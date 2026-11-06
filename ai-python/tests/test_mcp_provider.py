from __future__ import annotations

import asyncio

import pytest

from docqa_ai.tools.mcp.client import McpProtocolError, McpToolDefinition
from docqa_ai.tools.mcp.provider import McpServer, McpToolProvider


class FakeListClient:
    def __init__(
        self,
        definitions: tuple[McpToolDefinition, ...] = (),
        error: Exception | None = None,
        delay: float = 0.0,
    ) -> None:
        self.definitions = definitions
        self.error = error
        self.delay = delay
        self.list_calls = 0
        self.closed = 0

    async def list_tools(self, timeout_s: float | None = None) -> tuple[McpToolDefinition, ...]:
        self.list_calls += 1
        if self.delay:
            await asyncio.sleep(self.delay)
        if self.error is not None:
            raise self.error
        return self.definitions

    async def call_tool(self, name: str, arguments: dict[str, object], timeout_s: float | None = None):
        raise AssertionError("provider tests never execute tools")

    async def aclose(self) -> None:
        self.closed += 1


def definition(name: str) -> McpToolDefinition:
    return McpToolDefinition(name=name, description=f"{name} tool", required_arguments=())


def names(tools) -> list[str]:
    return [tool.name for tool in tools]


async def test_allowlist_intersects_discovered_tools() -> None:
    client = FakeListClient((definition("search"), definition("translate")))
    provider = McpToolProvider([McpServer("dict", client, ("search",))])
    tools = await provider.tools()
    assert names(tools) == ["mcp__dict__search"]
    assert tools[0].source == "mcp"


async def test_server_with_empty_allowlist_is_never_contacted() -> None:
    client = FakeListClient((definition("search"),))
    provider = McpToolProvider([McpServer("dict", client, ())])
    assert await provider.tools() == ()
    assert client.list_calls == 0
    assert provider.up_states() == {"dict": False}


async def test_up_states_follow_the_last_discovery_outcome() -> None:
    client = FakeListClient((definition("search"),))
    provider = McpToolProvider([McpServer("dict", client, ("search",))], ttl_seconds=0)
    assert provider.up_states() == {"dict": False}

    await provider.tools()
    assert provider.up_states() == {"dict": True}

    client.error = McpProtocolError("discovery broke")
    await provider.tools(force=True)
    assert provider.up_states() == {"dict": False}


async def test_snapshot_is_reused_within_ttl_and_refreshed_when_stale() -> None:
    now = [100.0]
    client = FakeListClient((definition("search"),))
    provider = McpToolProvider([McpServer("dict", client, ("search",))], ttl_seconds=300, clock=lambda: now[0])

    await provider.tools()
    now[0] += 100
    await provider.tools()
    assert client.list_calls == 1

    now[0] += 400
    await provider.tools()
    assert client.list_calls == 2


async def test_force_refresh_bypasses_the_ttl() -> None:
    client = FakeListClient((definition("search"),))
    provider = McpToolProvider([McpServer("dict", client, ("search",))], ttl_seconds=300)
    await provider.tools()
    await provider.tools(force=True)
    assert client.list_calls == 2


async def test_failed_refresh_keeps_the_previous_snapshot_for_that_server() -> None:
    failing = FakeListClient((definition("search"),))
    healthy = FakeListClient((definition("translate"),))
    provider = McpToolProvider(
        [
            McpServer("first", failing, ("search",)),
            McpServer("second", healthy, ("translate",)),
        ],
        ttl_seconds=0,
    )
    await provider.tools()
    assert sorted(names(await provider.tools())) == ["mcp__first__search", "mcp__second__translate"]

    failing.error = McpProtocolError("discovery broke")
    healthy.definitions = (definition("translate"), definition("summarize"))
    assert sorted(names(await provider.tools(force=True))) == ["mcp__first__search", "mcp__second__translate"]


async def test_max_tools_caps_the_snapshot() -> None:
    first = FakeListClient((definition("a"), definition("b")))
    second = FakeListClient((definition("c"),))
    provider = McpToolProvider(
        [
            McpServer("one", first, ("a", "b")),
            McpServer("two", second, ("c",)),
        ],
        max_tools=2,
    )
    assert names(await provider.tools()) == ["mcp__one__a", "mcp__one__b"]


async def test_concurrent_callers_share_one_discovery() -> None:
    client = FakeListClient((definition("search"),), delay=0.01)
    provider = McpToolProvider([McpServer("dict", client, ("search",))])
    results = await asyncio.gather(provider.tools(), provider.tools(), provider.tools())
    assert all(names(tools) == ["mcp__dict__search"] for tools in results)
    assert client.list_calls == 1


async def test_aclose_closes_each_client_once() -> None:
    shared = FakeListClient()
    provider = McpToolProvider(
        [
            McpServer("one", shared, ()),
            McpServer("two", shared, ()),
        ]
    )
    await provider.aclose()
    assert shared.closed == 1


def test_negative_limits_are_rejected() -> None:
    with pytest.raises(ValueError):
        McpToolProvider([], ttl_seconds=-1)
    with pytest.raises(ValueError):
        McpToolProvider([], max_tools=-1)
