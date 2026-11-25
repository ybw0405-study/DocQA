"""方案 4.2 步 3：HMAC 内部鉴权 + /metrics 独立内网端口的回归用例。"""

from __future__ import annotations

import json
import time

import httpx
from httpx import ASGITransport
from pydantic import SecretStr

from docqa_ai.api.internal_auth import (
    MAX_SKEW_SECONDS,
    NONCE_HEADER,
    SIGNATURE_HEADER,
    TIMESTAMP_HEADER,
    sign,
    verify,
)
from docqa_ai.api.main import build_metrics_app, create_app
from docqa_ai.business_metrics import BusinessMetrics
from docqa_ai.config import Settings
from tests.conftest import make_settings, request_payload

SECRET = "unit-test-hmac-secret"
TOKEN = "unit-test-internal-token"


def _hmac_app() -> object:
    return create_app(
        make_settings(internal_token=SecretStr(TOKEN), internal_hmac_secret=SecretStr(SECRET)),
        agent_service=object(), # type: ignore[arg-type]
    )


def _client(app: object) -> httpx.AsyncClient:
    return httpx.AsyncClient(transport=ASGITransport(app=app, raise_app_exceptions=False), base_url="http://test") # type: ignore[arg-type]


# ── 签名规范串：时间戳 + method + path + body ────────────────────────────────


def test_sign_verify_roundtrip() -> None:
    headers = sign(SECRET, "POST", "/agent/ask", b'{"question":"x"}')
    assert verify(
        SECRET,
        "POST",
        "/agent/ask",
        b'{"question":"x"}',
        headers[TIMESTAMP_HEADER],
        headers[SIGNATURE_HEADER],
        headers.get(NONCE_HEADER, ""),
    )


def test_verify_rejects_stale_timestamp() -> None:
    stale = sign(SECRET, "GET", "/openapi.json", timestamp=int(time.time()) - MAX_SKEW_SECONDS - 10)
    assert not verify(
        SECRET,
        "GET",
        "/openapi.json",
        b"",
        stale[TIMESTAMP_HEADER],
        stale[SIGNATURE_HEADER],
        stale.get(NONCE_HEADER, ""),
    )


def test_verify_rejects_tampered_body() -> None:
    headers = sign(SECRET, "POST", "/agent/ask", b'{"amount":1}')
    assert not verify(
        SECRET,
        "POST",
        "/agent/ask",
        b'{"amount":2}',
        headers[TIMESTAMP_HEADER],
        headers[SIGNATURE_HEADER],
        headers.get(NONCE_HEADER, ""),
    )


def test_signature_covers_method_and_path() -> None:
    headers = sign(SECRET, "GET", "/docs")
    assert not verify(
        SECRET,
        "GET",
        "/openapi.json",
        b"",
        headers[TIMESTAMP_HEADER],
        headers[SIGNATURE_HEADER],
        headers.get(NONCE_HEADER, ""),
    )


def test_verify_rejects_replayed_nonce() -> None:
    """nonce 一次性——同一签名请求第二次校验必须拒绝（防重放）。"""
    headers = sign(SECRET, "POST", "/agent/ask", b'{"q":1}')
    args = (
        SECRET,
        "POST",
        "/agent/ask",
        b'{"q":1}',
        headers[TIMESTAMP_HEADER],
        headers[SIGNATURE_HEADER],
        headers[NONCE_HEADER],
    )
    assert verify(*args) is True
    assert verify(*args) is False


# ── 中间件：HMAC 模式覆盖全部路径，白名单只留 /health* ──────────────────────


async def test_hmac_guard_accepts_signed_request() -> None:
    headers = sign(SECRET, "GET", "/openapi.json")
    async with _client(_hmac_app()) as client:
        assert (await client.get("/openapi.json", headers=headers)).status_code == 200


async def test_hmac_guard_rejects_unsigned_request_even_with_static_token() -> None:
    # 静态 X-Internal-Token 在 HMAC 模式下不再是通行凭据。
    async with _client(_hmac_app()) as client:
        response = await client.get("/openapi.json", headers={"x-internal-token": TOKEN})
    assert response.status_code == 401
    assert response.json()["code"] == "AGT-AUTH-002"


async def test_hmac_guard_rejects_bad_signature() -> None:
    headers = sign(SECRET, "GET", "/openapi.json")
    headers[SIGNATURE_HEADER] = "0" * 64
    async with _client(_hmac_app()) as client:
        assert (await client.get("/openapi.json", headers=headers)).status_code == 401


async def test_hmac_guard_exempts_health_probes() -> None:
    async with _client(_hmac_app()) as client:
        assert (await client.get("/health/live")).status_code == 200
        assert (await client.get("/health/ready")).status_code == 200


async def test_hmac_guard_covers_docs_redoc_and_openapi() -> None:
    async with _client(_hmac_app()) as client:
        for path in ("/docs", "/redoc", "/openapi.json"):
            assert (await client.get(path)).status_code == 401


async def test_signed_post_body_is_replayed_to_the_endpoint() -> None:
    # 中间件读过 body 做签名；下游必须仍能拿到它（BaseHTTPMiddleware 重放），
    # 否则会变成 400 invalid JSON。这里只断言「不是签名 / 解析拒绝」。
    body = json.dumps(request_payload()).encode("utf-8")
    headers = sign(SECRET, "POST", "/agent/ask", body)
    headers["content-type"] = "application/json"
    async with _client(_hmac_app()) as client:
        response = await client.post("/agent/ask", content=body, headers=headers)
    assert response.status_code not in (401, 400, 413)


async def test_static_token_compat_remains_when_hmac_is_not_configured() -> None:
    # 兼容期回归：未配 HMAC secret 时，上一轮的静态 token 白名单行为逐字节不变。
    app = create_app(make_settings(internal_token=SecretStr(TOKEN)), agent_service=object()) # type: ignore[arg-type]
    async with _client(app) as client:
        assert (await client.get("/openapi.json")).status_code == 401
        assert (await client.get("/openapi.json", headers={"x-internal-token": TOKEN})).status_code == 200


# ── /metrics 独立端口 ────────────────────────────────────────────────────────


def test_main_app_has_no_metrics_route() -> None:
    app = create_app(make_settings(metrics_exposed=True), agent_service=object()) # type: ignore[arg-type]
    paths = {getattr(route, "path", "") for route in app.routes}
    assert "/metrics" not in paths


def test_metrics_app_exposes_only_the_prometheus_endpoint() -> None:
    app = build_metrics_app(BusinessMetrics())
    paths = {getattr(route, "path", "") for route in app.routes}
    assert "/metrics" in paths
    assert "/docs" not in paths


def test_metrics_defaults_are_loopback_distinct_and_disabled() -> None:
    settings = Settings(_env_file=None)
    assert settings.metrics_exposed is False # 生产默认不暴露，语义不变
    assert settings.metrics_host == "127.0.0.1"
    assert settings.metrics_port != settings.api_port
