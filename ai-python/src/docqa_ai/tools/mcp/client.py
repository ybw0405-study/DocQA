from __future__ import annotations

import asyncio
import json
import re
from dataclasses import dataclass
from typing import Protocol

import httpx

# D-122：只实现 Streamable HTTP 的最小 JSON-RPC 2.0 客户端，不引入官方 mcp SDK，
# 也不支持 stdio（那等于把「配置即执行子进程」写进配置文件）。
MCP_PROTOCOL_VERSION = "2025-06-18"
CLIENT_INFO = {"name": "docqa-ai", "version": "1.0"}
_LOOPBACK_HOSTS = frozenset({"127.0.0.1", "localhost", "::1"})
_TOOL_NAME = re.compile(r"^[a-z0-9_]{1,32}$")
_MAX_DESCRIPTION = 200


class McpError(Exception):
    pass


class McpTransportError(McpError):
    pass


class McpProtocolError(McpError):
    pass


@dataclass(frozen=True)
class McpToolDefinition:
    name: str
    description: str
    required_arguments: tuple[str, ...]


@dataclass(frozen=True)
class McpCallResult:
    text: str
    dropped_blocks: int = 0


class McpClient(Protocol):
    async def list_tools(self, timeout_s: float | None = None) -> tuple[McpToolDefinition, ...]: ...

    async def call_tool(
        self,
        name: str,
        arguments: dict[str, object],
        timeout_s: float | None = None,
    ) -> McpCallResult: ...

    async def aclose(self) -> None: ...


def _is_loopback(host: str) -> bool:
    return host in _LOOPBACK_HOSTS or host.startswith("127.")


def _validate_url(url: str) -> str:
    """D-126：仅 http/https、禁止内嵌凭据、非 loopback 禁止明文。"""

    try:
        parsed = httpx.URL(url)
    except httpx.InvalidURL as exc:
        raise ValueError("mcp url is invalid") from exc
    if parsed.scheme not in {"http", "https"}:
        raise ValueError("mcp url must use http or https")
    if not parsed.host:
        raise ValueError("mcp url must include a host")
    if parsed.userinfo:
        raise ValueError("mcp url must not embed credentials")
    if parsed.scheme == "http" and not _is_loopback(parsed.host):
        raise ValueError("mcp url must use https unless the host is loopback")
    return str(parsed)


def _extract_sse_payload(text: str) -> str:
    """只取第一条事件的 data 行：D-122 明确不实现服务端推送流。"""

    data: list[str] = []
    for line in text.splitlines():
        if not line.strip():
            if data:
                break
            continue
        if line.startswith("data:"):
            data.append(line[5:].lstrip(" "))
    return "\n".join(data)


class McpHttpClient:
    """Minimal JSON-RPC 2.0 over Streamable HTTP. Tokens never reach logs."""

    def __init__(
        self,
        url: str,
        *,
        token: str = "",
        timeout_s: float = 3.0,
        max_response_bytes: int = 256 * 1024,
        transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        if timeout_s <= 0:
            raise ValueError("mcp timeout must be positive")
        if max_response_bytes <= 0:
            raise ValueError("mcp max response bytes must be positive")
        self._url = _validate_url(url)
        self._token = token
        self._timeout_s = timeout_s
        self._max_response_bytes = max_response_bytes
        self._client = httpx.AsyncClient(transport=transport, follow_redirects=False)
        self._session_id = ""
        self._protocol_version = ""
        self._initialized = False
        self._init_lock = asyncio.Lock()
        self._request_id = 0

    async def aclose(self) -> None:
        await self._client.aclose()

    async def list_tools(self, timeout_s: float | None = None) -> tuple[McpToolDefinition, ...]:
        timeout = self._resolve_timeout(timeout_s)
        await self._ensure_initialized(timeout)
        result = await self._request("tools/list", {}, timeout)
        raw_tools = result.get("tools")
        if not isinstance(raw_tools, list):
            raise McpProtocolError("mcp tools/list result is malformed")
        definitions: list[McpToolDefinition] = []
        seen: set[str] = set()
        for item in raw_tools:
            definition = self._parse_definition(item)
            if definition is None or definition.name in seen:
                continue
            seen.add(definition.name)
            definitions.append(definition)
        return tuple(definitions)

    async def call_tool(
        self,
        name: str,
        arguments: dict[str, object],
        timeout_s: float | None = None,
    ) -> McpCallResult:
        if not _TOOL_NAME.fullmatch(name):
            raise McpProtocolError("mcp tool name is invalid")
        timeout = self._resolve_timeout(timeout_s)
        await self._ensure_initialized(timeout)
        result = await self._request("tools/call", {"name": name, "arguments": arguments}, timeout)
        if result.get("isError") is True:
            raise McpProtocolError("mcp tool reported an error")
        content = result.get("content")
        if not isinstance(content, list):
            raise McpProtocolError("mcp tools/call result is malformed")
        texts: list[str] = []
        dropped = 0
        for item in content:
            if isinstance(item, dict) and item.get("type") == "text" and isinstance(item.get("text"), str):
                texts.append(item["text"])
            else:
                dropped += 1
        if not texts:
            raise McpProtocolError("mcp tool returned no text content")
        return McpCallResult(text="\n".join(texts), dropped_blocks=dropped)

    def _resolve_timeout(self, timeout_s: float | None) -> float:
        value = self._timeout_s if timeout_s is None else timeout_s
        if value <= 0:
            raise McpTransportError("mcp call has no remaining time")
        return value

    async def _ensure_initialized(self, timeout_s: float) -> None:
        if self._initialized:
            return
        async with self._init_lock:
            if self._initialized:
                return
            result = await self._request(
                "initialize",
                {
                    "protocolVersion": MCP_PROTOCOL_VERSION,
                    "capabilities": {},
                    "clientInfo": CLIENT_INFO,
                },
                timeout_s,
            )
            version = result.get("protocolVersion")
            self._protocol_version = version if isinstance(version, str) else ""
            await self._notify("notifications/initialized", timeout_s)
            self._initialized = True

    async def _notify(self, method: str, timeout_s: float) -> None:
        await self._post({"jsonrpc": "2.0", "method": method}, timeout_s)

    async def _request(self, method: str, params: dict[str, object], timeout_s: float) -> dict[str, object]:
        self._request_id += 1
        request_id = self._request_id
        body = await self._post(
            {"jsonrpc": "2.0", "id": request_id, "method": method, "params": params},
            timeout_s,
        )
        message = self._decode(body)
        if not isinstance(message, dict):
            raise McpProtocolError("mcp response is not a JSON object")
        if message.get("error") is not None:
            # 不把第三方 message 文本带出来：它可能回显入参（含学生问题）。
            raise McpProtocolError("mcp server returned an error")
        if message.get("id") != request_id:
            raise McpProtocolError("mcp response id does not match the request")
        result = message.get("result")
        if not isinstance(result, dict):
            raise McpProtocolError("mcp response result is malformed")
        return result

    async def _post(self, payload: dict[str, object], timeout_s: float) -> bytes:
        headers = {
            "Content-Type": "application/json",
            "Accept": "application/json, text/event-stream",
            "MCP-Protocol-Version": MCP_PROTOCOL_VERSION,
        }
        if self._token:
            headers["Authorization"] = f"Bearer {self._token}"
        if self._session_id:
            headers["Mcp-Session-Id"] = self._session_id
        timeout = httpx.Timeout(max(0.05, timeout_s))
        try:
            async with self._client.stream(
                "POST",
                self._url,
                json=payload,
                headers=headers,
                timeout=timeout,
            ) as response:
                if 300 <= response.status_code < 400:
                    raise McpTransportError("mcp endpoint redirected")
                if response.status_code >= 400:
                    raise McpTransportError(f"mcp endpoint returned {response.status_code}")
                session = response.headers.get("mcp-session-id")
                if session and not self._session_id:
                    self._session_id = session
                return await self._read_limited(response)
        except httpx.TimeoutException as exc:
            raise McpTransportError("mcp request timed out") from exc
        except httpx.HTTPError as exc:
            raise McpTransportError("mcp request failed") from exc

    async def _read_limited(self, response: httpx.Response) -> bytes:
        body = bytearray()
        async for chunk in response.aiter_bytes():
            body.extend(chunk)
            if len(body) > self._max_response_bytes:
                raise McpProtocolError("mcp response exceeds the size limit")
        return bytes(body)

    @staticmethod
    def _decode(body: bytes) -> object:
        if not body.strip():
            return None
        try:
            text = body.decode("utf-8")
        except UnicodeDecodeError as exc:
            raise McpProtocolError("mcp response is not valid UTF-8") from exc
        if text.lstrip().startswith(("event:", "data:")):
            text = _extract_sse_payload(text)
            if not text:
                raise McpProtocolError("mcp event stream carried no data")
        try:
            return json.loads(text)
        except json.JSONDecodeError as exc:
            raise McpProtocolError("mcp response is not valid JSON") from exc

    @staticmethod
    def _parse_definition(item: object) -> McpToolDefinition | None:
        if not isinstance(item, dict):
            return None
        name = item.get("name")
        if not isinstance(name, str) or not _TOOL_NAME.fullmatch(name):
            return None
        description = item.get("description")
        if not isinstance(description, str):
            description = ""
        schema = item.get("inputSchema")
        required: tuple[str, ...] = ()
        if isinstance(schema, dict):
            raw_required = schema.get("required")
            if isinstance(raw_required, list):
                required = tuple(value for value in raw_required if isinstance(value, str))[:20]
        return McpToolDefinition(
            name=name,
            description=description[:_MAX_DESCRIPTION],
            required_arguments=required,
        )
