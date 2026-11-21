from __future__ import annotations

from docqa_ai.agent.errors import DeadlineExceeded
from docqa_ai.pii.masker import mask_text
from docqa_ai.rag.service import RetrievalDeadlineExceeded, RetrievalUnavailable
from docqa_ai.tools.base import ToolContext, ToolError, ToolResult

_MAX_QUERY = 200
_MAX_CONTENT = 300
_MAX_TOTAL = 1500


class DocumentSearchTool:
    """Searches only the document scopes authorized by agent-gateway."""

    name = "document_search"
    source = "builtin"
    description = "在本次请求已授权的学习资料中检索相关片段"
    input_hint = "检索关键词，最多 200 字符"

    def __init__(self, max_results: int = 3) -> None:
        self._max_results = max_results

    async def run(self, argument: str, context: ToolContext) -> ToolResult:
        query = argument.strip()
        if not query or len(query) > _MAX_QUERY:
            raise ToolError("document_search input is invalid")
        masked = mask_text(query)
        try:
            result = await context.retrieval.retrieve(
                masked.masked,
                context.scopes,
                deadline=context.deadline,
                top_k=self._max_results,
                tenant_id=context.tenant_id,
            )
        except RetrievalDeadlineExceeded as exc:
            raise DeadlineExceeded("deadline expired during document search") from exc
        except RetrievalUnavailable as exc:
            raise ToolError("document search is unavailable") from exc
        if not result.chunks:
            return ToolResult(
                observation="未在已授权资料中找到相关内容。",
                masked_count=masked.count,
                pii_categories=masked.categories,
            )
        lines = [
            f"[{index + 1}] score={chunk.score:.4f} {chunk.content[:_MAX_CONTENT]}"
            for index, chunk in enumerate(result.chunks)
        ]
        observation = ("检索结果：\n" + "\n".join(lines))[:_MAX_TOTAL]
        categories = tuple(sorted({*masked.categories, *result.pii_categories}))
        return ToolResult(
            observation=observation,
            masked_count=masked.count + result.masked_chunks,
            pii_categories=categories,
        )
