from __future__ import annotations

import json

from docqa_ai.tools.base import ToolError

# web_fetch 的 URL 上限是 2048，JSON 外壳还要占字符，因此比 MCP 的 2000 更宽松。
MAX_INPUT_CHARS = 4096


def parse_argument(argument: str, *, primary: str) -> dict[str, object]:
    """接受「纯字符串」或「JSON 对象」两种形态，二者都归一为字典。

    纯字符串形态是为了让离线/小模型不必构造 JSON；一旦以 `{` 开头就按 JSON 解析，
    解析失败即失败，不做容错猜测（D-131 的同序原则：结构非法不外呼）。
    """

    raw = (argument or "").strip()
    if not raw or len(raw) > MAX_INPUT_CHARS:
        raise ToolError("tool input is empty or too long")
    if raw.startswith("{"):
        try:
            parsed = json.loads(raw)
        except json.JSONDecodeError as exc:
            raise ToolError("tool input must be a JSON object") from exc
        if not isinstance(parsed, dict):
            raise ToolError("tool input must be a JSON object")
        return parsed
    return {primary: raw}


def reject_unknown(data: dict[str, object], allowed: set[str]) -> None:
    if set(data) - allowed:
        raise ToolError("tool input contains an unknown field")


def text_field(
    data: dict[str, object],
    name: str,
    *,
    required: bool = False,
    max_chars: int = 200,
) -> str:
    value = data.get(name)
    if value is None:
        if required:
            raise ToolError(f"{name} is required")
        return ""
    if not isinstance(value, str):
        raise ToolError(f"{name} must be a string")
    text = value.strip()
    if required and not text:
        raise ToolError(f"{name} is required")
    if len(text) > max_chars:
        raise ToolError(f"{name} is too long")
    return text


def int_field(
    data: dict[str, object],
    name: str,
    *,
    minimum: int,
    maximum: int,
) -> int | None:
    value = data.get(name)
    if value is None:
        return None
    if isinstance(value, bool) or not isinstance(value, int):
        raise ToolError(f"{name} must be an integer")
    if value < minimum or value > maximum:
        raise ToolError(f"{name} is out of range")
    return value


def choice_field(data: dict[str, object], name: str, choices: frozenset[str], *, default: str) -> str:
    value = data.get(name)
    if value is None:
        return default
    if not isinstance(value, str):
        raise ToolError(f"{name} must be a string")
    text = value.strip().lower()
    if text not in choices:
        raise ToolError(f"{name} is not supported")
    return text
