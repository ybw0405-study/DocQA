from __future__ import annotations

import uuid
from pathlib import Path

import httpx
import pytest
from httpx import ASGITransport
from pydantic import SecretStr

from docqa_ai.api.main import (
    _PRODUCTION_ENV_NAMES,
    _runs_in_production,
    build_repositories,
    create_app,
)
from docqa_ai.protocol.models import DocumentScope
from docqa_ai.providers.embeddings import OfflineEmbeddingProvider
from docqa_ai.rag.repository import ChunkRecord, InMemoryVectorRepository
from docqa_ai.rag.service import RetrievalService, RetrievalUnavailable
from tests.conftest import DOCUMENT_ID, TENANT_ID, make_settings

ROOT = Path(__file__).resolve().parents[1]
SCOPE = DocumentScope(document_id=DOCUMENT_ID, index_version="v1")
EMBEDDING = tuple([1.0] + [0.0] * 1023)
TOKEN = "unit-test-internal-token"


def _clear_env_signals(monkeypatch: object) -> None:
    for name in _PRODUCTION_ENV_NAMES:
        monkeypatch.delenv(name, raising=False) # type: ignore[attr-defined]


# ── 生产判据反转（B2）：旧实现「含 prod 才算生产」，全仓无人设置这四个变量 ──────


def test_production_is_the_default_when_no_env_signals_are_set(monkeypatch: object) -> None:
    _clear_env_signals(monkeypatch)
    assert _runs_in_production() is True


def test_explicit_dev_signal_disables_the_production_guard(monkeypatch: object) -> None:
    _clear_env_signals(monkeypatch)
    monkeypatch.setenv("DOCQA_ENV", "dev") # type: ignore[attr-defined]
    assert _runs_in_production() is False
    monkeypatch.setenv("APP_ENV", "test") # type: ignore[attr-defined]
    assert _runs_in_production() is False


def test_default_app_carries_a_startup_guard_without_an_internal_token(monkeypatch: object) -> None:
    _clear_env_signals(monkeypatch)
    monkeypatch.delenv("DOCQA_AI_INTERNAL_TOKEN", raising=False) # type: ignore[attr-defined]
    app = create_app()
    assert app.state.startup_guard_error is not None
    assert "DOCQA_AI_INTERNAL_TOKEN" in app.state.startup_guard_error


def test_explicit_settings_skip_the_startup_guard_for_embedded_callers() -> None:
    app = create_app(make_settings(), agent_service=object()) # type: ignore[arg-type]
    assert app.state.startup_guard_error is None


def test_dev_on_non_loopback_without_credentials_is_rejected(monkeypatch: object) -> None:
    """dev + 0.0.0.0 且无任何凭据 = 数据面裸奔，必须在启动期挡住。"""
    _clear_env_signals(monkeypatch)
    monkeypatch.setenv("DOCQA_ENV", "dev") # type: ignore[attr-defined]
    monkeypatch.delenv("DOCQA_AI_INTERNAL_TOKEN", raising=False) # type: ignore[attr-defined]
    monkeypatch.delenv("DOCQA_AI_INTERNAL_HMAC_SECRET", raising=False) # type: ignore[attr-defined]
    monkeypatch.setenv("DOCQA_AI_API_HOST", "0.0.0.0") # type: ignore[attr-defined]
    monkeypatch.delenv("DOCQA_AI_ALLOW_ANONYMOUS_API", raising=False) # type: ignore[attr-defined]
    app = create_app()
    assert app.state.startup_guard_error is not None
    assert "non-loopback" in app.state.startup_guard_error


def test_dev_on_non_loopback_can_opt_in_anonymously(monkeypatch: object) -> None:
    """部署者显式 allow_anonymous_api=true 时放行这条本地联调路径。"""
    _clear_env_signals(monkeypatch)
    monkeypatch.setenv("DOCQA_ENV", "dev") # type: ignore[attr-defined]
    monkeypatch.delenv("DOCQA_AI_INTERNAL_TOKEN", raising=False) # type: ignore[attr-defined]
    monkeypatch.delenv("DOCQA_AI_INTERNAL_HMAC_SECRET", raising=False) # type: ignore[attr-defined]
    monkeypatch.setenv("DOCQA_AI_API_HOST", "0.0.0.0") # type: ignore[attr-defined]
    monkeypatch.setenv("DOCQA_AI_ALLOW_ANONYMOUS_API", "true") # type: ignore[attr-defined]
    app = create_app()
    assert app.state.startup_guard_error is None


async def test_internal_token_guard_uses_a_whitelist_not_a_blacklist() -> None:
    app = create_app(
        make_settings(internal_token=SecretStr(TOKEN)),
        agent_service=object(), # type: ignore[arg-type]
    )
    async with httpx.AsyncClient(
        transport=ASGITransport(app=app, raise_app_exceptions=False), base_url="http://test"
    ) as client:
        assert (await client.get("/health")).status_code == 200
        assert (await client.get("/openapi.json")).status_code == 401
        assert (await client.get("/docs")).status_code == 401
        assert (await client.get("/redoc")).status_code == 401
        assert (await client.get("/openapi.json", headers={"x-internal-token": TOKEN})).status_code == 200


# ── DQ-3：健康检查不再是恒 200 ──────────────────────────────────────────────


async def test_readiness_returns_503_when_redis_is_unreachable() -> None:
    class BrokenRedis:
        async def ping(self) -> None:
            raise OSError("connection refused")

    app = create_app(make_settings(), agent_service=object()) # type: ignore[arg-type]
    app.state.redis_client = BrokenRedis()
    async with httpx.AsyncClient(
        transport=ASGITransport(app=app, raise_app_exceptions=False), base_url="http://test"
    ) as client:
        response = await client.get("/health/ready")

    assert response.status_code == 503
    assert response.json()["checks"]["redis"] == "down"


async def test_liveness_stays_up_when_dependencies_are_absent() -> None:
    app = create_app(make_settings(), agent_service=object()) # type: ignore[arg-type]
    async with httpx.AsyncClient(
        transport=ASGITransport(app=app, raise_app_exceptions=False), base_url="http://test"
    ) as client:
        assert (await client.get("/health/live")).status_code == 200
        assert (await client.get("/health/ready")).status_code == 200


# ── 单一组装根（B2）：open_pool() 此前全仓零调用点 ───────────────────────────


def test_repositories_share_one_connection_provider() -> None:
    vector, lexical = build_repositories(make_settings(pg_dsn="postgresql://study@127.0.0.1:5432/docqa"))
    assert vector._connections is lexical._connections # type: ignore[attr-defined]


def test_pool_factory_is_wired_into_both_process_entrypoints() -> None:
    pg_source = (ROOT / "src/docqa_ai/rag/pg_connection.py").read_text(encoding="utf-8")
    api_source = (ROOT / "src/docqa_ai/api/main.py").read_text(encoding="utf-8")
    worker_source = (ROOT / "src/docqa_ai/parse_worker/worker.py").read_text(encoding="utf-8")
    assert "pool=open_pool(" in pg_source
    assert "build_pg_provider(" in api_source
    assert "build_pg_provider(" in worker_source
    assert "connection_provider=pg_provider" in worker_source


# ── 租户纵深防御：不再是 duck-typing 静默降级（B2）+ 替身语义（B3） ───────────


async def test_strict_mode_refuses_retrieval_without_a_tenant() -> None:
    service = RetrievalService(
        InMemoryVectorRepository([]),
        OfflineEmbeddingProvider(),
        require_tenant_id=True,
    )
    with pytest.raises(RetrievalUnavailable, match="tenant_id is required"):
        await service.retrieve("question", [SCOPE])


def test_strict_mode_refuses_a_repository_that_is_not_tenant_aware() -> None:
    class LegacyRepository:
        async def search(self, embedding: object, scopes: object, top_k: int) -> list[object]:
            return []

    try:
        RetrievalService(
            LegacyRepository(), # type: ignore[arg-type]
            OfflineEmbeddingProvider(),
            require_tenant_id=True,
        )
    except ValueError as exc:
        assert "not tenant-aware" in str(exc)
    else:
        raise AssertionError("strict mode must reject a repository without a tenant predicate")


async def test_in_memory_repository_excludes_unlabelled_rows_like_real_sql() -> None:
    def chunk(index: int, tenant: str, content: str) -> ChunkRecord:
        return ChunkRecord(
            vector_id=uuid.uuid4(),
            document_id=DOCUMENT_ID,
            chunk_index=index,
            content=content,
            embedding=EMBEDDING,
            metadata={"index_version": "v1"},
            tenant_id=tenant,
        )

    repository = InMemoryVectorRepository(
        [
            chunk(0, str(TENANT_ID), "owned by A"),
            chunk(1, "", "pre-migration row"),
            chunk(2, str(uuid.uuid4()), "owned by B"),
        ]
    )
    hits = await repository.search(EMBEDDING, [SCOPE], 10, tenant_id=str(TENANT_ID))

    # 真实 PG 的 WHERE tenant_id = %s 只命中 A；空串行与 B 的行都必须被排除。
    assert [hit.chunk_index for hit in hits] == [0]
