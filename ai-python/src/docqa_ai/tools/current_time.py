from __future__ import annotations

from collections.abc import Callable
from datetime import UTC, datetime

from docqa_ai.tools.base import ToolContext, ToolError, ToolResult


class CurrentTimeTool:
    name = "current_time"
    source = "builtin"
    description = "获取当前 UTC 日期与时间"
    input_hint = "无参数，必须传空字符串"

    def __init__(self, clock: Callable[[], datetime] | None = None) -> None:
        self._clock = clock or (lambda: datetime.now(UTC))

    async def run(self, argument: str, context: ToolContext) -> ToolResult:
        if argument.strip():
            raise ToolError("current_time does not accept input")
        now = self._clock()
        if now.tzinfo is None:
            now = now.replace(tzinfo=UTC)
        return ToolResult(observation=f"当前时间（UTC）：{now.astimezone(UTC).isoformat(timespec='seconds')}")
