from __future__ import annotations

from collections.abc import Iterable, Sequence

from docqa_ai.tools.base import Tool, ToolSpec


class ToolRegistry:
    def __init__(self, tools: Iterable[Tool]) -> None:
        self._tools = {tool.name: tool for tool in tools}

    def get(self, name: str) -> Tool | None:
        return self._tools.get(name)

    def resolve(self, allowed: Sequence[str]) -> tuple[Tool, ...]:
        resolved: list[Tool] = []
        for name in dict.fromkeys(allowed):
            tool = self._tools.get(name)
            if tool is not None:
                resolved.append(tool)
        return tuple(resolved)

    def specs(self, allowed: Sequence[str]) -> tuple[ToolSpec, ...]:
        return tuple(
            ToolSpec(
                name=tool.name,
                source=tool.source,
                description=tool.description,
                input_hint=tool.input_hint,
            )
            for tool in self.resolve(allowed)
        )

    def source(self, name: str) -> str | None:
        tool = self._tools.get(name)
        return None if tool is None else tool.source

    def names(self) -> tuple[str, ...]:
        return tuple(sorted(self._tools))

    def extended(self, extra: Iterable[Tool]) -> ToolRegistry:
        """Per-request overlay for dynamically discovered tools; the base registry stays immutable."""

        return ToolRegistry([*self._tools.values(), *extra])
