"""方案 4.2 步 3：内部鉴权从静态共享密钥升级为 HMAC 签名请求。

规范串把 method / path / 时间戳 / body 的 SHA-256 摘要一起签进去，服务端用短时钟漂移
窗口拒绝过期请求，配合调用方 nonce 可挡重放。密钥不进日志、不进响应、不进异常。
"""

from __future__ import annotations

import hashlib
import hmac
import time

TIMESTAMP_HEADER = "x-internal-timestamp"
SIGNATURE_HEADER = "x-internal-signature"
MAX_SKEW_SECONDS = 60


def canonical(method: str, path: str, timestamp: str, body: bytes) -> bytes:
    """签名输入：method + path（不含 query）+ 时间戳原串 + body 的十六进制摘要。"""
    body_hash = hashlib.sha256(body).hexdigest().encode("ascii")
    return b"\n".join(
        (
            method.upper().encode("utf-8"),
            path.encode("utf-8"),
            timestamp.encode("ascii"),
            body_hash,
        )
    )


def sign(secret: str, method: str, path: str, body: bytes = b"", *, timestamp: int | None = None) -> dict[str, str]:
    """调用方（Java 网关 / 测试）生成两个请求头。"""
    issued_at = str(int(time.time()) if timestamp is None else timestamp)
    digest = hmac.new(secret.encode("utf-8"), canonical(method, path, issued_at, body), hashlib.sha256).hexdigest()
    return {TIMESTAMP_HEADER: issued_at, SIGNATURE_HEADER: digest}


def verify(
    secret: str,
    method: str,
    path: str,
    body: bytes,
    timestamp: str,
    signature: str,
    *,
    now: float | None = None,
) -> bool:
    """时间戳漂移 + 常量时间比较；任一不满足都返回 False，不泄露失败原因。"""
    try:
        issued_at = int(timestamp)
    except (TypeError, ValueError):
        return False
    current = time.time() if now is None else now
    if abs(current - issued_at) > MAX_SKEW_SECONDS:
        return False
    expected = hmac.new(secret.encode("utf-8"), canonical(method, path, timestamp, body), hashlib.sha256).hexdigest()
    return bool(signature) and hmac.compare_digest(signature, expected)
