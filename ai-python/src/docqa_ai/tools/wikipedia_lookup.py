from __future__ import annotations

import json
import logging
from dataclasses import dataclass
from urllib.parse import quote

from docqa_ai.agent.errors import DeadlineExceeded
from docqa_ai.pii.masker import mask_text
from docqa_ai.tools.base import ToolContext, ToolError, ToolResult
from docqa_ai.tools.web.client import (
    BoundedHttpClient,
    FetchRejected,
    FetchTimeout,
    FetchUnavailable,
    HttpResponse,
    PayloadTooLarge,
)
from docqa_ai.tools.web.guard import bounded_timeout, pii_total
from docqa_ai.tools.web.inputs import choice_field, parse_argument, reject_unknown, text_field
from docqa_ai.tools.web.untrusted import wrap_untrusted

LOGGER = logging.getLogger(__name__)

_LANGUAGES = frozenset({"zh", "en"})
_MAX_OUTPUT_CHARS = 1500
_WRAPPER_RESERVE = 80
_MAX_QUERY = 200
_MAX_ENTRIES = 3
_MAX_EXTRACT = 400


class _Unavailable(Exception):
    """Wikipedia API is unreachable or returned a server error."""


@dataclass(frozen=True)
class _Entry:
    title: str
    extract: str
    url: str
    description: str


def _json_object(response: HttpResponse) -> dict[str, object] | None:
    try:
        parsed = json.loads(response.body.decode("utf-8", "replace"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        return None
    return parsed if isinstance(parsed, dict) else None


class WikipediaLookupTool:
    """Wikipedia REST summary with a search fallback."""

    name = "wikipedia_lookup"
    source = "builtin"
    description = "查询维基百科条目摘要；支持中文与英文"
    input_hint = 'JSON 或词条：{"query": "…", "lang": "zh|en"}'

    def __init__(
        self,
        client: BoundedHttpClient,
        *,
        default_lang: str = "zh",
        max_output_chars: int = _MAX_OUTPUT_CHARS,
        timeout_s: float = 5.0,
    ) -> None:
        if default_lang not in _LANGUAGES:
            raise ValueError("wikipedia language must be zh or en")
        self._client = client
        self._default_lang = default_lang
        self._max_output_chars = max(200, max_output_chars)
        self._timeout_s = timeout_s

    async def run(self, argument: str, context: ToolContext) -> ToolResult:
        data = parse_argument(argument, primary="query")
        reject_unknown(data, {"query", "lang"})
        raw_query = text_field(data, "query", required=True, max_chars=_MAX_QUERY)
        lang = choice_field(data, "lang", _LANGUAGES, default=self._default_lang)

        input_gate = mask_text(raw_query)
        query = input_gate.masked
        try:
            entries: list[_Entry] = []
            exact = await self._summary(lang, query, context)
            if exact is not None:
                entries.append(exact)
            else:
                for title in (await self._search(lang, query, context))[:_MAX_ENTRIES]:
                    entry = await self._summary(lang, title, context)
                    if entry is not None:
                        entries.append(entry)
                    if len(entries) >= _MAX_ENTRIES:
                        break
        except _Unavailable as exc:
            LOGGER.warning("wikipedia_lookup unavailable: lang=%s", lang)
            raise ToolError("wikipedia lookup is unavailable") from exc

        if not entries:
            raise ToolError("wikipedia has no matching entry")

        masks = [input_gate]
        lines: list[str] = [f"维基百科检索结果（query={query}）："]
        for position, entry in enumerate(entries[:_MAX_ENTRIES], 1):
            title_gate = mask_text(entry.title)
            extract_gate = mask_text(entry.extract[:_MAX_EXTRACT])
            url_gate = mask_text(entry.url)
            description_gate = mask_text(entry.description)
            masks.extend((title_gate, extract_gate, url_gate, description_gate))
            lines.append(f"[{position}] {title_gate.masked}")
            if description_gate.masked:
                lines.append(description_gate.masked)
            if extract_gate.masked:
                lines.append(extract_gate.masked)
            if url_gate.masked:
                lines.append(f"链接：{url_gate.masked}")

        text = "\n".join(lines)
        budget = self._max_output_chars - _WRAPPER_RESERVE
        if len(text) > budget:
            text = text[:budget].rstrip() + "\n（已截断）"
        observation = wrap_untrusted("wikipedia", text)
        masked_count, categories = pii_total(*masks)
        return ToolResult(observation=observation, masked_count=masked_count, pii_categories=categories)

    async def _get(
        self,
        url: str,
        params: dict[str, object] | None,
        context: ToolContext,
    ) -> HttpResponse:
        timeout = bounded_timeout(context, self._timeout_s)
        try:
            return await self._client.get(
                url,
                params=params,
                check_robots=False,
                allowed_hosts=(),
                timeout_s=timeout,
            )
        except FetchTimeout as exc:
            if context.deadline.expired():
                raise DeadlineExceeded("deadline expired during wikipedia lookup") from exc
            raise _Unavailable("request timed out") from exc
        except FetchRejected as exc:
            raise _Unavailable("request rejected") from exc
        except PayloadTooLarge as exc:
            raise _Unavailable("response too large") from exc
        except FetchUnavailable as exc:
            raise _Unavailable("endpoint unavailable") from exc

    async def _summary(self, lang: str, title: str, context: ToolContext) -> _Entry | None:
        url = f"https://{lang}.wikipedia.org/api/rest_v1/page/summary/{quote(title, safe='')}"
        response = await self._get(url, None, context)
        if response.status == 404:
            return None
        if not 200 <= response.status < 300:
            raise _Unavailable(f"summary returned HTTP {response.status}")
        payload = _json_object(response)
        if payload is None:
            raise _Unavailable("summary payload is not JSON")
        extract = payload.get("extract")
        if not isinstance(extract, str) or not extract.strip():
            return None
        page_title = payload.get("title")
        content_urls = payload.get("content_urls")
        desktop = content_urls.get("desktop") if isinstance(content_urls, dict) else None
        page_url = desktop.get("page") if isinstance(desktop, dict) else None
        description = payload.get("description")
        fallback_url = f"https://{lang}.wikipedia.org/wiki/{quote(title.replace(' ', '_'))}"
        return _Entry(
            title=page_title if isinstance(page_title, str) and page_title.strip() else title,
            extract=extract.strip(),
            url=page_url if isinstance(page_url, str) else fallback_url,
            description=description if isinstance(description, str) else "",
        )

    async def _search(self, lang: str, query: str, context: ToolContext) -> list[str]:
        response = await self._get(
            f"https://{lang}.wikipedia.org/w/api.php",
            {
                "action": "query",
                "list": "search",
                "srsearch": query,
                "srlimit": _MAX_ENTRIES,
                "format": "json",
                "formatversion": 2,
            },
            context,
        )
        if not 200 <= response.status < 300:
            raise _Unavailable(f"search returned HTTP {response.status}")
        payload = _json_object(response)
        if payload is None:
            raise _Unavailable("search payload is not JSON")
        query_block = payload.get("query")
        results = query_block.get("search") if isinstance(query_block, dict) else None
        if not isinstance(results, list):
            return []
        titles: list[str] = []
        for item in results:
            if isinstance(item, dict) and isinstance(item.get("title"), str) and item["title"].strip():
                titles.append(item["title"].strip())
        return titles
