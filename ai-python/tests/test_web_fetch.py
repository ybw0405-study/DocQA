from __future__ import annotations

from collections.abc import Callable

import httpx
import pytest

from docqa_ai.agent.errors import DeadlineExceeded
from docqa_ai.governance.budget import Deadline
from docqa_ai.tools.base import ToolContext, ToolError
from docqa_ai.tools.web.client import FetchRejected
from docqa_ai.tools.web_fetch import WebFetchTool
from tests.web_testkit import deadline_clock, html_response, make_client, tool_context

PAGE = """<html><head><title>课程页</title></head><body>
<nav>导航</nav>
<article><h1>概率论</h1><p>条件概率描述已知信息后的不确定性。</p></article>
</body></html>"""

Handler = Callable[[httpx.Request], httpx.Response]


def handler_with_robots(robots: str = "User-agent: *\nAllow: /\n") -> tuple[Handler, list[httpx.Request]]:
    requests: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        if request.url.path == "/robots.txt":
            return httpx.Response(200, text=robots, headers={"content-type": "text/plain"})
        if request.url.path == "/page":
            return html_response(PAGE)
        if request.url.path == "/start":
            return httpx.Response(302, headers={"location": "/page"})
        if request.url.path == "/missing":
            return httpx.Response(404, text="not found")
        if request.url.path == "/binary":
            return httpx.Response(200, headers={"content-type": "application/pdf"}, content=b"%PDF")
        return httpx.Response(404, text="unknown")

    return handler, requests


async def test_client_without_a_resolver_still_checks_dns(monkeypatch: pytest.MonkeyPatch) -> None:
    """S7b-6-H1：生产默认不注入 resolver，真实域名必须走系统解析而不是 TypeError。"""

    calls: list[str] = []

    async def fake_resolver(host: str, port: int) -> tuple[str, ...]:
        calls.append(host)
        return ("93.184.216.34",)

    monkeypatch.setattr("docqa_ai.tools.web.urls._system_resolver", fake_resolver)
    handler, _ = handler_with_robots()
    client = make_client(handler, resolver=None)
    try:
        result = await WebFetchTool(client).run("https://example.com/page", tool_context())
    finally:
        await client.aclose()
    assert "条件概率描述已知信息后的不确定性" in result.observation
    assert calls == ["example.com", "example.com"] # 预检一次，robots.txt 目标再解析一次


async def test_fetches_html_and_wraps_as_untrusted() -> None:
    handler, requests = handler_with_robots()
    client = make_client(handler) # type: ignore[arg-type]
    try:
        result = await WebFetchTool(client).run("https://example.com/page", tool_context())
    finally:
        await client.aclose()
    assert "条件概率描述已知信息后的不确定性" in result.observation
    assert result.observation.startswith('<untrusted source="web:example.com">')
    assert "导航" not in result.observation
    assert result.masked_count == 0
    assert str(requests[0].url).endswith("/robots.txt")
    assert str(requests[1].url) == "https://example.com/page"


async def test_robots_cache_hits_are_counted_without_a_second_request() -> None:
    handler, requests = handler_with_robots()
    hits: list[int] = []
    client = make_client(handler, on_cache_hit=lambda: hits.append(1)) # type: ignore[arg-type]
    try:
        await WebFetchTool(client).run("https://example.com/page", tool_context())
        await WebFetchTool(client).run("https://example.com/page", tool_context())
    finally:
        await client.aclose()
    assert len(hits) == 1
    assert sum(1 for request in requests if request.url.path == "/robots.txt") == 1


async def test_robots_disallow_blocks_before_the_page_request() -> None:
    handler, requests = handler_with_robots("User-agent: *\nDisallow: /page\n")
    client = make_client(handler) # type: ignore[arg-type]
    try:
        with pytest.raises(ToolError, match="rejected"):
            await WebFetchTool(client).run("https://example.com/page", tool_context())
    finally:
        await client.aclose()
    assert [request.url.path for request in requests] == ["/robots.txt"]


async def test_robots_server_error_is_fail_closed() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(503, text="down")

    client = make_client(handler)
    try:
        with pytest.raises(ToolError, match="rejected"):
            await WebFetchTool(client).run("https://example.com/page", tool_context())
    finally:
        await client.aclose()


async def test_redirect_is_followed_and_revalidated() -> None:
    handler, requests = handler_with_robots()
    client = make_client(handler) # type: ignore[arg-type]
    try:
        result = await WebFetchTool(client).run("https://example.com/start", tool_context())
    finally:
        await client.aclose()
    assert "条件概率" in result.observation
    assert [request.url.path for request in requests] == ["/robots.txt", "/start", "/page"]


async def test_redirect_to_private_host_is_rejected() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/robots.txt":
            return httpx.Response(404, text="none")
        return httpx.Response(302, headers={"location": "http://127.0.0.1/secret"})

    client = make_client(handler)
    try:
        with pytest.raises(ToolError, match="rejected"):
            await WebFetchTool(client).run("http://example.com/start", tool_context())
    finally:
        await client.aclose()


async def test_https_to_http_downgrade_is_rejected() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/robots.txt":
            return httpx.Response(404, text="none")
        return httpx.Response(302, headers={"location": "http://example.com/plain"})

    client = make_client(handler)
    try:
        with pytest.raises(ToolError, match="rejected"):
            await WebFetchTool(client).run("https://example.com/start", tool_context())
    finally:
        await client.aclose()


async def test_redirect_budget_is_enforced() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/robots.txt":
            return httpx.Response(404, text="none")
        return httpx.Response(302, headers={"location": request.url.path.replace("hop", "next")})

    client = make_client(handler)
    try:
        with pytest.raises(ToolError, match="rejected"):
            await WebFetchTool(client).run("https://example.com/hop1", tool_context())
    finally:
        await client.aclose()


async def test_non_2xx_status_is_a_tool_error() -> None:
    handler, _ = handler_with_robots()
    client = make_client(handler) # type: ignore[arg-type]
    try:
        with pytest.raises(ToolError, match="HTTP 404"):
            await WebFetchTool(client).run("https://example.com/missing", tool_context())
    finally:
        await client.aclose()


async def test_only_html_and_text_are_accepted() -> None:
    handler, _ = handler_with_robots()
    client = make_client(handler) # type: ignore[arg-type]
    try:
        with pytest.raises(ToolError, match="HTML or plain text"):
            await WebFetchTool(client).run("https://example.com/binary", tool_context())
    finally:
        await client.aclose()


async def test_declared_content_length_over_ceiling_is_rejected() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/robots.txt":
            return httpx.Response(404, text="none")
        return httpx.Response(
            200,
            headers={"content-type": "text/html", "content-length": "999999"},
            content=b"<html><body>ok</body></html>",
        )

    client = make_client(handler, max_bytes=1024)
    try:
        with pytest.raises(ToolError, match="too large"):
            await WebFetchTool(client).run("https://example.com/page", tool_context())
    finally:
        await client.aclose()


async def test_streamed_body_is_truncated_at_the_byte_ceiling() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/robots.txt":
            return httpx.Response(404, text="none")
        payload = b"<html><body>" + b"x" * 5000
        # 不声明 content-length，只能靠流式累计触发字节上限。
        return httpx.Response(200, headers={"content-type": "text/html"}, stream=httpx.ByteStream(payload))

    client = make_client(handler, max_bytes=1024)
    try:
        result = await WebFetchTool(client).run("https://example.com/page", tool_context())
    finally:
        await client.aclose()
    assert "x" in result.observation
    assert "字节上限" in result.observation


async def test_pii_is_masked_in_input_url_and_output() -> None:
    requests: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        if request.url.path == "/robots.txt":
            return httpx.Response(404, text="none")
        return html_response("<html><body><p>联系 student@example.com</p></body></html>")

    client = make_client(handler)
    try:
        result = await WebFetchTool(client).run(
            "https://example.com/page?email=student@example.com",
            tool_context(),
        )
    finally:
        await client.aclose()
    assert "student@example.com" not in result.observation
    assert "[EMAIL]" in result.observation
    assert result.masked_count == 2
    assert result.pii_categories == ("EMAIL",)
    assert "student@example.com" not in str(requests[-1].url)


async def test_output_is_truncated_below_the_configured_limit() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/robots.txt":
            return httpx.Response(404, text="none")
        return html_response("<html><body><p>" + ("内容" * 3000) + "</p></body></html>")

    client = make_client(handler)
    try:
        result = await WebFetchTool(client, max_chars=4000, max_output_chars=300).run(
            "https://example.com/page",
            tool_context(),
        )
    finally:
        await client.aclose()
    assert len(result.observation) <= 300
    assert result.observation.endswith("</untrusted>")


async def test_max_chars_must_stay_within_configuration() -> None:
    handler, _ = handler_with_robots()
    client = make_client(handler)
    try:
        with pytest.raises(ToolError, match="maxChars is out of range"):
            await WebFetchTool(client, max_chars=500).run(
                '{"url": "https://example.com/page", "maxChars": 900}',
                tool_context(),
            )
    finally:
        await client.aclose()


async def test_expired_deadline_stops_before_any_request() -> None:
    requests: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        return httpx.Response(200, text="unexpected")

    clock, now = deadline_clock()
    base = tool_context()
    context = ToolContext(scopes=base.scopes, retrieval=base.retrieval, deadline=Deadline(10, clock=clock))
    now[0] = 1.0
    client = make_client(handler)
    try:
        with pytest.raises(DeadlineExceeded):
            await WebFetchTool(client).run("https://example.com/page", context)
    finally:
        await client.aclose()
    assert requests == []


async def test_invalid_json_input_fails_closed() -> None:
    client = make_client(lambda request: httpx.Response(500))
    try:
        with pytest.raises(ToolError, match="JSON object"):
            await WebFetchTool(client).run("{not json", tool_context())
    finally:
        await client.aclose()


class _PeerStream:
    """Stands in for httpcore's network stream so the peer address is observable."""

    def __init__(self, address: str) -> None:
        self._address = address

    def get_extra_info(self, info: str):
        return (self._address, 443) if info == "server_addr" else None


def _with_peer(response: httpx.Response, address: str) -> httpx.Response:
    response.extensions["network_stream"] = _PeerStream(address)
    return response


def _peer_handler(address: str) -> Handler:
    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/robots.txt":
            return httpx.Response(200, text="User-agent: *\nAllow: /\n")
        return _with_peer(html_response(PAGE), address)

    return handler


async def test_connection_landing_on_a_private_peer_is_rejected() -> None:
    # 预检解析到公网，但真正连上的是内网地址，即 DNS rebinding 的 TOCTOU（S7b3-M2）。
    client = make_client(_peer_handler("10.0.0.7")) # type: ignore[arg-type]
    try:
        with pytest.raises(FetchRejected, match="connection peer"):
            await client.get("https://example.com/page")
    finally:
        await client.aclose()


async def test_public_peer_is_accepted() -> None:
    client = make_client(_peer_handler("93.184.216.34")) # type: ignore[arg-type]
    try:
        response = await client.get("https://example.com/page")
    finally:
        await client.aclose()
    assert response.status == 200


async def test_operator_allow_private_endpoints_skip_the_peer_check() -> None:
    client = make_client(_peer_handler("127.0.0.1"), allow_private=True) # type: ignore[arg-type]
    try:
        response = await client.get("https://example.com/page")
    finally:
        await client.aclose()
    assert response.status == 200
