from __future__ import annotations

from dataclasses import dataclass
from typing import Protocol

from docqa_ai.governance.budget import Deadline
from docqa_ai.protocol.models import DocumentScope
from docqa_ai.rag.service import RetrievalService


class ToolError(Exception):
    pass


@dataclass(frozen=True)
class ToolContext:
    scopes: tuple[DocumentScope, ...]
    retrieval: RetrievalService
    deadline: Deadline
    # 请求级租户：document_search 工具二次检索时一并下推到 PG 的 tenant_id 谓词。
    tenant_id: object | None = None


@dataclass(frozen=True)
class ToolResult:
    observation: str
    masked_count: int = 0
    pii_categories: tuple[str, ...] = ()


@dataclass(frozen=True)
class ToolSpec:
    """Prompt-time description of a callable tool. Third-party text is untrusted and bounded upstream."""

    name: str
    source: str
    description: str
    input_hint: str


class Tool(Protocol):
    name: str
    source: str
    description: str
    input_hint: str

    async def run(self, argument: str, context: ToolContext) -> ToolResult: ...
