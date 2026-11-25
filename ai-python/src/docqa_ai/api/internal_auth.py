"""方案 4.2 步 3：内部鉴权从静态共享密钥升级为 HMAC 签名请求。

规范串把 method / path / 时间戳 / nonce / body 的 SHA-256 摘要一起签进去，服务端用短时钟漂移
窗口拒绝过期请求，并用 nonce 去重集合挡重放（P1-6 修复：nonce 之前声称有但实际没进签名串、
也没去重）。密钥不进日志、不进响应、不进异常。
"""

from __future__ import annotations

import hashlib
import hmac
import threading
import time

TIMESTAMP_HEADER = "x-internal-timestamp"
SIGNATURE_HEADER = "x-internal-signature"
NONCE_HEADER = "x-internal-nonce"
MAX_SKEW_SECONDS = 60

# 进程内 nonce 去重（单实例足够；多副本部署应换 Redis SET NX EX，接口保持一致）。
_nonce_lock = threading.Lock()
_seen_nonces: dict[str, float] = {}


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
) -> bool:
    """时间戳漂移 + 常量时间比较 + nonce 一次性；任一不满足都返回 False，不泄露失败原因。"""
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
    cutoff = current - MAX_SKEW_SECONDS
    with _nonce_lock:
        for k, t in list(_seen_nonces.items()):
            if t < cutoff:
                del _seen_nonces[k]
        if nonce in _seen_nonces:
            return False
        _seen_nonces[nonce] = current
    return True
