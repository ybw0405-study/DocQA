"""界面模型连接：跨语言密文互操作、Redis 读取语义、按请求选 planner 与出网判定。

密文夹具与 Java 侧 ``CredentialCipherTest`` 共用同一份 base64：两侧都解得开，
才证明「网关封装、数据面解封」这条链在字节层面成立。密钥由公开常量派生，不是真实凭据。
"""

from __future__ import annotations

import base64
import hashlib
import json
import uuid

import httpx
import pytest
import respx
from fakeredis.aioredis import FakeRedis
from redis.exceptions import RedisError

from docqa_ai.api.main import build_experiment_ledger
from docqa_ai.config import Settings
from docqa_ai.governance.model_connection import (
    CredentialCipher,
    ModelConnection,
    PlannerResolver,
    RedisModelConnectionStore,
    ResolvedPlanner,
    connection_aad,
    offline_fallback,
)
from docqa_ai.providers.planner import ContextBlock, OfflinePlanner, OpenAIPlanner, PlanContext, PlannerUnavailable
from docqa_ai.tools.web.urls import UrlRejected, validate_url
from tests.conftest import TENANT_ID, make_settings

FIXTURE_KEY_B64 = "Ol6+TCExErUZz413bhIyEaI79pogf5/Bh3FAtPF0dtU="
FIXTURE_KEY_ID = "3a990d9492e6"
FIXTURE_CIPHERTEXT_B64 = "AAECAwQFBgcICQoLmb9lA5TMAFaUcL2oVa25OreCz+djACScj7NQ350pgbgWcINWq5W3EgU="
FIXTURE_PLAINTEXT = "sk-test-vector-0123456789"
FIXTURE_CONNECTION_ID = "aaaaaaaa-0000-0000-0000-000000000001"
FIXTURE_TENANT_ID = uuid.UUID("11111111-1111-1111-1111-111111111111")

MASTER_KEY = base64.b64encode(hashlib.sha256(b"docqa-python-test-master-key").digest()).decode()
OLD_KEY = base64.b64encode(hashlib.sha256(b"docqa-python-test-previous-key").digest()).decode()
SECRET = "sk-python-test-0123456789"
# 用公网 IP 字面量做端点：出网判定不需要 DNS，respx 也能精确拦截。
PUBLIC_BASE_URL = "https://93.184.216.34/v1"
KEY_PREFIX = "agent:model-connection:tenant:"


def _key_id(key_b64: str) -> str:
    return hashlib.sha256(base64.b64decode(key_b64)).hexdigest()[:12]


def _seal(key_b64: str, plaintext: str, connection_id: str, tenant_id: uuid.UUID) -> bytes:
    """测试侧封装：与 Java seal 同格式（12B IV || ct || 16B tag），IV 取固定值便于断言。"""
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM

    nonce = bytes(range(12))
    sealed = AESGCM(base64.b64decode(key_b64)).encrypt(
        nonce, plaintext.encode(), connection_aad(connection_id, tenant_id)
    )
    return nonce + sealed


def _payload(connection: ModelConnection) -> str:
    return json.dumps(
        {
            "id": connection.id,
            "baseUrl": connection.base_url,
            "model": connection.model,
            "credentialB64": None
            if connection.credential is None
            else base64.b64encode(connection.credential).decode(),
            "keyId": connection.key_id,
            "revision": connection.revision,
        }
    )


def _connection(**overrides) -> ModelConnection:
    sealed = _seal(MASTER_KEY, SECRET, FIXTURE_CONNECTION_ID, TENANT_ID)
    base = {
        "id": FIXTURE_CONNECTION_ID,
        "base_url": PUBLIC_BASE_URL,
        "model": "deepseek-chat",
        "credential": sealed,
        "key_id": _key_id(MASTER_KEY),
        "revision": 1700000000000,
    }
    base.update(overrides)
    return ModelConnection(**base)


def _plan_context(model: str) -> PlanContext:
    return PlanContext(
        question="资料里写了什么？",
        history=(),
        context_blocks=(
            ContextBlock(
                index=0,
                document_id=uuid.UUID("33333333-3333-4333-8333-333333333333"),
                chunk_index=0,
                content="正文",
                score=0.9,
            ),
        ),
        observations=(),
        allowed_tools=(),
        round_number=1,
        max_rounds=2,
        max_output_tokens=256,
        model=model,
        tool_specs=(),
    )


# --------------------------------------------------------------------- cipher


def test_opens_the_cross_language_fixture():
    cipher = CredentialCipher(FIXTURE_KEY_B64)
    opened = cipher.open(
        base64.b64decode(FIXTURE_CIPHERTEXT_B64),
        connection_aad(FIXTURE_CONNECTION_ID, FIXTURE_TENANT_ID),
        FIXTURE_KEY_ID,
    )
    assert opened.decode() == FIXTURE_PLAINTEXT


def test_key_id_matches_the_java_derivation():
    assert _key_id(FIXTURE_KEY_B64) == FIXTURE_KEY_ID


def test_refuses_ciphertext_moved_to_another_connection_or_tenant():
    cipher = CredentialCipher(MASTER_KEY)
    sealed = _seal(MASTER_KEY, SECRET, FIXTURE_CONNECTION_ID, TENANT_ID)
    other_connection = "aaaaaaaa-0000-0000-0000-000000000002"
    other_tenant = uuid.UUID("22222222-2222-2222-2222-222222222222")
    with pytest.raises(PlannerUnavailable):
        cipher.open(sealed, connection_aad(other_connection, TENANT_ID), _key_id(MASTER_KEY))
    with pytest.raises(PlannerUnavailable):
        cipher.open(sealed, connection_aad(FIXTURE_CONNECTION_ID, other_tenant), _key_id(MASTER_KEY))


def test_refuses_unknown_key_id_instead_of_guessing():
    cipher = CredentialCipher(MASTER_KEY)
    sealed = _seal(MASTER_KEY, SECRET, FIXTURE_CONNECTION_ID, TENANT_ID)
    with pytest.raises(PlannerUnavailable, match="unknown keyId"):
        cipher.open(sealed, connection_aad(FIXTURE_CONNECTION_ID, TENANT_ID), "ffffffffffff")


def test_opens_historical_ciphertext_after_rotation():
    historical = _seal(OLD_KEY, SECRET, FIXTURE_CONNECTION_ID, TENANT_ID)
    rotated = CredentialCipher(MASTER_KEY, [OLD_KEY])
    opened = rotated.open(historical, connection_aad(FIXTURE_CONNECTION_ID, TENANT_ID), _key_id(OLD_KEY))
    assert opened.decode() == SECRET
    # 旧密钥没保留时必须硬失败，而不是静默降级。
    with pytest.raises(PlannerUnavailable):
        CredentialCipher(MASTER_KEY).open(
            historical, connection_aad(FIXTURE_CONNECTION_ID, TENANT_ID), _key_id(OLD_KEY)
        )


def test_refuses_malformed_ciphertext_and_bad_master_keys():
    cipher = CredentialCipher(MASTER_KEY)
    aad = connection_aad(FIXTURE_CONNECTION_ID, TENANT_ID)
    with pytest.raises(PlannerUnavailable, match="malformed"):
        cipher.open(bytes(12), aad, _key_id(MASTER_KEY))
    with pytest.raises(ValueError, match="must be base64"):
        CredentialCipher("not-base64!!")
    with pytest.raises(ValueError, match="32 bytes"):
        CredentialCipher(base64.b64encode(bytes(16)).decode())


# ---------------------------------------------------------------------- store


async def test_absent_key_means_no_ui_connection():
    store = RedisModelConnectionStore(FakeRedis(decode_responses=True), KEY_PREFIX)
    assert await store.get(TENANT_ID) is None


async def test_reads_and_caches_the_published_connection():
    redis = FakeRedis(decode_responses=True)
    connection = _connection()
    await redis.set(KEY_PREFIX + str(TENANT_ID), _payload(connection))
    store = RedisModelConnectionStore(redis, KEY_PREFIX, cache_ms=5000)
    now = [1000.0]
    store._clock = lambda: now[0]

    first = await store.get(TENANT_ID)
    assert first == connection
    # 缓存窗口内改 Redis 不应被读到：证明缓存真的生效，而不是每次直连。
    await redis.set(KEY_PREFIX + str(TENANT_ID), _payload(_connection(model="changed")))
    assert (await store.get(TENANT_ID)).model == "deepseek-chat"
    now[0] += 6.0
    assert (await store.get(TENANT_ID)).model == "changed"


async def test_redis_failure_refuses_to_guess_a_provider():
    class FailingRedis:
        async def get(self, key):
            raise RedisError("connection reset")

    store = RedisModelConnectionStore(FailingRedis(), KEY_PREFIX)
    with pytest.raises(PlannerUnavailable):
        await store.get(TENANT_ID)


async def test_malformed_payload_is_a_hard_failure_not_a_fallback():
    redis = FakeRedis(decode_responses=True)
    store = RedisModelConnectionStore(redis, KEY_PREFIX)
    await redis.set(KEY_PREFIX + str(TENANT_ID), "not-json")
    with pytest.raises(PlannerUnavailable):
        await store.get(TENANT_ID)
    await redis.set(KEY_PREFIX + str(TENANT_ID), json.dumps({"id": "x"}))
    with pytest.raises(PlannerUnavailable):
        await store.get(TENANT_ID)


async def test_accepts_bytes_payloads_from_non_decoding_clients():
    redis = FakeRedis(decode_responses=False)
    await redis.set(KEY_PREFIX + str(TENANT_ID), _payload(_connection()).encode())
    store = RedisModelConnectionStore(redis, KEY_PREFIX)
    assert (await store.get(TENANT_ID)).model == "deepseek-chat"


# ------------------------------------------------------------------- resolver


def _resolver(redis, *, allow_private=False, allowed_hosts=(), grace=30.0) -> PlannerResolver:
    return PlannerResolver(
        RedisModelConnectionStore(redis, KEY_PREFIX),
        CredentialCipher(MASTER_KEY),
        offline_fallback("offline-extractive"),
        allowed_hosts=allowed_hosts,
        allow_private=allow_private,
        close_grace_seconds=grace,
    )


async def test_falls_back_to_env_planner_when_no_connection_exists():
    resolver = _resolver(FakeRedis(decode_responses=True))
    resolved = await resolver.resolve(TENANT_ID)
    assert resolved.provider == "offline"
    assert isinstance(resolved.planner, OfflinePlanner)
    await resolver.aclose()


@respx.mock
async def test_resolved_planner_calls_the_configured_endpoint_with_the_decrypted_key():
    redis = FakeRedis(decode_responses=True)
    await redis.set(KEY_PREFIX + str(TENANT_ID), _payload(_connection()))
    resolver = _resolver(redis)
    route = respx.post(PUBLIC_BASE_URL + "/chat/completions").mock(
        return_value=httpx.Response(
            200,
            json={
                "choices": [{"message": {"content": '{"action":"final","answer":"ok","citations":[]}'}}],
                "usage": {"prompt_tokens": 10, "completion_tokens": 5},
            },
        )
    )

    resolved = await resolver.resolve(TENANT_ID)

    assert resolved.provider == "openai"
    assert resolved.model == "deepseek-chat"
    assert isinstance(resolved.planner, OpenAIPlanner)
    await resolved.planner.plan(_plan_context(resolved.model), timeout_s=2.0)
    assert route.called
    request = route.calls.last.request
    assert request.headers["Authorization"] == "Bearer " + SECRET
    assert json.loads(request.content)["model"] == "deepseek-chat"
    await resolver.aclose()


async def test_caches_by_connection_and_revision_and_retires_the_old_client():
    redis = FakeRedis(decode_responses=True)
    await redis.set(KEY_PREFIX + str(TENANT_ID), _payload(_connection()))
    resolver = _resolver(redis, grace=30.0)
    now = [1000.0]
    resolver._clock = lambda: now[0]
    resolver._store._clock = lambda: now[0]

    first = await resolver.resolve(TENANT_ID)
    assert await resolver.resolve(TENANT_ID) is first

    now[0] += 6.0
    await redis.set(
        KEY_PREFIX + str(TENANT_ID), _payload(_connection(revision=1700000000001, model="deepseek-reasoner"))
    )
    second = await resolver.resolve(TENANT_ID)
    assert second is not first
    assert second.model == "deepseek-reasoner"
    # 旧 client 进宽限期而不是立刻关闭：在途流式响应仍持有它。
    assert len(resolver._retired) == 1
    now[0] += 31.0
    await resolver.resolve(TENANT_ID)
    assert resolver._retired == []
    await resolver.aclose()


async def test_rejects_loopback_endpoint_without_explicit_opt_in():
    redis = FakeRedis(decode_responses=True)
    await redis.set(KEY_PREFIX + str(TENANT_ID), _payload(_connection(base_url="http://127.0.0.1:11434/v1")))
    resolver = _resolver(redis)
    with pytest.raises(PlannerUnavailable, match="rejected"):
        await resolver.resolve(TENANT_ID)
    await resolver.aclose()


async def test_allow_private_opens_self_hosted_endpoints():
    redis = FakeRedis(decode_responses=True)
    await redis.set(KEY_PREFIX + str(TENANT_ID), _payload(_connection(base_url="http://127.0.0.1:11434/v1")))
    resolver = _resolver(redis, allow_private=True)
    resolved = await resolver.resolve(TENANT_ID)
    assert resolved.provider == "openai"
    await resolver.aclose()


async def test_allowlist_is_enforced_on_the_ui_supplied_url():
    redis = FakeRedis(decode_responses=True)
    await redis.set(KEY_PREFIX + str(TENANT_ID), _payload(_connection()))
    resolver = _resolver(redis, allowed_hosts=("example.com",))
    with pytest.raises(PlannerUnavailable, match="rejected"):
        await resolver.resolve(TENANT_ID)
    await resolver.aclose()


async def test_credential_without_key_id_is_a_hard_failure():
    redis = FakeRedis(decode_responses=True)
    await redis.set(KEY_PREFIX + str(TENANT_ID), _payload(_connection(key_id=None)))
    resolver = _resolver(redis)
    with pytest.raises(PlannerUnavailable, match="no keyId"):
        await resolver.resolve(TENANT_ID)
    await resolver.aclose()


async def test_no_key_connection_still_resolves_for_local_endpoints():
    redis = FakeRedis(decode_responses=True)
    await redis.set(
        KEY_PREFIX + str(TENANT_ID),
        _payload(_connection(base_url="http://127.0.0.1:11434/v1", credential=None, key_id=None)),
    )
    resolver = _resolver(redis, allow_private=True)
    resolved = await resolver.resolve(TENANT_ID)
    assert resolved.provider == "openai"
    await resolver.aclose()


# ------------------------------------------------------------- config & ledger


def test_flag_requires_budgets_and_master_key_even_when_env_is_offline():
    with pytest.raises(ValueError, match="experiment_budget"):
        make_settings(model_connections_enabled=True, cred_master_key=MASTER_KEY)
    with pytest.raises(ValueError, match="DOCQA_CRED_MASTER_KEY"):
        make_settings(
            model_connections_enabled=True,
            cred_master_key="",
            experiment_budget=10,
            experiment_token_budget=1000,
        )
    settings = make_settings(
        model_connections_enabled=True,
        cred_master_key=MASTER_KEY,
        experiment_budget=10,
        experiment_token_budget=1000,
    )
    assert settings.model_connections_enabled is True


def test_ledger_exists_when_ui_connections_are_enabled_even_if_env_is_offline():
    settings = make_settings(
        model_connections_enabled=True,
        cred_master_key=MASTER_KEY,
        experiment_budget=10,
        experiment_token_budget=1000,
    )
    assert build_experiment_ledger(settings, FakeRedis(decode_responses=True)) is not None
    # 开关关闭且全离线时仍然一个键都不写（的默认形态不变）。
    assert build_experiment_ledger(make_settings(), FakeRedis(decode_responses=True)) is None


def test_allowed_hosts_and_previous_keys_parse_comma_separated_values():
    settings = make_settings(
        llm_allowed_hosts=" api.deepseek.com , api.openai.com ,",
        cred_master_key_previous=f" {OLD_KEY} , ,",
    )
    assert settings.llm_allowed_host_entries == ("api.deepseek.com", "api.openai.com")
    assert settings.cred_previous_key_entries == (OLD_KEY,)


def test_allowed_hosts_must_be_bare_hostnames():
    with pytest.raises(ValueError, match="bare hostnames"):
        make_settings(llm_allowed_hosts="https://api.deepseek.com/v1")


def test_redacted_summary_never_leaks_the_master_key():
    settings = make_settings(
        model_connections_enabled=True,
        cred_master_key=MASTER_KEY,
        experiment_budget=10,
        experiment_token_budget=1000,
    )
    summary = settings.redacted_summary()
    assert summary["model_connections_enabled"] is True
    assert summary["cred_master_key_configured"] is True
    assert MASTER_KEY not in json.dumps(summary)
    assert "cred_master_key" not in summary


def test_master_key_is_read_from_the_shared_env_var(monkeypatch):
    monkeypatch.setenv("DOCQA_CRED_MASTER_KEY", MASTER_KEY)
    monkeypatch.setenv("DOCQA_LLM_ALLOW_PRIVATE", "true")
    settings = Settings(_env_file=None, environment="test", pg_dsn="", otel_enabled=False)
    assert settings.cred_master_key.get_secret_value() == MASTER_KEY
    assert settings.llm_allow_private is True


# ------------------------------------------------------------------ url policy


async def test_python_and_java_reject_the_same_metadata_endpoint():
    with pytest.raises(UrlRejected):
        await validate_url("http://169.254.169.254/latest/meta-data")
    target = await validate_url(PUBLIC_BASE_URL)
    assert target.host == "93.184.216.34"


def test_offline_fallback_reports_its_own_identity():
    resolved = offline_fallback("offline-extractive")
    assert isinstance(resolved, ResolvedPlanner)
    assert resolved.provider == "offline"
    assert resolved.planner.model == "offline-extractive"
