from __future__ import annotations

import asyncio
import logging
import time
from collections.abc import Callable, Iterable
from dataclasses import dataclass

from opentelemetry.trace import Tracer

from docqa_ai.tools.base import Tool
from docqa_ai.tools.mcp.client import McpClient, McpError
from docqa_ai.tools.mcp.tool import McpTool

LOGGER = logging.getLogger(__name__)


@dataclass(frozen=True)
class McpServer:
    """One configured HTTP MCP endpoint plus the tools this deployment is willing to run."""

    name: str
    client: McpClient
    allowed_tools: tuple[str, ...] = ()
    timeout_s: float = 3.0


class McpToolProvider:
    """Process-local tool discovery with TTL and per-server failure isolation (D-125).

    A failed refresh keeps the previous snapshot for that server; a server whose startup
    allowlist is empty never contributes tools, so it is never called at all.
    """

    def __init__(
        self,
        servers: Iterable[McpServer],
        *,
        ttl_seconds: float = 300.0,
        max_tools: int = 20,
        clock: Callable[[], float] = time.monotonic,
        tracer: Tracer | None = None,
    ) -> None:
        if ttl_seconds < 0:
            raise ValueError("mcp discovery ttl must not be negative")
        if max_tools < 0:
            raise ValueError("mcp max tools must not be negative")
        self._servers = tuple(servers)
        self._ttl = ttl_seconds
        self._max_tools = max_tools
        self._clock = clock
        self._tracer = tracer
        self._lock = asyncio.Lock()
        self._per_server: dict[str, tuple[Tool, ...]] = {}
        self._tools: tuple[Tool, ...] = ()
        self._up: dict[str, bool] = {server.name: False for server in self._servers}
        self._refreshed_at: float | None = None

    def up_states(self) -> dict[str, bool]:
        """Last discovery outcome per server; consumed by the `mcp_server_up` gauge."""

        return dict(self._up)

    async def tools(self, *, force: bool = False) -> tuple[Tool, ...]:
        if not self._servers:
            return ()
        async with self._lock:
            now = self._clock()
            fresh = self._refreshed_at is not None and now - self._refreshed_at < self._ttl
            if not force and fresh:
                return self._tools
            await self._refresh()
            self._refreshed_at = self._clock()
            return self._tools

    async def _refresh(self) -> None:
        for server in self._servers:
            if not server.allowed_tools:
                self._per_server[server.name] = ()
                continue
            try:
                definitions = await server.client.list_tools(timeout_s=server.timeout_s)
            except McpError as exc:
                self._up[server.name] = False
                self._record_failure(server.name, exc)
                continue
            self._up[server.name] = True
            allowed = set(server.allowed_tools)
            self._per_server[server.name] = tuple(
                McpTool(
                    server.name,
                    definition,
                    server.client,
                    timeout_s=server.timeout_s,
                    tracer=self._tracer,
                )
                for definition in definitions
                if definition.name in allowed
            )
        flat: list[Tool] = []
        seen: set[str] = set()
        for server in self._servers:
            for tool in self._per_server.get(server.name, ()):
                if tool.name in seen:
                    continue
                seen.add(tool.name)
                flat.append(tool)
        self._tools = tuple(flat[: self._max_tools])

    @staticmethod
    def _record_failure(server: str, error: McpError) -> None:
        LOGGER.warning(
            "MCP discovery failed, keeping the previous snapshot: server=%s error=%s",
            server,
            type(error).__name__,
        )

    async def aclose(self) -> None:
        closed: set[int] = set()
        for server in self._servers:
            if id(server.client) in closed:
                continue
            closed.add(id(server.client))
            await server.client.aclose()
