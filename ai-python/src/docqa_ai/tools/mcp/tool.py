from __future__ import annotations

import json
import logging
import re
import time

from opentelemetry import trace
from opentelemetry.trace import Tracer

from docqa_ai.agent.errors import DeadlineExceeded
from docqa_ai.pii.masker import mask_text
from docqa_ai.tools.base import ToolContext, ToolError, ToolResult
from docqa_ai.tools.mcp.client import McpCallResult, McpClient, McpError, McpToolDefinition
from docqa_ai.tools.observation import MIN_WRAPPED_LIMIT, fit_observation

LOGGER = logging.getLogger(__name__)

_MAX_ARGUMENT = 2000
_MAX_OUTPUT_CHARS = 1500
_MAX_DESCRIPTION = 200
_MAX_HINT = 120
_SERVER_NAME = re.compile(r"^[a-z0-9_]{1,32}$")


def _parse_arguments(argument: str) -> dict[str, object]:
    """D-126：入参统一为 JSON 对象字符串；空串等价于 `{}`。"""

    raw = argument.strip()
    if not raw:
        return {}
    if len(raw) > _MAX_ARGUMENT:
        raise ToolError("mcp tool input is too long")
    try:
        parsed = json.loads(raw)
    except json.JSONDecodeError as exc:
        raise ToolError("mcp tool input must be a JSON object") from exc
    if not isinstance(parsed, dict):
        raise ToolError("mcp tool input must be a JSON object")
    return parsed


class McpTool:
    """Adapts one discovered MCP tool to the local Tool protocol.

    Third-party text (description, output) is untrusted: it is escaped upstream in the prompt,
    masked before truncation here, and never used to build identifiers.
    """

    source = "mcp"

    def __init__(
        self,
        server: str,
        definition: McpToolDefinition,
        client: McpClient,
        *,
        timeout_s: float = 3.0,
        max_output_chars: int = _MAX_OUTPUT_CHARS,
        tracer: Tracer | None = None,
    ) -> None:
        if not _SERVER_NAME.fullmatch(server):
            raise ValueError("mcp server name is invalid")
        self.name = f"mcp__{server}__{definition.name}"
        self.description = definition.description[:_MAX_DESCRIPTION]
        if definition.required_arguments:
            hint = "JSON 对象参数，必需键: " + ", ".join(definition.required_arguments)
        else:
            hint = "JSON 对象参数，无必需键"
        self.input_hint = hint[:_MAX_HINT]
        self._server = server
        self._definition = definition
        self._client = client
        self._timeout_s = timeout_s
        # 低于 MIN_WRAPPED_LIMIT 时包裹标签与截断提示自己就会超长，因此从结构上抬高下限。
        self._max_output_chars = max(MIN_WRAPPED_LIMIT, max_output_chars)
        self._tracer = tracer or trace.get_tracer("docqa_ai")

    async def run(self, argument: str, context: ToolContext) -> ToolResult:
        arguments = _parse_arguments(argument)
        # D-131：出站前先掩码，与 document_search 同序；掩码后必须是合法 JSON 对象，否则不外呼。
        input_gate = mask_text(argument)
        if input_gate.count:
            LOGGER.warning(
                "MCP tool input was masked before egress: tool=%s count=%s",
                self.name,
                input_gate.count,
            )
            arguments = _parse_arguments(input_gate.masked)
        remaining = context.deadline.remaining_seconds()
        if remaining <= 0:
            raise DeadlineExceeded("deadline expired before mcp tool execution")
        started = time.perf_counter()
        outcome = "success"
        with self._tracer.start_as_current_span("mcp.server.call") as span:
            span.set_attribute("toolName", self.name)
            span.set_attribute("toolSource", "mcp")
            span.set_attribute("mcp.server", self._server)
            span.set_attribute("mcp.tool", self._definition.name)
            try:
                result = await self._client.call_tool(
                    self._definition.name,
                    arguments,
                    timeout_s=min(self._timeout_s, remaining),
                )
                if not isinstance(result, McpCallResult):
                    raise ToolError("mcp tool call returned an unexpected payload")
            except McpError as exc:
                outcome = "failure"
                raise ToolError("mcp tool call failed") from exc
            except ToolError:
                outcome = "failure"
                raise
            finally:
                span.set_attribute("outcome", outcome)
                span.set_attribute("mcp.duration_ms", (time.perf_counter() - started) * 1000.0)
        if result.dropped_blocks:
            LOGGER.warning(
                "MCP tool returned non-text content blocks: tool=%s dropped=%s",
                self.name,
                result.dropped_blocks,
            )
        # 先掩码后截断：截断可能把 PII 切成不可识别形态，顺序反了会漏（D-127）。
        masked = mask_text(result.text)
        return ToolResult(
            observation=fit_observation(
                f"mcp:{self._server}",
                masked.masked,
                limit=self._max_output_chars,
            ),
            masked_count=input_gate.count + masked.count,
            pii_categories=tuple(sorted({*input_gate.categories, *masked.categories})),
        )
