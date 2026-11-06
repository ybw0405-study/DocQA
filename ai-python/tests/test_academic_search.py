from __future__ import annotations

from collections.abc import Callable

import httpx
import pytest

from docqa_ai.tools.academic_search import AcademicSearchTool
from docqa_ai.tools.base import ToolError
from docqa_ai.tools.web.client import BoundedHttpClient
from tests.web_testkit import json_response, make_client, tool_context

Handler = Callable[[httpx.Request], httpx.Response]

OPENALEX_JSON = {
    "results": [
        {
            "title": "  Attention Is All You Need  ",
            "publication_year": 2017,
            "doi": "https://doi.org/10.1000/xyz",
            "authorships": [
                {"author": {"display_name": "Ashish Vaswani"}},
                {"author": {"display_name": "Noam Shazeer"}},
                {"author": {"display_name": "Niki Parmar"}},
            ],
            "abstract_inverted_index": {"Transformers": [0], "use": [1], "attention": [2]},
            "primary_location": {"landing_page_url": "https://example.org/paper"},
        },
        {
            "title": "",
            "publication_year": 2020,
        },
    ]
}

ARXIV_XML = """<?xml version="1.0" encoding="UTF-8"?>
<feed xmlns="http://www.w3.org/2005/Atom">
  <entry>
    <id>http://arxiv.org/abs/2105.00001v1</id>
    <title>Graph Neural Networks</title>
    <summary>A survey of graph neural networks.</summary>
    <published>2021-05-01T00:00:00Z</published>
    <author><name>Alice</name></author>
    <author><name>Bob</name></author>
  </entry>
</feed>"""


def academic_tool(handler: Handler, *, mailto: str = "") -> tuple[AcademicSearchTool, BoundedHttpClient]:
    client = make_client(handler)
    return AcademicSearchTool(client, mailto=mailto), client


def openalex_aware(arxiv_response: httpx.Response | None = None) -> tuple[Handler, list[httpx.Request]]:
    requests: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        if request.url.host == "api.openalex.org":
            return json_response(OPENALEX_JSON)
        if arxiv_response is not None:
            return arxiv_response
        return httpx.Response(200, text=ARXIV_XML, headers={"content-type": "application/atom+xml"})

    return handler, requests


async def test_openalex_metadata_and_inverted_abstract() -> None:
    handler, requests = openalex_aware()
    tool, client = academic_tool(handler, mailto="ops@example.com")
    try:
        result = await tool.run('{"query": "transformer", "source": "openalex", "yearFrom": 2015}', tool_context())
    finally:
        await client.aclose()
    assert "Attention Is All You Need" in result.observation
    assert "(2017)" in result.observation
    assert "Ashish Vaswani 等" in result.observation
    assert "Transformers use attention" in result.observation
    assert "https://doi.org/10.1000/xyz" in result.observation
    assert result.observation.startswith('<untrusted source="academic">')
    assert requests[0].url.params["mailto"] == "ops@example.com"
    assert requests[0].url.params["filter"] == "from_publication_date:2015-01-01"


async def test_arxiv_atom_is_parsed() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.host == "export.arxiv.org"
        return httpx.Response(200, text=ARXIV_XML, headers={"content-type": "application/atom+xml"})

    tool, client = academic_tool(handler)
    try:
        result = await tool.run('{"query": "graph", "source": "arxiv"}', tool_context())
    finally:
        await client.aclose()
    assert "Graph Neural Networks" in result.observation
    assert "(2021)" in result.observation
    assert "Alice, Bob" in result.observation
    assert "http://arxiv.org/abs/2105.00001v1" in result.observation


async def test_both_sources_are_merged_and_deduplicated() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.host == "api.openalex.org":
            return json_response({"results": [{"title": "Graph Neural Networks", "publication_year": 2021}]})
        return httpx.Response(200, text=ARXIV_XML, headers={"content-type": "application/atom+xml"})

    tool, client = academic_tool(handler)
    try:
        result = await tool.run('{"query": "graph"}', tool_context())
    finally:
        await client.aclose()
    assert result.observation.count("Graph Neural Networks") == 1


async def test_one_failing_source_returns_partial_results() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.host == "api.openalex.org":
            return httpx.Response(500, text="openalex down")
        return httpx.Response(200, text=ARXIV_XML, headers={"content-type": "application/atom+xml"})

    tool, client = academic_tool(handler)
    try:
        result = await tool.run('{"query": "graph"}', tool_context())
    finally:
        await client.aclose()
    assert "Graph Neural Networks" in result.observation
    assert "openalex 检索失败" in result.observation


async def test_both_sources_failing_is_a_tool_error() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(500, text="down")

    tool, client = academic_tool(handler)
    try:
        with pytest.raises(ToolError, match="unavailable"):
            await tool.run('{"query": "graph"}', tool_context())
    finally:
        await client.aclose()


async def test_no_results_returns_an_honest_message() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.host == "api.openalex.org":
            return json_response({"results": []})
        return httpx.Response(
            200,
            text='<feed xmlns="http://www.w3.org/2005/Atom"></feed>',
            headers={"content-type": "application/atom+xml"},
        )

    tool, client = academic_tool(handler)
    try:
        result = await tool.run('{"query": "nothing"}', tool_context())
    finally:
        await client.aclose()
    assert "未检索到相关论文" in result.observation


async def test_unsupported_source_is_rejected() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        raise AssertionError("no request expected")

    tool, client = academic_tool(handler)
    try:
        with pytest.raises(ToolError, match="not supported"):
            await tool.run('{"query": "graph", "source": "semantic"}', tool_context())
    finally:
        await client.aclose()


async def test_query_is_masked_before_egress() -> None:
    requests: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        return json_response({"results": []})

    tool, client = academic_tool(handler)
    try:
        result = await tool.run('{"query": "student@example.com", "source": "openalex"}', tool_context())
    finally:
        await client.aclose()
    assert requests[0].url.params["search"] == "[EMAIL]"
    assert result.masked_count == 1
    assert result.pii_categories == ("EMAIL",)


async def test_malformed_arxiv_atom_is_a_provider_failure() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, text="not xml at all <<<", headers={"content-type": "application/atom+xml"})

    tool, client = academic_tool(handler)
    try:
        with pytest.raises(ToolError, match="unavailable"):
            await tool.run('{"query": "graph", "source": "arxiv"}', tool_context())
    finally:
        await client.aclose()
