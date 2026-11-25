from __future__ import annotations

import uuid
from datetime import UTC, datetime

import pytest

from docqa_ai.agent.errors import DeadlineExceeded
from docqa_ai.governance.budget import Deadline
from docqa_ai.protocol.models import DocumentScope
from docqa_ai.providers.embeddings import OfflineEmbeddingProvider
from docqa_ai.rag.repository import ChunkRecord, InMemoryVectorRepository, VectorStoreUnavailable
from docqa_ai.rag.service import RetrievalDeadlineExceeded, RetrievalService
from docqa_ai.tools.base import ToolContext, ToolError
from docqa_ai.tools.calculator import CalculatorTool
from docqa_ai.tools.current_time import CurrentTimeTool
from docqa_ai.tools.document_search import DocumentSearchTool
from docqa_ai.tools.registry import ToolRegistry
from tests.conftest import DOCUMENT_ID


def context(retrieval: RetrievalService | None = None) -> ToolContext:
    return ToolContext(
        scopes=(DocumentScope(document_id=DOCUMENT_ID, index_version="v1"),),
        retrieval=retrieval or RetrievalService(InMemoryVectorRepository(), OfflineEmbeddingProvider()),
        deadline=Deadline(1000),
    )


@pytest.mark.parametrize(
    ("expression", "expected"),
    [
        ("2+3*4", "14"),
        ("(2+3)*4", "20"),
        ("-5 + 2", "-3"),
        ("7 / 2", "3.5"),
        ("7 // 2", "3"),
        ("7 % 2", "1"),
    ],
)
async def test_calculator_evaluates_whitelisted_arithmetic(expression: str, expected: str) -> None:
    result = await CalculatorTool().run(expression, context())
    assert result.observation == f"计算结果是 {expected}"


@pytest.mark.parametrize(
    "expression",
    [
        "__import__('os')",
        "abs(-1)",
        "a + 1",
        "lambda: 1",
        "2 ** 13",
        "1 / 0",
        "1" * 201,
        "1e999",
        "",
        "open('x')",
    ],
)
async def test_calculator_rejects_non_arithmetic_input(expression: str) -> None:
    with pytest.raises(ToolError):
        await CalculatorTool().run(expression, context())


async def test_current_time_is_utc_and_rejects_input() -> None:
    tool = CurrentTimeTool(clock=lambda: datetime(2026, 10, 27, 8, 0, tzinfo=UTC))
    result = await tool.run("", context())
    assert result.observation == "当前时间（UTC）：2026-10-27T08:00:00+00:00"
    with pytest.raises(ToolError):
        await tool.run("tomorrow", context())


async def document_search_service(content: str = "alpha beta gamma") -> RetrievalService:
    embedder = OfflineEmbeddingProvider()
    vector = (await embedder.embed([content], 1.0))[0]
    repository = InMemoryVectorRepository(
        [
            ChunkRecord(
                vector_id=uuid.uuid4(),
                document_id=DOCUMENT_ID,
                chunk_index=0,
                content=content,
                embedding=tuple(vector),
                metadata={"index_version": "v1"},
            )
        ]
    )
    return RetrievalService(repository, embedder, min_score=0.0)


async def test_document_search_returns_only_retrieved_content() -> None:
    service = await document_search_service()
    result = await DocumentSearchTool(max_results=3).run("alpha", context(service))
    assert "检索结果" in result.observation
    assert "alpha beta gamma" in result.observation
    assert result.masked_count == 0


async def test_document_search_masks_query_and_chunk_content() -> None:
    service = await document_search_service("联系 13800138000 获取 alpha")
    result = await DocumentSearchTool().run("a@b.com alpha", context(service))
    assert "a@b.com" not in result.observation
    assert "13800138000" not in result.observation
    assert result.masked_count >= 2
    assert set(result.pii_categories) == {"EMAIL", "PHONE"}


async def test_document_search_rejects_invalid_input() -> None:
    tool = DocumentSearchTool()
    with pytest.raises(ToolError):
        await tool.run("   ", context())
    with pytest.raises(ToolError):
        await tool.run("x" * 201, context())


async def test_document_search_maps_retrieval_deadline() -> None:
    class DeadlineRepository:
        async def search(self, embedding: object, scopes: object, top_k: int) -> list[object]:
            raise RetrievalDeadlineExceeded("late")

    service = RetrievalService(DeadlineRepository(), OfflineEmbeddingProvider())  # type: ignore[arg-type]
    with pytest.raises(DeadlineExceeded):
        await DocumentSearchTool().run("alpha", context(service))


async def test_document_search_maps_unavailable_retrieval() -> None:
    class BrokenRepository:
        async def search(self, embedding: object, scopes: object, top_k: int) -> list[object]:
            raise VectorStoreUnavailable("down")

    service = RetrievalService(BrokenRepository(), OfflineEmbeddingProvider())  # type: ignore[arg-type]
    with pytest.raises(ToolError):
        await DocumentSearchTool().run("alpha", context(service))


def test_registry_resolves_known_tools_in_order_without_duplicates() -> None:
    calculator = CalculatorTool()
    clock = CurrentTimeTool()
    search = DocumentSearchTool()
    registry = ToolRegistry([calculator, clock, search])
    assert registry.names() == ("calculator", "current_time", "document_search")
    assert registry.resolve(["current_time", "missing", "calculator", "current_time"]) == (clock, calculator)
    assert registry.get("missing") is None
    assert registry.source("calculator") == "builtin"
    assert registry.source("missing") is None


def test_registry_specs_carry_source_and_metadata_in_allowed_order() -> None:
    registry = ToolRegistry([CalculatorTool(), CurrentTimeTool()])
    specs = registry.specs(["current_time", "missing", "calculator"])
    assert [spec.name for spec in specs] == ["current_time", "calculator"]
    assert {spec.source for spec in specs} == {"builtin"}
    assert all(spec.description and spec.input_hint for spec in specs)
