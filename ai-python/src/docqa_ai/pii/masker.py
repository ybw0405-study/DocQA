from __future__ import annotations

import re
import unicodedata
from dataclasses import dataclass

PLACEHOLDERS = {
    "EMAIL": "[EMAIL]",
    "ID_CARD": "[ID_CARD]",
    "BANK_CARD": "[BANK_CARD]",
    "PASSPORT": "[PASSPORT]",
    "SSN": "[SSN]",
    "PHONE": "[PHONE]",
}
VERSION = "pii-v2"
LEGACY_VERSION = "pii-v1"
# v1 只有三类；v2 在此之上增加银行卡/护照/社保号。
_V1_CATEGORIES = frozenset({"EMAIL", "ID_CARD", "PHONE"})

_ZERO_WIDTH = re.compile("[\u200b-\u200d\u2060\ufeff]")
_EMAIL = re.compile(r"[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}", re.IGNORECASE)
_ID_CARD_18 = re.compile(r"(?<!\d)[1-9]\d{5}(?:18|19|20)\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\d|3[01])\d{3}[\dXx](?!\d)")
_ID_CARD_15 = re.compile(r"(?<!\d)[1-9]\d{5}\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\d|3[01])\d{3}(?!\d)")
# 16~19 位连续数字候选；真正的银行卡判定在 _is_bank_card()（前缀 + Luhn），
# 避免把订单号/流水号这类长数字一律当成卡号（DQ-P2-3 的主要误报风险点）。
_BANK_CARD = re.compile(r"(?<!\d)\d{16,19}(?!\d)")
# 中国护照：E/G/S/D/P + 8 位数字（2019 版及现行）；全角已由 NFKC 归一。
_PASSPORT = re.compile(r"(?<![A-Za-z0-9])[EGSDP]\d{8}(?![A-Za-z0-9])")
# 美式社保号 123-45-6789；带分隔符，误报面很窄。
_SSN = re.compile(r"(?<!\d)\d{3}-\d{2}-\d{4}(?!\d)")
_PHONE = re.compile(r"(?<!\d)(?:\+?86[\s-]?)?1[3-9]\d{9}(?!\d)")

_PATTERNS: tuple[tuple[str, re.Pattern[str]], ...] = (
    ("EMAIL", _EMAIL),
    ("ID_CARD", _ID_CARD_18),
    ("ID_CARD", _ID_CARD_15),
    ("BANK_CARD", _BANK_CARD),
    ("PASSPORT", _PASSPORT),
    ("SSN", _SSN),
    ("PHONE", _PHONE),
)

# 常见卡组织 IIN 前缀：银联 62、Visa 4、Mastercard 51-55。
_CARD_PREFIXES = ("62", "4", "51", "52", "53", "54", "55")


def _luhn_valid(digits: str) -> bool:
    total = 0
    double = False
    for ch in reversed(digits):
        d = ord(ch) - 48
        if double:
            d *= 2
            if d > 9:
                d -= 9
        total += d
        double = not double
    return total % 10 == 0


def _is_bank_card(value: str) -> bool:
    if not (16 <= len(value) <= 19) or not value.isdigit():
        return False
    if not value.startswith(_CARD_PREFIXES):
        return False
    return _luhn_valid(value)


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


def mask_text(text: str | None, *, version: str = VERSION) -> MaskResult:
    """按给定契约版本掩码；v1 仅三类，v2 增加银行卡/护照/社保号。

    Python 第二层复检 Java 已掩码文本时须用请求声明的版本，避免 v1 客户端
    因新规则被误判为「Java 漏脱敏」；检索片段/Prompt/输出的掩码继续用默认 v2
    （更严格只会多遮，不会漏遮）。
    """
    value = normalize(text)
    allowed = _V1_CATEGORIES if version == LEGACY_VERSION else None
    categories: list[str] = []
    total = 0
    for category, pattern in _PATTERNS:
        if allowed is not None and category not in allowed:
            continue
        if category == "BANK_CARD":
            hits = 0

            def repl(match: re.Match[str]) -> str:
                nonlocal hits
                if _is_bank_card(match.group(0)):
                    hits += 1
                    return PLACEHOLDERS["BANK_CARD"]
                return match.group(0)

            value = pattern.sub(repl, value)
            replaced = hits
        else:
            value, replaced = pattern.subn(PLACEHOLDERS[category], value)
        if replaced:
            total += replaced
            if category not in categories:
                categories.append(category)
    return MaskResult(masked=value, categories=tuple(categories), count=total)


def contains_pii(text: str | None, *, version: str = VERSION) -> bool:
    return mask_text(text, version=version).contains_pii


def detect_categories(text: str | None, *, version: str = VERSION) -> tuple[tuple[str, ...], int]:
    result = mask_text(text, version=version)
    return result.categories, result.count
