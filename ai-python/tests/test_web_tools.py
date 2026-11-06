from __future__ import annotations

import logging

import pytest

from docqa_ai.api.main import build_default_service
from docqa_ai.governance.audit import new_event
from docqa_ai.protocol.models import AgentAskRequest
from docqa_ai.tools.web.toolset import build_web_toolset
from tests.conftest import make_request, make_settings

WEB_TOOL_NAMES = {"web_fetch", "web_search", "academic_search", "wikipedia_lookup"}


async def test_disabled_by_default_constructs_nothing() -> None:
    assert build_web_toolset(make_settings()) is None


async def test_enabled_without_search_backend_registers_three_tools() -> None:
    toolset = build_web_toolset(make_settings(real_tools_enabled=True))
    assert toolset is not None
    try:
        assert {tool.name for tool in toolset.tools} == {"web_fetch", "academic_search", "wikipedia_lookup"}
    finally:
        await toolset.aclose()


async def test_searxng_registers_the_search_tool() -> None:
    toolset = build_web_toolset(make_settings(real_tools_enabled=True, web_search_provider="searxng"))
    assert toolset is not None
    try:
        names = {tool.name for tool in toolset.tools}
    finally:
        await toolset.aclose()
    assert names == WEB_TOOL_NAMES


async def test_tavily_without_key_is_skipped_with_a_warning(
    monkeypatch: pytest.MonkeyPatch, caplog: pytest.LogCaptureFixture
) -> None:
    monkeypatch.delenv("TAVILY_API_KEY", raising=False)
    with caplog.at_level(logging.WARNING):
        toolset = build_web_toolset(make_settings(real_tools_enabled=True, web_search_provider="tavily"))
    assert toolset is not None
    try:
        names = {tool.name for tool in toolset.tools}
    finally:
        await toolset.aclose()
    assert "web_search" not in names
    assert "TAVILY_API_KEY" in caplog.text


async def test_tavily_with_key_registers_the_search_tool(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("TAVILY_API_KEY", "test-key")
    toolset = build_web_toolset(make_settings(real_tools_enabled=True, web_search_provider="tavily"))
    assert toolset is not None
    try:
        names = {tool.name for tool in toolset.tools}
    finally:
        await toolset.aclose()
    assert names == WEB_TOOL_NAMES


async def test_default_service_does_not_wire_web_tools_when_disabled() -> None:
    from opentelemetry import trace

    service, redis_client = build_default_service(make_settings(), trace.get_tracer("test"))
    try:
        assert service._web is None
        assert not WEB_TOOL_NAMES & set(service._tools.names())
    finally:
        await service.aclose()
        await redis_client.aclose()


async def test_default_service_wires_and_closes_web_tools_when_enabled() -> None:
    from opentelemetry import trace

    settings = make_settings(real_tools_enabled=True, web_search_provider="searxng")
    service, redis_client = build_default_service(settings, trace.get_tracer("test"))
    try:
        assert service._web is not None
        names = set(service._tools.names())
        assert WEB_TOOL_NAMES <= names
        client = service._web.client
        assert client._client.is_closed is False
        await service.aclose()
        assert client._client.is_closed is True
    finally:
        await service.aclose()
        await redis_client.aclose()


@pytest.mark.parametrize("name", sorted(WEB_TOOL_NAMES))
def test_new_builtin_names_pass_protocol_and_audit_validation(name: str) -> None:
    request: AgentAskRequest = make_request(allowedTools=[name])
    assert request.deduplicated_tools() == (name,)
    event = new_event("TOOL_CALLED", "agent_service", toolName=name, toolSource="builtin")
    assert event.payloadRedacted["toolName"] == name
