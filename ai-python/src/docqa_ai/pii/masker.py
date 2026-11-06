from __future__ import annotations

import re
import unicodedata
from dataclasses import dataclass

PLACEHOLDERS = {
    "PHONE": "[PHONE]",
    "ID_CARD": "[ID_CARD]",
    "EMAIL": "[EMAIL]",
}
VERSION = "pii-v1"

_ZERO_WIDTH = re.compile("[\u200b-\u200d\u2060\ufeff]")
_EMAIL = re.compile(r"[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}", re.IGNORECASE)
_ID_CARD_18 = re.compile(r"(?<!\d)[1-9]\d{5}(?:18|19|20)\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\d|3[01])\d{3}[\dXx](?!\d)")
_ID_CARD_15 = re.compile(r"(?<!\d)[1-9]\d{5}\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\d|3[01])\d{3}(?!\d)")
_PHONE = re.compile(r"(?<!\d)(?:\+?86[\s-]?)?1[3-9]\d{9}(?!\d)")

_PATTERNS: tuple[tuple[str, re.Pattern[str]], ...] = (
    ("EMAIL", _EMAIL),
    ("ID_CARD", _ID_CARD_18),
    ("ID_CARD", _ID_CARD_15),
    ("PHONE", _PHONE),
)


@dataclass(frozen=True)
class MaskResult:
    masked: str
    categories: tuple[str, ...]
    count: int

    @property
    def contains_pii(self) -> bool:
        return self.count > 0


def normalize(text: str | None) -> str:
    value = "" if text is None else text
    return _ZERO_WIDTH.sub("", unicodedata.normalize("NFKC", value))


def mask_text(text: str | None) -> MaskResult:
    """Mask phone, ID card and email using the pii-v1 rules shared with Java."""

    value = normalize(text)
    categories: list[str] = []
    total = 0
    for category, pattern in _PATTERNS:
        value, hits = pattern.subn(PLACEHOLDERS[category], value)
        if hits:
            total += hits
            if category not in categories:
                categories.append(category)
    return MaskResult(masked=value, categories=tuple(categories), count=total)


def contains_pii(text: str | None) -> bool:
    return mask_text(text).contains_pii


def detect_categories(text: str | None) -> tuple[tuple[str, ...], int]:
    result = mask_text(text)
    return result.categories, result.count
