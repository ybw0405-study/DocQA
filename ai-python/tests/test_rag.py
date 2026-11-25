from __future__ import annotations

import asyncio
import uuid

import pytest

from docqa_ai.governance.budget import Deadline
from docqa_ai.protocol.models import DocumentScope
from docqa_ai.providers.embeddings import (
    EmbeddingProvider,
    EmbeddingTimeout,
    EmbeddingUnavailable,
    OfflineEmbeddingProvider,
)
from docqa_ai.rag.repository import (
    ChunkRecord,
    InMemoryVectorRepository,
    PgVectorRepository,
    UnavailableVectorRepository,
    VectorStoreError,
    VectorStoreUnavailable,
    bounded_score,
)
from docqa_ai.rag.service import RetrievalDeadlineExceeded, RetrievalService, RetrievalUnavailable
from tests.conftest import DOCUMENT_ID

OTHER_DOCUMENT = uuid.UUID("44444444-4444-4444-8444-444444444444")


def scope(document_id: uuid.UUID = DOCUMENT_ID, version: str = "v1") -> DocumentScope:
    return DocumentScope(document_id=document_id, index_version=version)


async def record(
    embedder: EmbeddingProvider,
    document_id: uuid.UUID,
    content: str,
    *,
    index: int = 0,
    version: str = "v1",
) -> ChunkRecord:
    vector = (await embedder.embed([content], 1.0))[0]
    return ChunkRecord(
        vector_id=uuid.uuid4(),
        document_id=document_id,
        chunk_index=index,
        content=content,
        embedding=tuple(vector),
        metadata={"index_version": version},
    )


async def test_retrieval_only_returns_authorized_document_scopes() -> None:
    embedder = OfflineEmbeddingProvider()
    repository = InMemoryVectorRepository(
        [
            await record(embedder, DOCUMENT_ID, "alpha"),
            await record(embedder, OTHER_DOCUMENT, "alpha"),
        ]
    )
    service = RetrievalService(repository, embedder, top_k=6)
    result = await service.retrieve("alpha", [scope()])
    assert len(result.chunks) == 1
    assert result.chunks[0].document_id == DOCUMENT_ID
    assert result.chunks[0].score == pytest.approx(1.0)


async def test_retrieval_filters_by_index_version() -> None:
    embedder = OfflineEmbeddingProvider()
    repository = InMemoryVectorRepository([await record(embedder, DOCUMENT_ID, "alpha", version="v2")])
    service = RetrievalService(repository, embedder)
    assert (await service.retrieve("alpha", [scope(version="v1")])).chunks == ()


async def test_retrieval_applies_min_score_and_sorting() -> None:
    embedder = OfflineEmbeddingProvider()
    repository = InMemoryVectorRepository(
        [
            await record(embedder, DOCUMENT_ID, "alpha", index=0),
            await record(embedder, DOCUMENT_ID, "完全无关的内容", index=1),
        ]
    )
    service = RetrievalService(repository, embedder, min_score=0.9)
    result = await service.retrieve("alpha", [scope()])
    assert [chunk.chunk_index for chunk in result.chunks] == [0]
    assert result.top_score >= 0.9


async def test_retrieval_masks_pii_before_returning_content() -> None:
    embedder = OfflineEmbeddingProvider()
    content = "联系 13800138000 或 a@b.com"
    repository = InMemoryVectorRepository([await record(embedder, DOCUMENT_ID, content)])
    service = RetrievalService(repository, embedder, min_score=0.0)
    result = await service.retrieve("联系", [scope()])
    assert result.masked_chunks == 1
    assert result.pii_categories == ("EMAIL", "PHONE")
    assert "13800138000" not in result.chunks[0].content
    assert "a@b.com" not in result.chunks[0].content
    assert "[PHONE]" in result.chunks[0].content


async def test_retrieval_empty_scopes_short_circuits() -> None:
    class ExplodingEmbedder:
        model = "exploding"
        dimensions = 1024

        async def embed(self, texts: list[str], timeout_s: float) -> list[list[float]]:
            raise AssertionError("must not be called")

    service = RetrievalService(InMemoryVectorRepository(), ExplodingEmbedder())
    result = await service.retrieve("question", [])
    assert result.chunks == ()


async def test_retrieval_deadline_is_enforced_before_embedding() -> None:
    now = [0.0]
    deadline = Deadline(100, clock=lambda: now[0])
    now[0] = 0.2
    service = RetrievalService(InMemoryVectorRepository(), OfflineEmbeddingProvider())
    with pytest.raises(RetrievalDeadlineExceeded):
        await service.retrieve("alpha", [scope()], deadline=deadline)


async def test_hanging_embedding_is_cut_at_the_remaining_deadline() -> None:
    class HangingEmbedder:
        model = "hanging"
        dimensions = 1024

        async def embed(self, texts: list[str], timeout_s: float) -> list[list[float]]:
            await asyncio.sleep(5)
            return [[0.0] * 1024 for _ in texts]

    service = RetrievalService(InMemoryVectorRepository(), HangingEmbedder())
    with pytest.raises(RetrievalDeadlineExceeded):
        await service.retrieve("alpha", [scope()], deadline=Deadline(100))


async def test_embedding_timeout_is_mapped_to_unavailable() -> None:
    class TimingOutEmbedder:
        model = "timeout"
        dimensions = 1024

        async def embed(self, texts: list[str], timeout_s: float) -> list[list[float]]:
            raise EmbeddingTimeout("timeout")

    service = RetrievalService(InMemoryVectorRepository(), TimingOutEmbedder())
    with pytest.raises(RetrievalUnavailable):
        await service.retrieve("alpha", [scope()])


async def test_embedding_failure_is_mapped_to_unavailable() -> None:
    class FailingEmbedder:
        model = "failing"
        dimensions = 1024

        async def embed(self, texts: list[str], timeout_s: float) -> list[list[float]]:
            raise EmbeddingUnavailable("down")

    service = RetrievalService(InMemoryVectorRepository(), FailingEmbedder())
    with pytest.raises(RetrievalUnavailable):
        await service.retrieve("alpha", [scope()])


async def test_embedding_dimension_mismatch_is_rejected() -> None:
    class ShortEmbedder:
        model = "short"
        dimensions = 2

        async def embed(self, texts: list[str], timeout_s: float) -> list[list[float]]:
            return [[0.1, 0.2] for _ in texts]

    service = RetrievalService(InMemoryVectorRepository(), ShortEmbedder())
    with pytest.raises(RetrievalUnavailable):
        await service.retrieve("alpha", [scope()])


async def test_unconfigured_vector_store_fails_closed() -> None:
    service = RetrievalService(UnavailableVectorRepository(), OfflineEmbeddingProvider())
    with pytest.raises(RetrievalUnavailable):
        await service.retrieve("alpha", [scope()])


async def test_vector_store_error_is_wrapped() -> None:
    class BrokenRepository:
        async def search(self, embedding: object, scopes: object, top_k: int) -> list[object]:
            raise VectorStoreUnavailable("broken")

    service = RetrievalService(BrokenRepository(), OfflineEmbeddingProvider()) # type: ignore[arg-type]
    with pytest.raises(RetrievalUnavailable):
        await service.retrieve("alpha", [scope()])


def test_bounded_score_still_clamps_but_refuses_non_finite_values() -> None:
    assert bounded_score(1.4) == 1.0
    assert bounded_score(-0.3) == 0.0
    for broken in (float("nan"), float("inf"), float("-inf")):
        with pytest.raises(VectorStoreError):
            bounded_score(broken)


async def test_pg_vector_search_refuses_a_zero_magnitude_query_before_connecting() -> None:
    repository = PgVectorRepository(
        dsn="postgresql://nobody@127.0.0.1:1/nowhere",
        table="chunk_embedding_1024",
        dimensions=4,
    )
    with pytest.raises(VectorStoreError, match="zero magnitude"):
        await repository.search([0.0, 0.0, 0.0, 0.0], [scope()], 6)


async def test_non_finite_similarity_fails_the_retrieval_instead_of_reporting_confidence_one() -> None:
    embedder = OfflineEmbeddingProvider()
    broken = ChunkRecord(
        vector_id=uuid.uuid4(),
        document_id=DOCUMENT_ID,
        chunk_index=0,
        content="alpha",
        embedding=tuple([float("nan")] * embedder.dimensions),
        metadata={"index_version": "v1"},
    )
    service = RetrievalService(InMemoryVectorRepository([broken]), embedder, top_k=6)
    with pytest.raises(RetrievalUnavailable):
        await service.retrieve("alpha", [scope()])
