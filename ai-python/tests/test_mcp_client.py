from __future__ import annotations

import json
from collections.abc import Callable

import httpx
import pytest

from docqa_ai.tools.mcp.client import (
    MCP_PROTOCOL_VERSION,
    McpHttpClient,
    McpProtocolError,
    McpTransportError,
)

URL = "http://127.0.0.1:8080/mcp"


class FakeServer:
    """Records JSON-RPC traffic and replies in-process; no socket is ever opened."""

    def __init__(
        self,
        *,
        tools: list[dict[str, object]] | None = None,
        call_result: dict[str, object] | None = None,
        session_id: str = "sess-1",
    ) -> None:
        self.tools = tools or []
        self.call_result = call_result or {"content": [{"type": "text", "text": "ok"}]}
        self.session_id = session_id
        self.requests: list[dict[str, object]] = []
        self.headers: list[httpx.Headers] = []

    def __call__(self, request: httpx.Request) -> httpx.Response:
        payload = json.loads(request.content)
        self.requests.append(payload)
        self.headers.append(request.headers)
        method = payload.get("method")
        if method == "initialize":
            return httpx.Response(
                200,
                json={
                    "jsonrpc": "2.0",
                    "id": payload["id"],
                    "result": {"protocolVersion": MCP_PROTOCOL_VERSION, "capabilities": {}, "serverInfo": {}},
                },
                headers={"Mcp-Session-Id": self.session_id},
            )
        if method == "notifications/initialized":
            return httpx.Response(202)
        if method == "tools/list":
            return httpx.Response(
                200,
                json={"jsonrpc": "2.0", "id": payload["id"], "result": {"tools": self.tools}},
            )
        if method == "tools/call":
            return httpx.Response(
                200,
                json={"jsonrpc": "2.0", "id": payload["id"], "result": self.call_result},
            )
        return httpx.Response(400)


def client(handler: Callable[[httpx.Request], httpx.Response], **overrides: object) -> McpHttpClient:
    options: dict[str, object] = {"transport": httpx.MockTransport(handler)}
    options.update(overrides)
    return McpHttpClient(URL, **options) # type: ignore[arg-type]


def methods(server: FakeServer) -> list[str]:
    return [str(payload.get("method")) for payload in server.requests]


async def test_list_tools_initializes_session_and_filters_invalid_definitions() -> None:
    server = FakeServer(
        tools=[
            {
                "name": "lookup",
                "description": "d" * 300,
                "inputSchema": {"type": "object", "required": ["q", 7, "limit"]},
            },
            {"name": "lookup", "description": "duplicate"},
            {"name": "Bad-Name", "description": "uppercase is not a valid mcp tool name"},
            {"name": "x" * 33, "description": "too long"},
            {"description": "missing name"},
        ]
    )
    mcp = client(server)
    try:
        definitions = await mcp.list_tools()
        assert [item.name for item in definitions] == ["lookup"]
        assert len(definitions[0].description) == 200
        assert definitions[0].required_arguments == ("q", "limit")
        assert methods(server) == ["initialize", "notifications/initialized", "tools/list"]
        assert server.requests[0]["params"]["protocolVersion"] == MCP_PROTOCOL_VERSION # type: ignore[index]
        assert "id" not in server.requests[1]
        assert server.headers[2]["Mcp-Session-Id"] == "sess-1"

        await mcp.list_tools()
        assert methods(server).count("initialize") == 1
        assert methods(server).count("tools/list") == 2
    finally:
        await mcp.aclose()


async def test_call_tool_joins_text_blocks_and_counts_dropped_content() -> None:
    server = FakeServer(
        call_result={
            "content": [
                {"type": "text", "text": "first"},
                {"type": "image", "data": "..."},
                {"type": "text", "text": "second"},
            ]
        }
    )
    mcp = client(server)
    try:
        result = await mcp.call_tool("lookup", {"q": "python"})
        assert result.text == "first\nsecond"
        assert result.dropped_blocks == 1
        call = server.requests[-1]
        assert call["method"] == "tools/call"
        assert call["params"] == {"name": "lookup", "arguments": {"q": "python"}} # type: ignore[index]
    finally:
        await mcp.aclose()


async def test_call_tool_maps_is_error_and_missing_text_to_protocol_errors() -> None:
    failing = FakeServer(call_result={"isError": True, "content": [{"type": "text", "text": "boom"}]})
    mcp = client(failing)
    try:
        with pytest.raises(McpProtocolError):
            await mcp.call_tool("lookup", {})
    finally:
        await mcp.aclose()

    empty = FakeServer(call_result={"content": [{"type": "image", "data": "..."}]})
    mcp = client(empty)
    try:
        with pytest.raises(McpProtocolError):
            await mcp.call_tool("lookup", {})
    finally:
        await mcp.aclose()


async def test_single_event_stream_response_is_decoded() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        payload = json.loads(request.content)
        if payload["method"] == "initialize":
            return httpx.Response(
                200,
                json={"jsonrpc": "2.0", "id": payload["id"], "result": {"protocolVersion": MCP_PROTOCOL_VERSION}},
            )
        if payload["method"] == "notifications/initialized":
            return httpx.Response(202)
        body = json.dumps({"jsonrpc": "2.0", "id": payload["id"], "result": {"tools": []}})
        return httpx.Response(
            200,
            content=f"event: message\ndata: {body}\n\n".encode(),
            headers={"content-type": "text/event-stream"},
        )

    mcp = client(handler)
    try:
        assert await mcp.list_tools() == ()
    finally:
        await mcp.aclose()


async def test_transport_and_protocol_failures_are_classified() -> None:
    def status(code: int) -> Callable[[httpx.Request], httpx.Response]:
        return lambda request: httpx.Response(code)

    for code in (302, 500):
        mcp = client(status(code))
        try:
            with pytest.raises(McpTransportError):
                await mcp.list_tools()
        finally:
            await mcp.aclose()

    mcp = client(lambda request: httpx.Response(200, content=b"not json"))
    try:
        with pytest.raises(McpProtocolError):
            await mcp.list_tools()
    finally:
        await mcp.aclose()


async def test_oversized_response_is_rejected() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        payload = json.loads(request.content)
        if payload["method"] == "initialize":
            return httpx.Response(
                200,
                json={"jsonrpc": "2.0", "id": payload["id"], "result": {"protocolVersion": MCP_PROTOCOL_VERSION}},
            )
        if payload["method"] == "notifications/initialized":
            return httpx.Response(202)
        return httpx.Response(
            200,
            json={
                "jsonrpc": "2.0",
                "id": payload["id"],
                "result": {"tools": [{"name": "lookup", "description": "d" * 500}]},
            },
        )

    mcp = client(handler, max_response_bytes=200)
    try:
        with pytest.raises(McpProtocolError):
            await mcp.list_tools()
    finally:
        await mcp.aclose()


async def test_mismatched_id_and_error_object_are_rejected_without_leaking_text() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        payload = json.loads(request.content)
        if payload["method"] == "initialize":
            return httpx.Response(
                200,
                json={"jsonrpc": "2.0", "id": payload["id"], "result": {"protocolVersion": MCP_PROTOCOL_VERSION}},
            )
        if payload["method"] == "notifications/initialized":
            return httpx.Response(202)
        if payload["method"] == "tools/list":
            return httpx.Response(200, json={"jsonrpc": "2.0", "id": payload["id"] + 1, "result": {}})
        return httpx.Response(
            200,
            json={"jsonrpc": "2.0", "id": payload["id"], "error": {"code": -32000, "message": "boom 13800138000"}},
        )

    mcp = client(handler)
    try:
        with pytest.raises(McpProtocolError):
            await mcp.list_tools()
        with pytest.raises(McpProtocolError) as error:
            await mcp.call_tool("lookup", {})
        assert "13800138000" not in str(error.value)
    finally:
        await mcp.aclose()


@pytest.mark.parametrize(
    "url",
    [
        "http://mcp.example.com/mcp",
        "ftp://127.0.0.1/mcp",
        "http://user:pass@127.0.0.1/mcp",
        "http:///mcp",
    ],
)
async def test_insecure_urls_are_rejected(url: str) -> None:
    with pytest.raises(ValueError):
        McpHttpClient(url)


async def test_https_loopback_and_plain_http_loopback_are_allowed() -> None:
    for url in ("https://mcp.example.com/mcp", "http://127.0.0.1:8080/mcp", "http://localhost/mcp"):
        mcp = McpHttpClient(url, transport=httpx.MockTransport(lambda request: httpx.Response(200)))
        await mcp.aclose()


async def test_timeout_maps_to_transport_error() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectTimeout("too slow", request=request)

    mcp = client(handler)
    try:
        with pytest.raises(McpTransportError):
            await mcp.list_tools()
    finally:
        await mcp.aclose()
