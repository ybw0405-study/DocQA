from __future__ import annotations

from docqa_ai.agent.errors import DeadlineExceeded
from docqa_ai.pii.masker import MaskResult
from docqa_ai.tools.base import ToolContext


def bounded_timeout(context: ToolContext, configured_s: float) -> float:
    remaining = context.deadline.remaining_seconds()
    if remaining <= 0:
        raise DeadlineExceeded("deadline expired before the web tool ran")
    return min(configured_s, remaining)


def pii_total(*masks: MaskResult) -> tuple[int, tuple[str, ...]]:
    count = sum(mask.count for mask in masks)
    categories = tuple(sorted({category for mask in masks for category in mask.categories}))
    return count, categories
