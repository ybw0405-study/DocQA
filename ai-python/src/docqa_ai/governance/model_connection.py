"""界面配置的模型连接：密文解封、Redis 读取与按请求选 planner。

网关（Java）把生效连接发布到 ``agent:model-connection:tenant:{tenantId}``，载荷里只有
**AES-256-GCM 密文**与非密文字段；本模块用同一把主密钥在本地解封，因此明文 API Key
既不过内部 ask HTTP（dev 形态下那条通道没有内部鉴权），也不以明文出现在 Redis。

三条语义刻意不同，别混：
* 键不存在 → 回退 env 配置的 provider（离线演示与占位符降级继续成立）；
* Redis 不可达 → 抛错（与 kill switch 同一 fail-closed 哲学，实际上 kill switch 会先拦下）；
* 密文解不开 / 端点不合规 → 硬失败，**绝不静默退回离线**——静默降级正是本功能要消除的行为。
"""

from __future__ import annotations

import base64
import hashlib
import inspect
import json
import logging
import time
from collections.abc import Sequence
from dataclasses import dataclass
from uuid import UUID

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from redis.asyncio import Redis
from redis.exceptions import RedisError

from docqa_ai.logging_setup import log_event
from docqa_ai.providers.planner import OfflinePlanner, OpenAIPlanner, Planner, PlannerUnavailable
from docqa_ai.tools.web.urls import UrlRejected, validate_url

LOGGER = logging.getLogger(__name__)

_IV_BYTES = 12
_KEY_BYTES = 32
_KEY_ID_HEX_CHARS = 12
# 被淘汰的 planner 可能仍被在途请求持有（图在请求开始时就抓住了引用），
# 立刻 aclose 会掐断正在输出的流；给一段宽限期再收口。
_CLOSE_GRACE_SECONDS = 30.0


class CredentialCipher:
    """与 Java ``CredentialCipher`` 逐字节兼容的解封端。

    密文格式 ``12B IV || ciphertext || 16B tag``，AAD 绑定 ``connectionId|tenantId``。
    keyId 是主密钥 SHA-256 的前 12 位十六进制；轮换后按密文携带的 keyId 从 keyring 选旧密钥。
    未知 keyId 直接拒绝，而不是拿当前密钥去试。
    """

    def __init__(self, master_key_b64: str, previous_keys: Sequence[str] = ()) -> None:
        raw = _decode_key(master_key_b64, "DOCQA_CRED_MASTER_KEY")
        self._keyring: dict[str, AESGCM] = {_key_id(raw): AESGCM(raw)}
        for previous in previous_keys:
            if not previous or not previous.strip():
                continue
            previous_raw = _decode_key(previous.strip(), "DOCQA_CRED_MASTER_KEY_PREVIOUS")
            self._keyring[_key_id(previous_raw)] = AESGCM(previous_raw)

    def open(self, stored: bytes, aad: bytes, key_id: str) -> bytes:
        chosen = self._keyring.get(key_id)
        if chosen is None:
            raise PlannerUnavailable("model connection credential uses an unknown keyId; re-enter the API key")
        if len(stored) <= _IV_BYTES:
            raise PlannerUnavailable("model connection credential ciphertext is malformed")
        try:
            return chosen.decrypt(stored[:_IV_BYTES], stored[_IV_BYTES:], aad)
        except InvalidTag as exc:
            raise PlannerUnavailable("model connection credential cannot be decrypted") from exc


def connection_aad(connection_id: str, tenant_id: UUID) -> bytes:
    return f"{connection_id}|{tenant_id}".encode()


def _decode_key(value: str, name: str) -> bytes:
    try:
        raw = base64.b64decode(value, validate=True)
    except Exception as exc:
        raise ValueError(f"{name} must be base64") from exc
    if len(raw) != _KEY_BYTES:
        raise ValueError(f"{name} must decode to {_KEY_BYTES} bytes, got {len(raw)}")
    return raw


def _key_id(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()[:_KEY_ID_HEX_CHARS]


@dataclass(frozen=True)
class ModelConnection:
    """网关发布的生效连接；credential 是密文，不是明文 Key。"""

    id: str
    base_url: str
    model: str
    credential: bytes | None
    key_id: str | None
    revision: int


@dataclass(frozen=True)
class ResolvedPlanner:
    """一次请求实际使用的 planner 与其身份；审计与 PlanContext 都用它，不再读 settings。"""

    planner: Planner
    model: str
    provider: str


class RedisModelConnectionStore:
    """读网关发布的生效连接，短缓存避开每请求一次 Redis 往返。

    缓存与失效口径照 ``RedisKillSwitch``：可注入时钟、故障态不进常规缓存窗口。
    区别是失败语义——kill switch 读不到就熔断，这里读不到就抛错交给上游映射，
    而**键不存在**是正常状态（该租户没配界面连接），回退 env provider。
    """

    def __init__(self, redis: Redis, key_prefix: str, cache_ms: int = 5000) -> None:
        self._redis = redis
        self._key_prefix = key_prefix
        self._cache_seconds = cache_ms / 1000.0
        self._cache: dict[str, tuple[ModelConnection | None, float]] = {}
        self._clock = time.monotonic

    def key(self, tenant_id: UUID) -> str:
        return f"{self._key_prefix}{tenant_id}"

    async def get(self, tenant_id: UUID) -> ModelConnection | None:
        key = self.key(tenant_id)
        now = self._clock()
        cached = self._cache.get(key)
        if cached is not None and now < cached[1]:
            return cached[0]
        try:
            raw = await self._redis.get(key)
        except RedisError:
            # 与 kill switch 同一哲学：读不到就不猜。猜一个 provider 等于静默改变外呼行为。
            log_event(
                LOGGER,
                logging.WARNING,
                "Model connection lookup failed; refusing to guess a provider",
                step="model_connection",
            )
            raise PlannerUnavailable("model connection store is unavailable") from None
        connection = None if raw is None else _parse(raw)
        self._cache[key] = (connection, now + self._cache_seconds)
        return connection


def _parse(raw: str | bytes) -> ModelConnection:
    text = raw.decode("utf-8") if isinstance(raw, bytes) else raw
    try:
        payload = json.loads(text)
    except (TypeError, ValueError) as exc:
        raise PlannerUnavailable("model connection payload is not valid JSON") from exc
    if not isinstance(payload, dict):
        raise PlannerUnavailable("model connection payload is not an object")
    try:
        credential_b64 = payload.get("credentialB64")
        return ModelConnection(
            id=str(payload["id"]),
            base_url=str(payload["baseUrl"]),
            model=str(payload["model"]),
            credential=None if credential_b64 is None else base64.b64decode(credential_b64, validate=True),
            key_id=None if payload.get("keyId") is None else str(payload["keyId"]),
            revision=int(payload.get("revision", 0)),
        )
    except (KeyError, TypeError, ValueError) as exc:
        raise PlannerUnavailable("model connection payload is missing required fields") from exc


class PlannerResolver:
    """按请求把生效连接解析成 planner；没有界面连接时回退到启动期构造的那个。

    planner 按 ``(connectionId, revision)`` 缓存：改配置会换 revision，于是新建一个
    httpx client，旧的进宽限期后再关，避免掐断在途流式响应。
    """

    def __init__(
        self,
        store: RedisModelConnectionStore,
        cipher: CredentialCipher,
        fallback: ResolvedPlanner,
        *,
        allowed_hosts: Sequence[str] = (),
        allow_private: bool = False,
        tool_protocol: str = "json",
        max_citations: int = 3,
        close_grace_seconds: float = _CLOSE_GRACE_SECONDS,
    ) -> None:
        self._store = store
        self._cipher = cipher
        self._fallback = fallback
        self._allowed_hosts = tuple(allowed_hosts)
        self._allow_private = allow_private
        self._tool_protocol = tool_protocol
        self._max_citations = max(1, max_citations)
        self._close_grace = close_grace_seconds
        self._cache: dict[tuple[str, int], ResolvedPlanner] = {}
        self._retired: list[tuple[Planner, float]] = []
        self._clock = time.monotonic

    async def resolve(self, tenant_id: UUID) -> ResolvedPlanner:
        await self._sweep()
        connection = await self._store.get(tenant_id)
        if connection is None:
            return self._fallback
        key = (connection.id, connection.revision)
        cached = self._cache.get(key)
        if cached is not None:
            return cached
        resolved = await self._build(connection, tenant_id)
        for stale_key, stale in list(self._cache.items()):
            if stale_key[0] == connection.id and stale_key != key:
                del self._cache[stale_key]
                self._retired.append((stale.planner, self._clock() + self._close_grace))
        self._cache[key] = resolved
        return resolved

    async def aclose(self) -> None:
        planners = [resolved.planner for resolved in self._cache.values()]
        planners.extend(planner for planner, _ in self._retired)
        self._cache.clear()
        self._retired.clear()
        for planner in planners:
            await _aclose(planner)

    async def _build(self, connection: ModelConnection, tenant_id: UUID) -> ResolvedPlanner:
        try:
            await validate_url(
                connection.base_url,
                allowed_hosts=self._allowed_hosts,
                allow_private=self._allow_private,
            )
        except UrlRejected as exc:
            raise PlannerUnavailable(f"model connection endpoint rejected: {exc}") from exc
        api_key = ""
        if connection.credential is not None:
            if not connection.key_id:
                raise PlannerUnavailable("model connection credential has no keyId")
            api_key = self._cipher.open(
                connection.credential, connection_aad(connection.id, tenant_id), connection.key_id
            ).decode("utf-8")
        return ResolvedPlanner(
            planner=OpenAIPlanner(
                connection.base_url,
                api_key,
                connection.model,
                tool_protocol=self._tool_protocol,
                max_citations=self._max_citations,
            ),
            model=connection.model,
            provider="openai",
        )

    async def _sweep(self) -> None:
        if not self._retired:
            return
        now = self._clock()
        due = [planner for planner, deadline in self._retired if now >= deadline]
        self._retired = [(planner, deadline) for planner, deadline in self._retired if now < deadline]
        for planner in due:
            await _aclose(planner)


async def _aclose(planner: Planner) -> None:
    closer = getattr(planner, "aclose", None)
    if closer is None:
        return
    result = closer()
    if inspect.isawaitable(result):
        await result


def offline_fallback(model: str) -> ResolvedPlanner:
    return ResolvedPlanner(planner=OfflinePlanner(model), model=model, provider="offline")
