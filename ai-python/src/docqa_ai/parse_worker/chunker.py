from __future__ import annotations

import re
from collections.abc import Sequence
from dataclasses import dataclass

from docqa_ai.parse_worker.parser import ParsedPage
from docqa_ai.providers.tokens import estimate_tokens

_BREAKS = ("\n\n", "\n", "。", "！", "？", ". ", "! ", "? ", "；", "; ", " ")
_BLANKS = re.compile(r"\n{3,}")
_TRAILING_SPACE = re.compile(r"[ \t]+\n")


@dataclass(frozen=True)
class TextChunk:
    index: int
    content: str
    page_no: int | None
    token_count: int


def chunk_document(
    pages: Sequence[ParsedPage],
    *,
    chunk_size: int = 800,
    overlap: int = 100,
) -> list[TextChunk]:
    """Split pages into ordered, overlapping chunks of at most ``chunk_size`` characters."""

    if chunk_size <= 0:
        raise ValueError("chunk_size must be positive")
    if overlap < 0 or overlap >= chunk_size:
        raise ValueError("overlap must be non-negative and smaller than chunk_size")

    chunks: list[TextChunk] = []
    index = 0
    for page in pages:
        text = _normalize(page.text)
        if not text:
            continue
        start = 0
        while start < len(text):
            end = min(start + chunk_size, len(text))
            if end < len(text):
                end = _break_point(text, start, end)
            content = text[start:end].strip()
            if content:
                chunks.append(
                    TextChunk(
                        index=index,
                        content=content,
                        page_no=page.page_no,
                        token_count=estimate_tokens(content),
                    )
                )
                index += 1
            if end >= len(text):
                break
            start = max(end - overlap, start + (end - start) // 2, start + 1)
    return chunks


def _normalize(text: str) -> str:
    normalized = text.replace("\r\n", "\n").replace("\r", "\n")
    normalized = _TRAILING_SPACE.sub("\n", normalized)
    normalized = _BLANKS.sub("\n\n", normalized)
    return normalized.strip()


def _break_point(text: str, start: int, hard_end: int) -> int:
    search_from = start + max(1, (hard_end - start) // 2)
    best = start
    window = text[search_from:hard_end]
    for separator in _BREAKS:
        position = window.rfind(separator)
        if position < 0:
            continue
        candidate = search_from + position + len(separator)
        if candidate > best:
            best = candidate
    return best if best > start else hard_end
