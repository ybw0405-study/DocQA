from __future__ import annotations

import logging
import os
from dataclasses import dataclass

from docqa_ai.config import Settings
from docqa_ai.metrics import ToolMetrics
from docqa_ai.tools.academic_search import AcademicSearchTool
from docqa_ai.tools.base import Tool
from docqa_ai.tools.web.client import BoundedHttpClient
from docqa_ai.tools.web_fetch import WebFetchTool
from docqa_ai.tools.web_search import WebSearchTool
from docqa_ai.tools.wikipedia_lookup import WikipediaLookupTool

LOGGER = logging.getLogger(__name__)


@dataclass(frozen=True)
class WebToolset:
    """联网工具与它们共用的受限客户端；关闭时整个对象为 None，结构上零外呼（D-149）。"""

    tools: tuple[Tool, ...]
    client: BoundedHttpClient

    async def aclose(self) -> None:
        await self.client.aclose()


def build_web_toolset(settings: Settings, *, metrics: ToolMetrics | None = None) -> WebToolset | None:
    if not settings.real_tools_enabled:
        return None
    timeout_s = settings.web_fetch_timeout_ms / 1000.0
    client = BoundedHttpClient(
        timeout_s=timeout_s,
        max_bytes=settings.web_fetch_max_bytes,
        allowed_hosts=settings.web_fetch_allowed_hosts,
        robots_ttl_s=float(settings.web_robots_cache_seconds),
        on_cache_hit=metrics.record_cache_hit if metrics is not None else None,
    )
    tools: list[Tool] = [
        WebFetchTool(client, max_chars=settings.web_tool_max_chars, timeout_s=timeout_s),
        AcademicSearchTool(
            client,
            mailto=settings.academic_mailto,
            max_results=settings.web_tool_max_results,
            timeout_s=timeout_s,
        ),
        WikipediaLookupTool(client, default_lang=settings.wikipedia_language, timeout_s=timeout_s),
    ]
    if settings.web_search_provider != "none":
        if settings.web_search_provider == "tavily" and not os.environ.get(settings.tavily_key_env, "").strip():
            # D-149：缺 key 只让搜索工具消失并告警，不让整个服务起不来。
            LOGGER.warning("web_search is disabled: %s is not set", settings.tavily_key_env)
        else:
            tools.append(
                WebSearchTool(
                    client,
                    provider=settings.web_search_provider,
                    searxng_url=settings.searxng_url,
                    tavily_key_env=settings.tavily_key_env,
                    max_results=settings.web_tool_max_results,
                    timeout_s=timeout_s,
                )
            )
    return WebToolset(tuple(tools), client)
