from __future__ import annotations

from docqa_ai.tools.web.untrusted import wrap_untrusted

TRUNCATION_MARKER = "（已截断）"

# 包裹标签与截断提示本身要占字符；低于这个额度时无法同时保证「已包裹」与「不超长」。
MIN_WRAPPED_LIMIT = 120


def fit_observation(source: str, body: str, *, limit: int) -> str:
    """把第三方正文包进 `<untrusted>` 并保证整条观测不超过 `limit` 字符（D-142）。

    截断提示在包裹内部，闭合标签不会被切掉；转义可能让正文变长，因此逐次收缩直到放得下。
    """

    limit = max(1, limit)
    wrapped = wrap_untrusted(source, body)
    if len(wrapped) <= limit:
        return wrapped
    marker = "\n" + TRUNCATION_MARKER
    keep = max(0, limit - len(wrap_untrusted(source, TRUNCATION_MARKER)))
    while keep > 0:
        text = body[:keep].rstrip()
        candidate = wrap_untrusted(source, text + marker if text else TRUNCATION_MARKER)
        if len(candidate) <= limit:
            return candidate
        keep -= max(1, len(candidate) - limit)
    return wrap_untrusted(source, TRUNCATION_MARKER)
