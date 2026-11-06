from __future__ import annotations

import re

_TAG_ESCAPE = re.compile(r"<\s*(/?)\s*untrusted", re.IGNORECASE)
# 终端控制序列与除 \n \t 外的 C0/C1 控制字符：第三方页面不得向日志或提示里注入显示指令。
_ANSI = re.compile(r"\x1b\[[0-9;?]*[ -/]*[@-~]")
_CONTROL = re.compile("[\x00-\x08\x0b\x0c\x0e-\x1f\x7f-\x9f]")


def _neutralize(text: str) -> str:
    """Third-party text must not be able to close the wrapper and escape as instructions."""

    cleaned = _CONTROL.sub("", _ANSI.sub("", text))
    return _TAG_ESCAPE.sub(lambda match: f"&lt;{match.group(1)}untrusted", cleaned)


def wrap_untrusted(source: str, body: str) -> str:
    return f'<untrusted source="{source}">\n{_neutralize(body).strip()}\n</untrusted>'
