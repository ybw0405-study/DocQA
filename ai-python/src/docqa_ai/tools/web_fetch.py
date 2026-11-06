from __future__ import annotations

import logging
from urllib.parse import urlsplit

from docqa_ai.agent.errors import DeadlineExceeded
from docqa_ai.pii.masker import mask_text
from docqa_ai.tools.base import ToolContext, ToolError, ToolResult
from docqa_ai.tools.web.client import (
    BoundedHttpClient,
    FetchRejected,
    FetchTimeout,
    FetchUnavailable,
    PayloadTooLarge,
)
from docqa_ai.tools.web.extract import extract_html, extract_plain
from docqa_ai.tools.web.guard import bounded_timeout, pii_total
from docqa_ai.tools.web.inputs import int_field, parse_argument, reject_unknown, text_field
from docqa_ai.tools.web.untrusted import wrap_untrusted

LOGGER = logging.getLogger(__name__)

_MAX_URL = 2048
_MAX_OUTPUT_CHARS = 1500
# 包裹标签本身要占字符：先给正文留下净额，避免截断把闭合标签切掉。
_WRAPPER_RESERVE = 80
_MAX_TITLE = 300
_HTML_TYPES = ("text/html", "application/xhtml+xml")


def _acceptable(content_type: str) -> bool:
    lowered = content_type.split(";", 1)[0].strip().lower()
    return lowered in _HTML_TYPES or lowered.startswith("text/")


def _host_hint(url: str) -> str:
    try:
        return urlsplit(url).hostname or ""
    except ValueError:
        return ""


class WebFetchTool:
    """Fetches one public page through the bounded client (D-137 / D-141).

    The model supplies the URL, so this is the only built-in that reaches arbitrary
    hosts: SSRF validation, robots.txt, redirect revalidation, a byte ceiling and a
    deadline all apply before any byte is parsed.
    """

    name = "web_fetch"
    source = "builtin"
    description = "抓取一个公开网页并返回正文摘要；只支持 http/https 与公开主机"
    input_hint = 'JSON 或 URL：{"url": "https://…", "maxChars": 2000}，maxChars ≤4000'

    def __init__(
        self,
        client: BoundedHttpClient,
        *,
        max_chars: int = 4000,
        max_output_chars: int = _MAX_OUTPUT_CHARS,
        timeout_s: float = 5.0,
    ) -> None:
        if max_chars < 200:
            raise ValueError("web_fetch max_chars must be at least 200")
        self._client = client
        self._max_chars = max_chars
        self._max_output_chars = max(200, max_output_chars)
        self._timeout_s = timeout_s

    async def run(self, argument: str, context: ToolContext) -> ToolResult:
        data = parse_argument(argument, primary="url")
        reject_unknown(data, {"url", "maxChars"})
        raw_url = text_field(data, "url", required=True, max_chars=_MAX_URL)
        requested = int_field(data, "maxChars", minimum=200, maximum=self._max_chars) or self._max_chars

        # 入站门禁：URL 里的 PII 先掩码再外发；掩码后结构非法由 validate_url 兜底拒绝。
        input_gate = mask_text(raw_url)
        timeout = bounded_timeout(context, self._timeout_s)
        try:
            response = await self._client.get(input_gate.masked, timeout_s=timeout, check_robots=True)
        except FetchTimeout as exc:
            if context.deadline.expired():
                raise DeadlineExceeded("deadline expired during web_fetch") from exc
            raise ToolError("web_fetch timed out") from exc
        except FetchRejected as exc:
            LOGGER.warning("web_fetch rejected: host=%s", _host_hint(input_gate.masked))
            raise ToolError("web_fetch request was rejected by the safety policy") from exc
        except PayloadTooLarge as exc:
            raise ToolError("web_fetch response is too large") from exc
        except FetchUnavailable as exc:
            LOGGER.warning("web_fetch unavailable: host=%s", _host_hint(input_gate.masked))
            raise ToolError("web_fetch is unavailable") from exc

        if not 200 <= response.status < 300:
            raise ToolError(f"web_fetch returned HTTP {response.status}")
        content_type = response.header("content-type")
        if not _acceptable(content_type):
            raise ToolError("web_fetch only accepts HTML or plain text")

        lowered = content_type.split(";", 1)[0].strip().lower()
        if lowered in _HTML_TYPES:
            page = extract_html(response.body, content_type, max_chars=requested)
        else:
            page = extract_plain(response.body, content_type, max_chars=requested)

        url_gate = mask_text(response.url)
        title_gate = mask_text(page.title[:_MAX_TITLE])
        body_gate = mask_text(page.text)
        parts: list[str] = []
        if title_gate.masked:
            parts.append(f"标题：{title_gate.masked}")
        parts.append(f"来源：{url_gate.masked}")
        parts.append(body_gate.masked or "（页面没有可提取的正文）")
        if response.truncated:
            # 未声明 content-length 时只能流式截断；不说明会把「被切掉」误当成页面结尾。
            parts.append("（响应体超过字节上限，正文可能不完整）")
        if page.truncated:
            parts.append(f"（页面正文已截断到 {requested} 字符）")
        text = "\n".join(parts)
        budget = self._max_output_chars - _WRAPPER_RESERVE
        if len(text) > budget:
            text = text[:budget].rstrip() + "\n（已截断）"
        host = _host_hint(response.url)
        observation = wrap_untrusted(f"web:{host}" if host else "web", text)
        masked_count, categories = pii_total(input_gate, url_gate, title_gate, body_gate)
        return ToolResult(
            observation=observation,
            masked_count=masked_count,
            pii_categories=categories,
        )
