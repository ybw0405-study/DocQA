from __future__ import annotations

import uuid
from collections.abc import Callable, Sequence

import httpx

from docqa_ai.governance.budget import Deadline
from docqa_ai.protocol.models import DocumentScope
from docqa_ai.providers.embeddings import OfflineEmbeddingProvider
from docqa_ai.rag.repository import InMemoryVectorRepository
from docqa_ai.rag.service import RetrievalService
from docqa_ai.tools.base import ToolContext
from docqa_ai.tools.web.client import BoundedHttpClient

PUBLIC_ADDRESS = "93.184.216.34"


async def public_resolver(host: str, port: int) -> Sequence[str]:
    return (PUBLIC_ADDRESS,)


async def private_resolver(host: str, port: int) -> Sequence[str]:
    return ("10.0.0.7",)


def deadline_clock(start: float = 0.0) -> tuple[Callable[[], float], list[float]]:
    now = [start]
    return (lambda: now[0]), now


def tool_context(deadline_ms: int = 5000) -> ToolContext:
    return ToolContext(
        scopes=(DocumentScope(document_id=uuid.UUID("00000000-0000-0000-0000-000000000001"), index_version="v1"),),
        retrieval=RetrievalService(InMemoryVectorRepository(), OfflineEmbeddingProvider()),
        deadline=Deadline(deadline_ms),
    )


def make_client(
    handler: Callable[[httpx.Request], httpx.Response],
    **overrides: object,
) -> BoundedHttpClient:
    options: dict[str, object] = {
        "transport": httpx.MockTransport(handler),
        "resolver": public_resolver,
        "timeout_s": 2.0,
    }
    options.update(overrides)
    return BoundedHttpClient(**options)  # type: ignore[arg-type]


def html_response(body: str, *, status: int = 200) -> httpx.Response:
    return httpx.Response(status, headers={"content-type": "text/html; charset=utf-8"}, text=body)


def json_response(payload: object, *, status: int = 200) -> httpx.Response:
    return httpx.Response(status, headers={"content-type": "application/json"}, json=payload)
