from __future__ import annotations

from collections.abc import Callable

import httpx
import pytest

from docqa_ai.tools.base import ToolError
from docqa_ai.tools.web.client import BoundedHttpClient
from docqa_ai.tools.web_search import WebSearchTool
from tests.web_testkit import json_response, make_client, tool_context

Handler = Callable[[httpx.Request], httpx.Response]

SEARXNG_JSON = {
    "results": [
        {"title": "线性代数讲义", "url": "https://math.example/linear", "content": "矩阵与线性映射。"},
        {"title": "概率论笔记", "url": "https://math.example/prob", "content": "条件概率与贝叶斯。"},
        {"title": "坏链接", "url": "javascript:alert(1)", "content": "应被丢弃"},
        {"title": "无链接", "content": "应被丢弃"},
    ]
}


def searxng_tool(handler: Handler, *, max_results: int = 5) -> tuple[WebSearchTool, BoundedHttpClient]:
    client = make_client(handler)
    tool = WebSearchTool(
        client,
        provider="searxng",
        searxng_url="http://127.0.0.1:8888/",
        max_results=max_results,
    )
    return tool, client


async def test_searxng_returns_titles_urls_and_summaries() -> None:
    requests: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        assert str(request.url).startswith("http://127.0.0.1:8888/search?")
        assert request.url.params["format"] == "json"
        return json_response(SEARXNG_JSON)

    tool, client = searxng_tool(handler)
    try:
        result = await tool.run("线性代数", tool_context())
    finally:
        await client.aclose()
    assert "线性代数讲义" in result.observation
    assert "https://math.example/linear" in result.observation
    assert "javascript" not in result.observation
    assert result.observation.startswith('<untrusted source="web_search">')
    assert len(requests) == 1


async def test_result_count_is_capped() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return json_response(SEARXNG_JSON)

    tool, client = searxng_tool(handler, max_results=1)
    try:
        result = await tool.run('{"query": "数学", "maxResults": 1}', tool_context())
    finally:
        await client.aclose()
    assert "线性代数讲义" in result.observation
    assert "概率论笔记" not in result.observation


async def test_zero_results_is_an_honest_message() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return json_response({"results": []})

    tool, client = searxng_tool(handler)
    try:
        result = await tool.run("不存在的主题", tool_context())
    finally:
        await client.aclose()
    assert "没有返回结果" in result.observation


async def test_searxng_failure_falls_back_to_tavily(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("TAVILY_API_KEY", "test-key")
    requests: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        if request.url.host == "127.0.0.1":
            return httpx.Response(500, text="searxng down")
        assert request.headers["authorization"] == "Bearer test-key"
        return json_response(
            {"results": [{"title": "Tavily 结果", "url": "https://tavily.example/1", "content": "备用摘要"}]}
        )

    tool, client = searxng_tool(handler)
    try:
        result = await tool.run("线性代数", tool_context())
    finally:
        await client.aclose()
    assert "Tavily 结果" in result.observation
    assert [request.url.host for request in requests] == ["127.0.0.1", "api.tavily.com"]
    assert requests[1].method == "POST"


async def test_both_backends_failing_is_a_tool_error(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("TAVILY_API_KEY", "test-key")

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(500, text="down")

    tool, client = searxng_tool(handler)
    try:
        with pytest.raises(ToolError, match="unavailable"):
            await tool.run("线性代数", tool_context())
    finally:
        await client.aclose()


async def test_searxng_failure_without_tavily_key_is_a_tool_error(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("TAVILY_API_KEY", raising=False)

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(500, text="down")

    tool, client = searxng_tool(handler)
    try:
        with pytest.raises(ToolError, match="unavailable"):
            await tool.run("线性代数", tool_context())
    finally:
        await client.aclose()


async def test_tavily_only_without_key_fails_closed(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("TAVILY_API_KEY", raising=False)

    def handler(request: httpx.Request) -> httpx.Response:
        raise AssertionError("must not call the network without a key")

    client = make_client(handler)
    tool = WebSearchTool(client, provider="tavily")
    try:
        with pytest.raises(ToolError, match="unavailable"):
            await tool.run("线性代数", tool_context())
    finally:
        await client.aclose()


async def test_non_json_searxng_payload_is_a_backend_failure() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, headers={"content-type": "text/html"}, text="<html>json disabled</html>")

    tool, client = searxng_tool(handler)
    try:
        with pytest.raises(ToolError, match="unavailable"):
            await tool.run("线性代数", tool_context())
    finally:
        await client.aclose()


async def test_query_is_masked_before_egress() -> None:
    requests: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        return json_response({"results": []})

    tool, client = searxng_tool(handler)
    try:
        result = await tool.run("找 student@example.com 的资料", tool_context())
    finally:
        await client.aclose()
    assert requests[0].url.params["q"] == "找 [EMAIL] 的资料"
    assert result.masked_count == 1
    assert result.pii_categories == ("EMAIL",)


async def test_unknown_input_field_is_rejected() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        raise AssertionError("no request expected")

    tool, client = searxng_tool(handler)
    try:
        with pytest.raises(ToolError, match="unknown field"):
            await tool.run('{"query": "x", "engine": "google"}', tool_context())
    finally:
        await client.aclose()
