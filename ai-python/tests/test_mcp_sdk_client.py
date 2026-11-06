from __future__ import annotations

import asyncio
import sys
from pathlib import Path

import mcp
import pytest

from docqa_ai.tools.mcp import (
    McpProtocolError,
    McpStdioConfig,
    McpTransportError,
    SdkMcpClient,
)

FIXTURE = Path(__file__).parent / "fixtures" / "echo_mcp_server.py"


def venv_python() -> Path:
    """The interpreter that actually has the mcp SDK installed (not the test runner)."""

    for parent in Path(mcp.__file__).resolve().parents:
        candidate = parent / ("Scripts/python.exe" if sys.platform == "win32" else "bin/python")
        if candidate.is_file():
            return candidate
    raise AssertionError("could not locate the virtualenv interpreter that owns mcp")


def echo_config() -> McpStdioConfig:
    return McpStdioConfig(
        command=str(venv_python()),
        args=(str(FIXTURE),),
        cwd=str(FIXTURE.parent),
    )


def client_for(config: McpStdioConfig, *, name: str = "echo", **overrides: object) -> SdkMcpClient:
    options: dict[str, object] = {"timeout_s": 10.0, "startup_timeout_s": 30.0}
    options.update(overrides)
    return SdkMcpClient(name, config, **options)


async def test_stdio_session_lists_and_calls_a_real_subprocess() -> None:
    client = client_for(echo_config())
    try:
        definitions = await client.list_tools()
        assert [definition.name for definition in definitions] == ["echo"]
        assert definitions[0].required_arguments == ("word",)

        result = await client.call_tool("echo", {"word": "hi"})
        assert result.text == "echo:hi"
        assert result.dropped_blocks == 0
    finally:
        await client.aclose()


async def test_concurrent_calls_share_one_session() -> None:
    client = client_for(echo_config(), max_concurrency=4)
    try:
        results = await asyncio.gather(*(client.call_tool("echo", {"word": str(index)}) for index in range(4)))
        assert [result.text for result in results] == [f"echo:{index}" for index in range(4)]
    finally:
        await client.aclose()


async def test_close_is_idempotent_and_rejects_later_calls() -> None:
    client = client_for(echo_config())
    await client.aclose()
    await client.aclose()
    with pytest.raises(McpTransportError, match="closed"):
        await client.list_tools()


def test_relative_command_is_rejected() -> None:
    with pytest.raises(ValueError, match="absolute"):
        SdkMcpClient("echo", McpStdioConfig(command="python", args=("-c", "pass")))


def test_unset_env_name_is_rejected(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("DOCQA_TEST_MCP_TOKEN", raising=False)
    with pytest.raises(ValueError, match="not set"):
        SdkMcpClient(
            "echo",
            McpStdioConfig(command=str(venv_python()), env_names=("DOCQA_TEST_MCP_TOKEN",)),
        )


async def test_invalid_tool_name_is_rejected_without_spawning() -> None:
    client = client_for(echo_config())
    try:
        with pytest.raises(McpProtocolError, match="tool name"):
            await client.call_tool("Bad-Name", {})
    finally:
        await client.aclose()


async def test_circuit_opens_after_repeated_startup_failure() -> None:
    config = McpStdioConfig(command=str(venv_python()), args=("-c", "raise SystemExit(2)"))
    client = SdkMcpClient(
        "bad",
        config,
        timeout_s=0.5,
        startup_timeout_s=5.0,
        failure_threshold=2,
    )
    try:
        for _ in range(2):
            with pytest.raises(McpTransportError, match="mcp server"):
                await client.list_tools()
        with pytest.raises(McpTransportError, match="circuit is open"):
            await client.list_tools()
    finally:
        await client.aclose()
