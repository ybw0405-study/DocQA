from __future__ import annotations

import asyncio
import concurrent.futures
import io
import logging
import os
import re
import sys
import threading
import time
from collections.abc import AsyncIterator, Callable
from contextlib import asynccontextmanager, suppress
from dataclasses import dataclass
from typing import Any

from mcp import ClientSession
from mcp.client.stdio import StdioServerParameters, stdio_client

from docqa_ai.tools.mcp.client import (
    McpCallResult,
    McpError,
    McpProtocolError,
    McpToolDefinition,
    McpTransportError,
)

LOGGER = logging.getLogger(__name__)

_MAX_STDERR_CHARS = 8192
_MAX_REQUIRED_ARGUMENTS = 20
_MAX_ARGS = 32
_MAX_ARG_CHARS = 4096
_TOOL_NAME = re.compile(r"^[a-z0-9_]{1,32}$")
_ENV_NAME = re.compile(r"^[A-Z][A-Z0-9_]{0,63}$")
_WINDOWS_NATIVE_SUFFIXES = frozenset({".exe"})

# D-140：子进程只继承运行时必需的系统变量；业务密钥必须显式列入 Server 配置。
_BASE_ENV = (
    "PATH",
    "PATHEXT",
    "SYSTEMROOT",
    "SYSTEMDRIVE",
    "COMSPEC",
    "TEMP",
    "TMP",
    "USERPROFILE",
    "HOMEDRIVE",
    "HOMEPATH",
    "APPDATA",
    "LOCALAPPDATA",
)


@dataclass(frozen=True)
class McpStdioConfig:
    """One locally provisioned MCP server. The command must be an absolute path."""

    command: str
    args: tuple[str, ...] = ()
    env_names: tuple[str, ...] = ()
    cwd: str | None = None


class _BoundedLog(io.TextIOBase):
    """Captures child stderr through a pipe without letting a noisy server grow memory.

    The subprocess needs a real file descriptor on Windows, so a pipe is used and
    a reader thread keeps only the counters. Log content never enters this process.
    """

    def __init__(self, limit: int = _MAX_STDERR_CHARS) -> None:
        self._limit = limit
        self.chars = 0
        self.truncated = False
        self._read_fd, write_fd = os.pipe()
        self._sink = os.fdopen(write_fd, "wb", buffering=0)
        self._thread = threading.Thread(target=self._drain, name="mcp-stderr", daemon=True)
        self._thread.start()

    def fileno(self) -> int:
        return self._sink.fileno()

    def writable(self) -> bool:
        return True

    def write(self, text: str) -> int:  # type: ignore[override]
        data = (text if isinstance(text, str) else str(text)).encode("utf-8", "replace")
        return self._sink.write(data)

    def flush(self) -> None:
        with suppress(ValueError):
            self._sink.flush()

    def _drain(self) -> None:
        while True:
            try:
                chunk = os.read(self._read_fd, 4096)
            except OSError:
                return
            if not chunk:
                return
            self.chars += len(chunk)
            if self.chars > self._limit:
                self.truncated = True

    def close(self) -> None:
        with suppress(ValueError):
            self._sink.close()
        self._thread.join(timeout=1.0)
        with suppress(OSError):
            os.close(self._read_fd)
        super().close()


def _validate_stdio(config: McpStdioConfig) -> None:
    if not config.command or "\x00" in config.command:
        raise ValueError("mcp stdio command is invalid")
    if not os.path.isabs(config.command):
        raise ValueError("mcp stdio command must be an absolute path")
    if not os.path.isfile(config.command):
        raise ValueError("mcp stdio command does not exist")
    if sys.platform == "win32" and os.path.splitext(config.command)[1].lower() not in _WINDOWS_NATIVE_SUFFIXES:
        # .cmd / .bat 会被 Windows 隐式交给 shell 执行，npx 这类分发器正是这种形态。
        raise ValueError("mcp stdio command must be a native executable")
    if len(config.args) > _MAX_ARGS:
        raise ValueError("mcp stdio has too many arguments")
    if any("\x00" in arg or len(arg) > _MAX_ARG_CHARS for arg in config.args):
        raise ValueError("mcp stdio argument is invalid")
    if config.cwd is not None:
        if not os.path.isabs(config.cwd) or not os.path.isdir(config.cwd):
            raise ValueError("mcp stdio cwd must be an existing absolute directory")


def _validate_env_names(names: tuple[str, ...]) -> None:
    for name in names:
        if not _ENV_NAME.fullmatch(name):
            raise ValueError("mcp env name is invalid")
        if name not in os.environ:
            # 显式列出的变量缺席是部署错误，静默省略会让 Server 带着空令牌启动。
            raise ValueError("mcp env variable is not set")


def _build_env(names: tuple[str, ...]) -> dict[str, str]:
    env = {key: os.environ[key] for key in _BASE_ENV if key in os.environ}
    for name in names:
        env[name] = os.environ[name]
    return env


def _field(item: object, snake: str, camel: str) -> Any:
    """SDK 2.x models expose snake_case; the wire names stay camelCase."""
    value = getattr(item, snake, None)
    return getattr(item, camel, None) if value is None else value


def _map_tools(result: object) -> tuple[McpToolDefinition, ...]:
    raw_tools = getattr(result, "tools", None)
    if not isinstance(raw_tools, list):
        raise McpProtocolError("mcp tools/list result is malformed")
    definitions: list[McpToolDefinition] = []
    seen: set[str] = set()
    for item in raw_tools:
        name = getattr(item, "name", None)
        if not isinstance(name, str) or not _TOOL_NAME.fullmatch(name) or name in seen:
            continue
        seen.add(name)
        description = getattr(item, "description", "")
        if not isinstance(description, str):
            description = ""
        schema = _field(item, "input_schema", "inputSchema")
        required: tuple[str, ...] = ()
        if isinstance(schema, dict):
            raw_required = schema.get("required")
            if isinstance(raw_required, list):
                required = tuple(value for value in raw_required if isinstance(value, str))[:_MAX_REQUIRED_ARGUMENTS]
        definitions.append(
            McpToolDefinition(
                name=name,
                description=description,
                required_arguments=required,
            )
        )
    return tuple(definitions)


def _map_call(result: object) -> McpCallResult:
    if _field(result, "is_error", "isError") is True:
        raise McpProtocolError("mcp tool reported an error")
    content = getattr(result, "content", None)
    if not isinstance(content, list):
        raise McpProtocolError("mcp tools/call result is malformed")
    texts: list[str] = []
    dropped = 0
    for item in content:
        if getattr(item, "type", None) == "text" and isinstance(getattr(item, "text", None), str):
            texts.append(item.text)
        else:
            dropped += 1
    if not texts:
        raise McpProtocolError("mcp tool returned no text content")
    return McpCallResult(text="\n".join(texts), dropped_blocks=dropped)


class _SessionHost:
    """Owns the official SDK transport on a dedicated event loop thread.

    The SDK's stdio transport spawns a subprocess, and Windows' Selector loop
    (which the API entrypoint uses for psycopg) cannot spawn subprocesses. A
    dedicated Proactor thread keeps the transport independent of the app loop
    and gives the supervision code one place to start, stop and restart it.
    """

    def __init__(self, owner: SdkMcpClient) -> None:
        self._owner = owner
        self.thread = threading.Thread(target=self._main, name=f"mcp-{owner.name}", daemon=True)
        self.loop: asyncio.AbstractEventLoop | None = None
        self.session: ClientSession | None = None
        self.ready: concurrent.futures.Future[None] = concurrent.futures.Future()
        self.stop: asyncio.Event | None = None

    def start(self) -> None:
        self.thread.start()

    def _main(self) -> None:
        loop: asyncio.AbstractEventLoop | None = None
        try:
            loop = asyncio.ProactorEventLoop() if sys.platform == "win32" else asyncio.new_event_loop()
            asyncio.set_event_loop(loop)
            self.loop = loop
            loop.run_until_complete(self._run())
        except BaseException as exc:  # noqa: BLE001 - the thread must never die silently
            self._fail(exc)
        finally:
            if loop is not None:
                with suppress(Exception):
                    loop.run_until_complete(loop.shutdown_asyncgens())
                loop.close()
            with suppress(Exception):
                asyncio.set_event_loop(None)
            self.loop = None
            self.session = None

    async def _run(self) -> None:
        stop = asyncio.Event()
        self.stop = stop
        owner = self._owner
        try:
            async with owner._open() as (read_stream, write_stream):
                async with ClientSession(
                    read_stream,
                    write_stream,
                    read_timeout_seconds=owner._timeout_s,
                ) as session:
                    await session.initialize()
                    self.session = session
                    self._resolve()
                    await stop.wait()
        except asyncio.CancelledError:
            raise
        except Exception as exc:  # noqa: BLE001 - any transport failure ends this session
            self._fail(exc)
            LOGGER.warning(
                "MCP stdio session ended: server=%s error=%s",
                owner.name,
                type(exc).__name__,
            )
        finally:
            self.session = None
            self._fail(McpTransportError("mcp session closed"))

    def _resolve(self) -> None:
        if not self.ready.done():
            try:
                self.ready.set_result(None)
            except concurrent.futures.InvalidStateError:
                return

    def _fail(self, exc: BaseException) -> None:
        if self.ready.done():
            return
        error = exc if isinstance(exc, Exception) else McpTransportError("mcp session was cancelled")
        try:
            self.ready.set_exception(error)
        except concurrent.futures.InvalidStateError:
            return

    def request_stop(self) -> None:
        loop = self.loop
        stop = self.stop
        if loop is None or stop is None:
            return
        with suppress(RuntimeError):
            loop.call_soon_threadsafe(stop.set)

    def join(self, timeout: float) -> bool:
        if self.thread.is_alive():
            self.thread.join(timeout)
        return not self.thread.is_alive()


class SdkMcpClient:
    """Long-lived official-SDK session with supervision, timeout and circuit breaking (D-132/D-140).

    Requests run on the host loop; the application loop only waits on futures.
    A failed session is discarded, and a new one starts on the next call until
    the circuit opens, so one dead server cannot take the API down.
    """

    def __init__(
        self,
        name: str,
        config: McpStdioConfig,
        *,
        timeout_s: float = 3.0,
        startup_timeout_s: float = 5.0,
        max_concurrency: int = 4,
        failure_threshold: int = 3,
        circuit_open_s: float = 60.0,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        if timeout_s <= 0:
            raise ValueError("mcp timeout must be positive")
        if startup_timeout_s <= 0:
            raise ValueError("mcp startup timeout must be positive")
        if max_concurrency <= 0:
            raise ValueError("mcp concurrency must be positive")
        if failure_threshold <= 0:
            raise ValueError("mcp failure threshold must be positive")
        if circuit_open_s <= 0:
            raise ValueError("mcp circuit window must be positive")
        if not _TOOL_NAME.fullmatch(name):
            raise ValueError("mcp server name is invalid")
        _validate_stdio(config)
        _validate_env_names(config.env_names)
        self.name = name
        self._config = config
        self._timeout_s = timeout_s
        self._startup_timeout_s = startup_timeout_s
        self._failure_threshold = failure_threshold
        self._circuit_open_s = circuit_open_s
        self._clock = clock
        self._semaphore = asyncio.Semaphore(max_concurrency)
        self._lock = asyncio.Lock()
        self._host: _SessionHost | None = None
        self._failures = 0
        self._open_until = 0.0
        self._closed = False

    async def list_tools(self, timeout_s: float | None = None) -> tuple[McpToolDefinition, ...]:
        timeout = self._resolve_timeout(timeout_s)
        async with self._semaphore:
            session = await self._ensure_session()
            result = await self._call_session(session, "list", "", {}, timeout)
        return _map_tools(result)

    async def call_tool(
        self,
        name: str,
        arguments: dict[str, object],
        timeout_s: float | None = None,
    ) -> McpCallResult:
        if not _TOOL_NAME.fullmatch(name):
            raise McpProtocolError("mcp tool name is invalid")
        timeout = self._resolve_timeout(timeout_s)
        async with self._semaphore:
            session = await self._ensure_session()
            result = await self._call_session(session, "call", name, dict(arguments), timeout)
        return _map_call(result)

    async def aclose(self) -> None:
        if self._closed:
            return
        self._closed = True
        async with self._lock:
            await self._stop_host()

    def _resolve_timeout(self, timeout_s: float | None) -> float:
        value = self._timeout_s if timeout_s is None else timeout_s
        if value <= 0:
            raise McpTransportError("mcp call has no remaining time")
        return value

    async def _ensure_session(self) -> ClientSession:
        if self._closed:
            raise McpTransportError("mcp client is closed")
        session = self._healthy_session()
        if session is not None:
            return session
        async with self._lock:
            if self._closed:
                raise McpTransportError("mcp client is closed")
            session = self._healthy_session()
            if session is not None:
                return session
            if self._clock() < self._open_until:
                # 熔断期内不再尝试拉起；顺手回收失败会话，避免子进程留到 aclose 才退出。
                await self._stop_host()
                raise McpTransportError("mcp circuit is open")
            await self._stop_host()
            host = _SessionHost(self)
            self._host = host
            host.start()
            try:
                await asyncio.wait_for(asyncio.wrap_future(host.ready), timeout=self._startup_timeout_s)
            except TimeoutError as exc:
                self._record_failure()
                await self._stop_host()
                raise McpTransportError("mcp server did not start in time") from exc
            except Exception as exc:
                self._record_failure()
                await self._stop_host()
                if isinstance(exc, McpError):
                    raise
                raise McpTransportError("mcp server failed to start") from exc
            session = host.session
            if session is None:
                self._record_failure()
                await self._stop_host()
                raise McpTransportError("mcp server is not ready")
            self._failures = 0
            return session

    def _healthy_session(self) -> ClientSession | None:
        host = self._host
        if host is None or not host.thread.is_alive() or not host.ready.done():
            return None
        if host.ready.cancelled() or host.ready.exception() is not None:
            return None
        return host.session

    async def _call_session(
        self,
        session: ClientSession,
        method: str,
        name: str,
        arguments: dict[str, object],
        timeout_s: float,
    ) -> Any:
        host = self._host
        loop = host.loop if host is not None else None
        if loop is None:
            self._record_failure()
            raise McpTransportError("mcp server is not ready")
        if method == "list":
            coroutine = session.list_tools()
        else:
            coroutine = session.call_tool(name, arguments, read_timeout_seconds=timeout_s)
        future = asyncio.run_coroutine_threadsafe(coroutine, loop)
        try:
            result = await asyncio.wait_for(asyncio.wrap_future(future), timeout=timeout_s + 0.05)
        except TimeoutError as exc:
            future.cancel()
            self._record_failure()
            await self._stop_host()
            raise McpTransportError("mcp request timed out") from exc
        except McpError:
            # 协议层错误在映射阶段产生；这里只可能是会话/传输错误，连接状态不再可信。
            self._record_failure()
            await self._stop_host()
            raise
        except Exception as exc:
            # 连接中途断掉时，SDK 的异常类型不稳定；统一映射为可重试的传输错误。
            self._record_failure()
            raise McpTransportError("mcp request failed") from exc
        self._failures = 0
        return result

    async def _stop_host(self) -> None:
        host = self._host
        self._host = None
        if host is None:
            return
        host.request_stop()
        stopped = await asyncio.to_thread(host.join, 5.0)
        if not stopped:
            LOGGER.warning("MCP stdio host did not stop in time: server=%s", self.name)

    def _record_failure(self) -> None:
        self._failures += 1
        if self._failures >= self._failure_threshold:
            self._open_until = self._clock() + self._circuit_open_s

    @asynccontextmanager
    async def _open(self) -> AsyncIterator[tuple[Any, Any]]:
        config = self._config
        errlog = _BoundedLog()
        params = StdioServerParameters(
            command=config.command,
            args=list(config.args),
            env=_build_env(config.env_names),
            cwd=config.cwd,
        )
        try:
            async with stdio_client(params, errlog=errlog) as (read_stream, write_stream):
                yield read_stream, write_stream
        finally:
            try:
                if errlog.chars:
                    LOGGER.warning(
                        "MCP stdio server wrote to stderr: server=%s chars=%s truncated=%s",
                        self.name,
                        errlog.chars,
                        errlog.truncated,
                    )
            finally:
                errlog.close()
