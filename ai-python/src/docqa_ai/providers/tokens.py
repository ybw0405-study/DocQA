from __future__ import annotations

import math
import re

_CJK = re.compile(r"[\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff]")
_WORD = re.compile(r"[A-Za-z0-9_]+")
_OTHER = re.compile(r"[^\sA-Za-z0-9_\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff]")


def estimate_tokens(text: str | None) -> int:
    """Conservative upper bound used for pre-call token reservation.

    CJK characters count as one token each; ASCII words count as four characters per token.
    This intentionally over-estimates rather than over-spending the Java-initialized budget.
    """

    if not text:
        return 0
    cjk = len(_CJK.findall(text))
    words = sum(math.ceil(len(word) / 4) for word in _WORD.findall(text))
    punctuation = math.ceil(len(_OTHER.findall(text)) / 4)
    return max(1, cjk + words + punctuation)


def estimate_messages_tokens(messages: list[dict[str, str]]) -> int:
    total = 0
    for message in messages:
        total += estimate_tokens(message.get("content", "")) + 4
    return total
