from __future__ import annotations

from collections.abc import Callable
from urllib.parse import unquote

import httpx
import pytest

from docqa_ai.tools.base import ToolError
from docqa_ai.tools.web.client import BoundedHttpClient
from docqa_ai.tools.wikipedia_lookup import WikipediaLookupTool
from tests.web_testkit import json_response, make_client, tool_context

Handler = Callable[[httpx.Request], httpx.Response]

SUMMARY = {
    "title": "线性代数",
    "extract": "线性代数是关于向量空间与线性映射的数学分支。",
    "description": "数学分支",
    "content_urls": {"desktop": {"page": "https://zh.wikipedia.org/wiki/线性代数"}},
}


def wiki_tool(handler: Handler, *, lang: str = "zh") -> tuple[WikipediaLookupTool, BoundedHttpClient]:
    client = make_client(handler)
    return WikipediaLookupTool(client, default_lang=lang), client


async def test_exact_summary_is_returned() -> None:
    requests: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        assert request.url.host == "zh.wikipedia.org"
        assert "/api/rest_v1/page/summary/" in request.url.path
        return json_response(SUMMARY)

    tool, client = wiki_tool(handler)
    try:
        result = await tool.run("线性代数", tool_context())
    finally:
        await client.aclose()
    assert "线性代数是关于向量空间与线性映射的数学分支" in result.observation
    assert "https://zh.wikipedia.org/wiki/线性代数" in result.observation
    assert result.observation.startswith('<untrusted source="wikipedia">')
    assert len(requests) == 1


async def test_404_falls_back_to_search_and_summaries() -> None:
    paths: list[str] = []

    def handler(request: httpx.Request) -> httpx.Response:
        paths.append(request.url.path)
        if request.url.path == "/w/api.php":
            return json_response({"query": {"search": [{"title": "数学"}, {"title": "矩阵"}]}})
        title = unquote(request.url.path.rsplit("/", 1)[-1])
        if title == "线性代数":
            return httpx.Response(404, text="missing")
        return json_response({"title": title, "extract": f"{title}的摘要", "content_urls": {}})

    tool, client = wiki_tool(handler)
    try:
        result = await tool.run("线性代数", tool_context())
    finally:
        await client.aclose()
    assert "数学的摘要" in result.observation
    assert "矩阵的摘要" in result.observation
    assert len(paths) == 4


async def test_search_without_matches_is_a_tool_error() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/w/api.php":
            return json_response({"query": {"search": []}})
        return httpx.Response(404, text="missing")

    tool, client = wiki_tool(handler)
    try:
        with pytest.raises(ToolError, match="no matching entry"):
            await tool.run("不存在的条目", tool_context())
    finally:
        await client.aclose()


async def test_server_error_is_a_tool_error() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(503, text="down")

    tool, client = wiki_tool(handler)
    try:
        with pytest.raises(ToolError, match="unavailable"):
            await tool.run("线性代数", tool_context())
    finally:
        await client.aclose()


async def test_unsupported_language_is_rejected() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        raise AssertionError("no request expected")

    tool, client = wiki_tool(handler)
    try:
        with pytest.raises(ToolError, match="not supported"):
            await tool.run('{"query": "linear algebra", "lang": "de"}', tool_context())
    finally:
        await client.aclose()


async def test_default_language_comes_from_configuration() -> None:
    hosts: list[str] = []

    def handler(request: httpx.Request) -> httpx.Response:
        hosts.append(request.url.host)
        return json_response(SUMMARY)

    tool, client = wiki_tool(handler, lang="en")
    try:
        await tool.run("linear algebra", tool_context())
    finally:
        await client.aclose()
    assert hosts == ["en.wikipedia.org"]


async def test_query_is_masked_before_egress() -> None:
    paths: list[str] = []

    def handler(request: httpx.Request) -> httpx.Response:
        paths.append(unquote(request.url.path))
        return json_response(SUMMARY)

    tool, client = wiki_tool(handler)
    try:
        result = await tool.run("student@example.com", tool_context())
    finally:
        await client.aclose()
    assert "[EMAIL]" in paths[0]
    assert "student@example.com" not in paths[0]
    assert result.masked_count == 1
    assert result.pii_categories == ("EMAIL",)


async def test_missing_extract_falls_back_to_search() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/w/api.php":
            return json_response({"query": {"search": [{"title": "矩阵"}]}})
        title = unquote(request.url.path.rsplit("/", 1)[-1])
        if title == "线性代数":
            return json_response({"title": "线性代数", "extract": ""})
        return json_response({"title": title, "extract": "矩阵摘要"})

    tool, client = wiki_tool(handler)
    try:
        result = await tool.run("线性代数", tool_context())
    finally:
        await client.aclose()
    assert "矩阵摘要" in result.observation
