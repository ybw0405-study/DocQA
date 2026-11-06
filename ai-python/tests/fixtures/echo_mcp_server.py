"""Minimal stdio MCP server used by the SDK adapter tests.

Run as a script, never imported by the test process: the point is to exercise a
real subprocess handshake through the official SDK.
"""

from __future__ import annotations

from mcp.server.mcpserver import MCPServer

server = MCPServer("echo")


@server.tool()
def echo(word: str) -> str:
    """Return the word prefixed with echo:."""

    return f"echo:{word}"


if __name__ == "__main__":
    server.run("stdio")
