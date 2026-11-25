"""方案 4.2 步 3：内部鉴权从静态共享密钥升级为 HMAC 签名请求。

规范串把 method / path / 时间戳 / nonce / body 的 SHA-256 摘要一起签进去，服务端用短时钟漂移
窗口拒绝过期请求，并用 nonce 去重集合挡重放。密钥不进日志、不进响应、不进异常。

nonce 去重经 NonceStore 抽象：单实例用进程内实现，多副本换 RedisNonceStore（SET NX，
Redis 不可用时 fail-closed 拒绝请求——与数据面治理基调一致）。
"""

from __future__ import annotations

import hashlib
import hmac
import threading
import time
from typing import Protocol

TIMESTAMP_HEADER = "x-internal-timestamp"
SIGNATURE_HEADER = "x-internal-signature"
NONCE_HEADER = "x-internal-nonce"
MAX_SKEW_SECONDS = 60


class NonceStore(Protocol):
    """原子占位：返回 True 表示首次见到该 nonce，False 表示已存在（重复/重放）。"""

    def claim(self, nonce: str, ttl_s: float) -> bool: ...


class InProcessNonceStore:
    """进程内实现（单实例足够）；过期条目惰性清理。"""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._seen: dict[str, float] = {}

    def claim(self, nonce: str, ttl_s: float) -> bool:
        cutoff = time.time() - ttl_s
        with self._lock:
            for k, t in list(self._seen.items()):
                if t < cutoff:
                    del self._seen[k]
            if nonce in self._seen:
                return False
            self._seen[nonce] = time.time()
            return True


class RedisNonceStore:
    """多副本实现：SET key 1 NX EX ttl。Redis 故障时 fail-closed（拒绝请求）而非放行——
    防重放是安全边界，可用性由调用方（中间件 401 + 上游重试）承接，与预算/限流同基调。"""

    def __init__(self, client: object, *, key_prefix: str = "docqa:internal:nonce:") -> None:
        self._client = client
        self._prefix = key_prefix

    def claim(self, nonce: str, ttl_s: float) -> bool:
        try:
            return bool(self._client.set(f"{self._prefix}{nonce}", 1, nx=True, ex=max(1, int(ttl_s))))
        except Exception:
            # 故障静默拒（返回 False）会让签名正常的请求收到 401；这是有意的 fail-closed。
            return False


_default_nonce_store: InProcessNonceStore = InProcessNonceStore()


def canonical(method: str, path: str, timestamp: str, nonce: str, body: bytes) -> bytes:
    """签名输入：method + path（不含 query）+ 时间戳 + nonce + body 的十六进制摘要。"""
    body_hash = hashlib.sha256(body).hexdigest().encode("ascii")
    return b"\n".join(
        (
            method.upper().encode("utf-8"),
            path.encode("utf-8"),
            timestamp.encode("ascii"),
            nonce.encode("ascii"),
            body_hash,
        )
    )


def sign(
    secret: str, method: str, path: str, body: bytes = b"", *, timestamp: int | None = None, nonce: str | None = None
) -> dict[str, str]:
    """调用方（Java 网关 / 测试）生成三个请求头。nonce 默认随机生成。"""
    import uuid

    issued_at = str(int(time.time()) if timestamp is None else timestamp)
    n = nonce or uuid.uuid4().hex
    digest = hmac.new(secret.encode("utf-8"), canonical(method, path, issued_at, n, body), hashlib.sha256).hexdigest()
    return {TIMESTAMP_HEADER: issued_at, SIGNATURE_HEADER: digest, NONCE_HEADER: n}


def verify(
    secret: str,
    method: str,
    path: str,
    body: bytes,
    timestamp: str,
    signature: str,
    nonce: str = "",
    *,
    now: float | None = None,
    nonce_store: NonceStore | None = None,
) -> bool:
    """时间戳漂移 + 常量时间比较 + nonce 一次性；任一不满足都返回 False，不泄露失败原因。

    多副本部署必须显式注入 RedisNonceStore——默认进程内实现跨副本不共享，防重放会静默失效。
    """
    try:
        issued_at = int(timestamp)
    except (TypeError, ValueError):
        return False
    current = time.time() if now is None else now
    if abs(current - issued_at) > MAX_SKEW_SECONDS:
        return False
    expected = hmac.new(
        secret.encode("utf-8"), canonical(method, path, timestamp, nonce, body), hashlib.sha256
    ).hexdigest()
    if not (signature and hmac.compare_digest(signature, expected)):
        return False
    # nonce 一次性：签名正确后原子占位；重复 nonce 直接拒绝。
    if not nonce:
        return False
    store = nonce_store or _default_nonce_store
    return store.claim(nonce, MAX_SKEW_SECONDS)
