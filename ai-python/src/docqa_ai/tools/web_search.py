from __future__ import annotations

import json
import logging
import os
from dataclasses import dataclass
from typing import Literal
from urllib.parse import urlsplit

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
from docqa_ai.tools.web.inputs import int_field, parse_argument, reject_unknown, text_field
from docqa_ai.tools.web.untrusted import wrap_untrusted

LOGGER = logging.getLogger(__name__)

_TAVILY_ENDPOINT = "https://api.tavily.com/search"
_MAX_OUTPUT_CHARS = 1500
_WRAPPER_RESERVE = 80
_MAX_QUERY = 200
_MAX_RESULTS = 5
_MAX_SUMMARY = 300


class _BackendError(Exception):
    """One search backend failed; the fallback may still answer."""


@dataclass(frozen=True)
class _Result:
    title: str
    url: str
    summary: str


def _json_object(response: HttpResponse) -> dict[str, object]:
    try:
        parsed = json.loads(response.body.decode("utf-8", "replace"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise _BackendError("payload is not JSON") from exc
    if not isinstance(parsed, dict):
        raise _BackendError("payload is not a JSON object")
    return parsed


def _web_url(raw: object) -> str:
    if not isinstance(raw, str) or not raw.strip():
        return ""
    try:
        parts = urlsplit(raw.strip())
    except ValueError:
        return ""
    return raw.strip() if parts.scheme in ("http", "https") and parts.netloc else ""


class WebSearchTool:
    """SearXNG first, Tavily as an optional fallback; never fabricates results."""

    name = "web_search"
    source = "builtin"
    description = "联网搜索网页标题与摘要，不返回正文"
    input_hint = 'JSON 或关键词：{"query": "…", "maxResults": 5}'

    def __init__(
        self,
        client: BoundedHttpClient,
        *,
        provider: Literal["searxng", "tavily"],
        searxng_url: str = "",
        tavily_key_env: str = "TAVILY_API_KEY",
        max_results: int = _MAX_RESULTS,
        max_output_chars: int = _MAX_OUTPUT_CHARS,
        timeout_s: float = 5.0,
    ) -> None:
        if provider == "searxng" and not searxng_url.strip():
            raise ValueError("searxng_url is required when provider=searxng")
        self._client = client
        self._provider = provider
        self._searxng_url = searxng_url.strip().rstrip("/")
        self._tavily_key_env = tavily_key_env
        self._max_results = max(1, min(max_results, _MAX_RESULTS))
        self._max_output_chars = max(200, max_output_chars)
        self._timeout_s = timeout_s

    async def run(self, argument: str, context: ToolContext) -> ToolResult:
        data = parse_argument(argument, primary="query")
        reject_unknown(data, {"query", "maxResults"})
        raw_query = text_field(data, "query", required=True, max_chars=_MAX_QUERY)
        requested = int_field(data, "maxResults", minimum=1, maximum=self._max_results) or self._max_results

        input_gate = mask_text(raw_query)
        query = input_gate.masked
        results: list[_Result] | None = None
        failures = 0
        for backend in self._backends():
            try:
                results = await self._run_backend(backend, query, requested, context)
            except _BackendError as exc:
                failures += 1
                LOGGER.warning("web_search backend failed: backend=%s error=%s", backend, type(exc).__name__)
                continue
            break
        if results is None:
            raise ToolError("web_search is unavailable") if failures else ToolError("web_search is not configured")

        if not results:
            return ToolResult(
                observation=f"联网搜索没有返回结果（query={query}）。",
                masked_count=input_gate.count,
                pii_categories=input_gate.categories,
            )

        masks = [input_gate]
        lines: list[str] = [f"搜索结果（query={query}）："]
        for position, result in enumerate(results[:requested], 1):
            title_gate = mask_text(result.title)
            url_gate = mask_text(result.url)
            summary_gate = mask_text(result.summary[:_MAX_SUMMARY])
            masks.extend((title_gate, url_gate, summary_gate))
            lines.append(f"[{position}] {title_gate.masked}")
            if url_gate.masked:
                lines.append(f"链接：{url_gate.masked}")
            if summary_gate.masked:
                lines.append(f"摘要：{summary_gate.masked}")

        text = "\n".join(lines)
        budget = self._max_output_chars - _WRAPPER_RESERVE
        if len(text) > budget:
            text = text[:budget].rstrip() + "\n（已截断）"
        observation = wrap_untrusted("web_search", text)
        masked_count, categories = pii_total(*masks)
        return ToolResult(observation=observation, masked_count=masked_count, pii_categories=categories)

    def _backends(self) -> tuple[str, ...]:
        if self._provider == "searxng":
            if self._tavily_key():
                return ("searxng", "tavily")
            return ("searxng",)
        return ("tavily",)

    def _tavily_key(self) -> str:
        return os.environ.get(self._tavily_key_env, "").strip()

    async def _run_backend(
        self,
        backend: str,
        query: str,
        limit: int,
        context: ToolContext,
    ) -> list[_Result]:
        if backend == "searxng":
            return await self._searxng(query, limit, context)
        return await self._tavily(query, limit, context)

    async def _get(
        self,
        url: str,
        *,
        params: dict[str, object] | None = None,
        payload: dict[str, object] | None = None,
        headers: dict[str, str] | None = None,
        allow_private: bool,
        context: ToolContext,
    ) -> HttpResponse:
        timeout = bounded_timeout(context, self._timeout_s)
        try:
            if payload is None:
                return await self._client.get(
                    url,
                    params=params,
                    check_robots=False,
                    allow_private=allow_private,
                    allowed_hosts=(),
                    timeout_s=timeout,
                )
            return await self._client.post_json(
                url,
                payload,
                headers=headers,
                allow_private=allow_private,
                allowed_hosts=(),
                timeout_s=timeout,
            )
        except FetchTimeout as exc:
            if context.deadline.expired():
                raise DeadlineExceeded("deadline expired during web search") from exc
            raise _BackendError("request timed out") from exc
        except FetchRejected as exc:
            raise _BackendError("request rejected") from exc
        except PayloadTooLarge as exc:
            raise _BackendError("response too large") from exc
        except FetchUnavailable as exc:
            raise _BackendError("endpoint unavailable") from exc

    async def _searxng(self, query: str, limit: int, context: ToolContext) -> list[_Result]:
        response = await self._get(
            f"{self._searxng_url}/search",
            params={"q": query, "format": "json", "safesearch": 1, "language": "zh-CN"},
            allow_private=True,
            context=context,
        )
        if not 200 <= response.status < 300:
            raise _BackendError(f"searxng returned HTTP {response.status}")
        payload = _json_object(response)
        entries = payload.get("results")
        if not isinstance(entries, list):
            raise _BackendError("searxng payload has no results")
        results: list[_Result] = []
        for entry in entries:
            if not isinstance(entry, dict):
                continue
            url = _web_url(entry.get("url"))
            if not url:
                continue
            title = entry.get("title")
            content = entry.get("content")
            results.append(
                _Result(
                    title=title.strip() if isinstance(title, str) else url,
                    url=url,
                    summary=content.strip() if isinstance(content, str) else "",
                )
            )
        return results[:limit]

    async def _tavily(self, query: str, limit: int, context: ToolContext) -> list[_Result]:
        key = self._tavily_key()
        if not key:
            raise _BackendError("tavily key is not configured")
        response = await self._get(
            _TAVILY_ENDPOINT,
            payload={
                "query": query,
                "max_results": limit,
                "search_depth": "basic",
                "include_answer": False,
                "include_raw_content": False,
            },
            headers={"Authorization": f"Bearer {key}"},
            allow_private=False,
            context=context,
        )
        if not 200 <= response.status < 300:
            raise _BackendError(f"tavily returned HTTP {response.status}")
        payload = _json_object(response)
        entries = payload.get("results")
        if not isinstance(entries, list):
            raise _BackendError("tavily payload has no results")
        results: list[_Result] = []
        for entry in entries:
            if not isinstance(entry, dict):
                continue
            url = _web_url(entry.get("url"))
            if not url:
                continue
            title = entry.get("title")
            content = entry.get("content")
            results.append(
                _Result(
                    title=title.strip() if isinstance(title, str) else url,
                    url=url,
                    summary=content.strip() if isinstance(content, str) else "",
                )
            )
        return results[:limit]
