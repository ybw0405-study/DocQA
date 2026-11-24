from __future__ import annotations

import json
import logging
from dataclasses import dataclass
from datetime import UTC, datetime
from urllib.parse import urlsplit

from lxml import etree

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
from docqa_ai.tools.web.inputs import (
    choice_field,
    int_field,
    parse_argument,
    reject_unknown,
    text_field,
)
from docqa_ai.tools.web.untrusted import wrap_untrusted

LOGGER = logging.getLogger(__name__)

_OPENALEX_ENDPOINT = "https://api.openalex.org/works"
_ARXIV_ENDPOINT = "https://export.arxiv.org/api/query"
_ATOM = "{http://www.w3.org/2005/Atom}"
_SOURCES = frozenset({"openalex", "arxiv", "both"})
_MAX_OUTPUT_CHARS = 1500
_WRAPPER_RESERVE = 80
_MAX_QUERY = 200
_MAX_RESULTS = 5
_MAX_ABSTRACT = 300
_MIN_YEAR = 1900


class _ProviderError(Exception):
    """One academic backend failed; the other may still answer."""


@dataclass(frozen=True)
class _Item:
    title: str
    year: str
    authors: str
    url: str
    summary: str


def _host_hint(url: str) -> str:
    try:
        return urlsplit(url).hostname or ""
    except ValueError:
        return ""


def _json_object(response: HttpResponse) -> dict[str, object]:
    try:
        parsed = json.loads(response.body.decode("utf-8", "replace"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise _ProviderError("response is not JSON") from exc
    if not isinstance(parsed, dict):
        raise _ProviderError("response is not a JSON object")
    return parsed


def _author_line(names: list[str]) -> str:
    cleaned = [name.strip() for name in names if isinstance(name, str) and name.strip()]
    if not cleaned:
        return ""
    if len(cleaned) <= 2:
        return ", ".join(cleaned)
    return f"{cleaned[0]} 等"


def _inverted_abstract(index: object) -> str:
    """OpenAlex 的摘要是「词 → 位置」倒排，按位置还原后再截断。"""

    if not isinstance(index, dict):
        return ""
    pairs: list[tuple[int, str]] = []
    for word, positions in index.items():
        if not isinstance(word, str) or not isinstance(positions, list):
            continue
        for position in positions:
            if isinstance(position, int) and not isinstance(position, bool):
                pairs.append((position, word))
    pairs.sort()
    return " ".join(word for _, word in pairs[:_MAX_ABSTRACT])[:_MAX_ABSTRACT]


class AcademicSearchTool:
    """OpenAlex + arXiv metadata search; abstracts only, never full text."""

    name = "academic_search"
    source = "builtin"
    description = "检索 OpenAlex 与 arXiv 的论文元数据与摘要"
    input_hint = 'JSON：{"query": "…", "source": "openalex|arxiv|both", "yearFrom": 2020}'

    def __init__(
        self,
        client: BoundedHttpClient,
        *,
        mailto: str = "",
        max_results: int = _MAX_RESULTS,
        max_output_chars: int = _MAX_OUTPUT_CHARS,
        timeout_s: float = 5.0,
    ) -> None:
        self._client = client
        self._mailto = mailto.strip()
        self._max_results = max(1, min(max_results, _MAX_RESULTS))
        self._max_output_chars = max(200, max_output_chars)
        self._timeout_s = timeout_s

    async def run(self, argument: str, context: ToolContext) -> ToolResult:
        data = parse_argument(argument, primary="query")
        reject_unknown(data, {"query", "source", "yearFrom"})
        raw_query = text_field(data, "query", required=True, max_chars=_MAX_QUERY)
        source = choice_field(data, "source", _SOURCES, default="both")
        year_from = int_field(data, "yearFrom", minimum=_MIN_YEAR, maximum=datetime.now(UTC).year)

        input_gate = mask_text(raw_query)
        query = input_gate.masked
        items: list[_Item] = []
        failures: list[str] = []
        if source in ("openalex", "both"):
            try:
                items.extend(await self._openalex(query, year_from, context))
            except _ProviderError as exc:
                failures.append("openalex")
                LOGGER.warning("academic_search openalex failed: %s", type(exc).__name__)
        if source in ("arxiv", "both"):
            try:
                items.extend(await self._arxiv(query, context))
            except _ProviderError as exc:
                failures.append("arxiv")
                LOGGER.warning("academic_search arxiv failed: %s", type(exc).__name__)

        deduped: list[_Item] = []
        seen: set[str] = set()
        for item in items:
            key = " ".join(item.title.casefold().split())
            if not key or key in seen:
                continue
            seen.add(key)
            deduped.append(item)
        if not deduped:
            if failures:
                raise ToolError("academic search is unavailable")
            return ToolResult(
                observation="未检索到相关论文。",
                masked_count=input_gate.count,
                pii_categories=input_gate.categories,
            )

        masks = [input_gate]
        lines: list[str] = [f"学术检索结果（query={query}）："]
        for position, item in enumerate(deduped[: self._max_results], 1):
            title_gate = mask_text(item.title)
            url_gate = mask_text(item.url)
            summary_gate = mask_text(item.summary)
            authors_gate = mask_text(item.authors)
            masks.extend((title_gate, url_gate, summary_gate, authors_gate))
            head = f"[{position}] {title_gate.masked}"
            if item.year:
                head += f" ({item.year})"
            lines.append(head)
            if authors_gate.masked:
                lines.append(f"作者：{authors_gate.masked}")
            if url_gate.masked:
                lines.append(f"链接：{url_gate.masked}")
            if summary_gate.masked:
                lines.append(f"摘要：{summary_gate.masked}")
        if failures:
            lines.append(f"注：{'、'.join(failures)} 检索失败，以上为部分结果。")

        text = "\n".join(lines)
        budget = self._max_output_chars - _WRAPPER_RESERVE
        if len(text) > budget:
            text = text[:budget].rstrip() + "\n（已截断）"
        observation = wrap_untrusted("academic", text)
        masked_count, categories = pii_total(*masks)
        return ToolResult(observation=observation, masked_count=masked_count, pii_categories=categories)

    async def _fetch(self, url: str, params: dict[str, object], context: ToolContext) -> HttpResponse:
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
                raise DeadlineExceeded("deadline expired during academic search") from exc
            raise _ProviderError("request timed out") from exc
        except FetchRejected as exc:
            LOGGER.warning("academic endpoint rejected: host=%s", _host_hint(url))
            raise _ProviderError("request rejected") from exc
        except PayloadTooLarge as exc:
            raise _ProviderError("response too large") from exc
        except FetchUnavailable as exc:
            raise _ProviderError("endpoint unavailable") from exc

    async def _openalex(self, query: str, year_from: int | None, context: ToolContext) -> list[_Item]:
        params: dict[str, object] = {"search": query, "per-page": self._max_results}
        if self._mailto:
            params["mailto"] = self._mailto
        if year_from is not None:
            params["filter"] = f"from_publication_date:{year_from}-01-01"
        response = await self._fetch(_OPENALEX_ENDPOINT, params, context)
        if not 200 <= response.status < 300:
            raise _ProviderError(f"openalex returned HTTP {response.status}")
        results = _json_object(response).get("results")
        if not isinstance(results, list):
            raise _ProviderError("openalex payload has no results")
        items: list[_Item] = []
        for entry in results:
            if not isinstance(entry, dict):
                continue
            title = entry.get("title")
            if not isinstance(title, str) or not title.strip():
                continue
            authorships = entry.get("authorships")
            names: list[str] = []
            if isinstance(authorships, list):
                for authorship in authorships[:3]:
                    if not isinstance(authorship, dict):
                        continue
                    author = authorship.get("author")
                    if isinstance(author, dict) and isinstance(author.get("display_name"), str):
                        names.append(author["display_name"])
            location = entry.get("primary_location")
            landing = location.get("landing_page_url") if isinstance(location, dict) else None
            url = entry.get("doi") or landing or entry.get("id") or ""
            year = entry.get("publication_year")
            items.append(
                _Item(
                    title=" ".join(title.split()),
                    year=str(year) if isinstance(year, int) and not isinstance(year, bool) else "",
                    authors=_author_line(names),
                    url=url if isinstance(url, str) else "",
                    summary=_inverted_abstract(entry.get("abstract_inverted_index")),
                )
            )
        return items

    async def _arxiv(self, query: str, context: ToolContext) -> list[_Item]:
        cleaned = query.replace('"', " ").strip()
        params: dict[str, object] = {
            "search_query": f'all:"{cleaned}"',
            "start": 0,
            "max_results": self._max_results,
        }
        response = await self._fetch(_ARXIV_ENDPOINT, params, context)
        if not 200 <= response.status < 300:
            raise _ProviderError(f"arxiv returned HTTP {response.status}")
        parser = etree.XMLParser(resolve_entities=False, no_network=True, recover=True, huge_tree=False)
        try:
            root = etree.fromstring(response.body, parser=parser)
        except etree.XMLSyntaxError as exc:
            raise _ProviderError("arxiv payload is not XML") from exc
        if root is None:
            raise _ProviderError("arxiv payload is not XML")
        items: list[_Item] = []
        for entry in root.findall(f"{_ATOM}entry"):
            title = " ".join((entry.findtext(f"{_ATOM}title") or "").split())
            if not title:
                continue
            names = [
                " ".join((node.text or "").split()) for node in entry.findall(f"{_ATOM}author/{_ATOM}name") if node.text
            ]
            published = entry.findtext(f"{_ATOM}published") or ""
            items.append(
                _Item(
                    title=title,
                    year=published[:4] if published[:4].isdigit() else "",
                    authors=_author_line(names),
                    url=(entry.findtext(f"{_ATOM}id") or "").strip(),
                    summary=" ".join((entry.findtext(f"{_ATOM}summary") or "").split())[:_MAX_ABSTRACT],
                )
            )
        return items
