from __future__ import annotations

import asyncio
import dataclasses
import logging
import uuid
from collections.abc import Callable, Sequence

import pytest
from opentelemetry.sdk.trace.export.in_memory_span_exporter import InMemorySpanExporter

from docqa_ai.api.main import build_reranker
from docqa_ai.governance.budget import BudgetUnavailable, Deadline
from docqa_ai.governance.experiment_budget import InMemoryExperimentBudgetStore
from docqa_ai.governance.experiment_egress import BudgetedEmbeddingProvider, BudgetedRerankProvider
from docqa_ai.protocol.models import DocumentScope
from docqa_ai.providers.embeddings import OfflineEmbeddingProvider
from docqa_ai.providers.rerank import (
    NoopRerank,
    RerankedItem,
    RerankOutputInvalid,
    RerankUnavailable,
)
from docqa_ai.rag.lexical_repository import (
    LexicalHit,
    LexicalStoreError,
    LexicalStoreUnavailable,
)
from docqa_ai.rag.repository import VectorHit, VectorStoreUnavailable
from docqa_ai.rag.service import (
    RetrievalService,
    RetrievalUnavailable,
    lexical_coverage,
    reciprocal_rank_fusion,
)
from docqa_ai.tracing import build_tracing
from tests.conftest import DOCUMENT_ID, make_settings

OTHER_DOCUMENT = uuid.UUID("44444444-4444-4444-8444-444444444444")


def scope(document_id: uuid.UUID = DOCUMENT_ID, version: str = "v1") -> DocumentScope:
    return DocumentScope(document_id=document_id, index_version=version)


def vector_hit(chunk_index: int, content: str, score: float, document_id: uuid.UUID = DOCUMENT_ID) -> VectorHit:
    return VectorHit(
        document_id=document_id,
        chunk_index=chunk_index,
        content=content,
        score=score,
        metadata={"index_version": "v1"},
    )


def lexical_hit(chunk_index: int, content: str, document_id: uuid.UUID = DOCUMENT_ID) -> LexicalHit:
    return LexicalHit(
        document_id=document_id,
        chunk_index=chunk_index,
        content=content,
        metadata={"index_version": "v1"},
    )


@dataclasses.dataclass
class VectorCall:
    scopes: tuple[DocumentScope, ...]
    limit: int


@dataclasses.dataclass
class LexicalCall:
    query: str
    scopes: tuple[DocumentScope, ...]
    limit: int


class RecordingVectorRepository:
    def __init__(self, hits: Sequence[VectorHit]) -> None:
        self._hits = list(hits)
        self.calls: list[VectorCall] = []

    async def search(self, embedding: object, scopes: Sequence[DocumentScope], top_k: int) -> list[VectorHit]:
        self.calls.append(VectorCall(tuple(scopes), top_k))
        return list(self._hits)


class RecordingLexicalRepository:
    def __init__(self, hits: Sequence[LexicalHit]) -> None:
        self._hits = list(hits)
        self.calls: list[LexicalCall] = []

    async def search(self, lexical_query: str, scopes: Sequence[DocumentScope], limit: int) -> list[LexicalHit]:
        self.calls.append(LexicalCall(lexical_query, tuple(scopes), limit))
        return list(self._hits)


class FailingLexicalRepository:
    def __init__(self, error: Exception | None = None) -> None:
        self.error = error or LexicalStoreUnavailable("lexical index is down")
        self.calls = 0

    async def search(self, lexical_query: str, scopes: Sequence[DocumentScope], limit: int) -> list[LexicalHit]:
        self.calls += 1
        raise self.error


class ExplodingLexicalRepository:
    async def search(self, lexical_query: str, scopes: Sequence[DocumentScope], limit: int) -> list[LexicalHit]:
        raise AssertionError("the lexical channel must not run")


class SlowLexicalRepository:
    async def search(self, lexical_query: str, scopes: Sequence[DocumentScope], limit: int) -> list[LexicalHit]:
        await asyncio.sleep(0.5)
        return []


@dataclasses.dataclass
class RerankCall:
    query: str
    documents: tuple[str, ...]


class ScriptedRerankProvider:
    """按脚本给出名次的第三方替身。

    `calls` 记录的是**实收文本**，它是 D-116（外发只有掩码后正文）与 D-104（名次由第三方决定、
    分数不由它决定）两条判据的载体；只断言返回次序的话，这两件事都可以悄悄破口。
    """

    model = "scripted-rerank"

    def __init__(
        self,
        strategy: Callable[[int], list[RerankedItem]] | None = None,
        error: Exception | None = None,
    ) -> None:
        self._strategy = strategy
        self._error = error
        self.calls: list[RerankCall] = []

    async def rerank(self, query: str, documents: list[str], timeout_s: float) -> list[RerankedItem]:
        self.calls.append(RerankCall(query, tuple(documents)))
        if self._error is not None:
            raise self._error
        if self._strategy is not None:
            return self._strategy(len(documents))
        return [RerankedItem(index, 0.5) for index in reversed(range(len(documents)))]


class ExplodingRerankProvider:
    model = "must-not-run"

    async def rerank(self, query: str, documents: list[str], timeout_s: float) -> list[RerankedItem]:
        raise AssertionError("no third-party rerank call may leave the process here")


class ClockAdvancingVectorRepository(RecordingVectorRepository):
    """向量查询吃掉大部分预算，把剩余时间压到词法通道必然超时的量级（假时钟不随真实时间前进）。"""

    def __init__(self, hits: Sequence[VectorHit], clock: list[float], advance_to: float) -> None:
        super().__init__(hits)
        self._clock = clock
        self._advance_to = advance_to

    async def search(self, embedding: object, scopes: Sequence[DocumentScope], top_k: int) -> list[VectorHit]:
        hits = await super().search(embedding, scopes, top_k)
        self._clock[0] = self._advance_to
        return hits


def service(
    vector_repository: object,
    lexical_repository: object | None = None,
    **overrides: object,
) -> RetrievalService:
    options: dict[str, object] = {"top_k": 6, "min_score": 0.0}
    options.update(overrides)
    return RetrievalService(
        vector_repository,  # type: ignore[arg-type]
        OfflineEmbeddingProvider(),
        lexical_repository=lexical_repository,  # type: ignore[arg-type]
        **options,  # type: ignore[arg-type]
    )


async def test_lexical_only_chunk_enters_the_result_with_a_zero_score() -> None:
    """只被词法命中、向量没给的块保留进上下文，但相似度未知就写 0，不伪造证据（D-84）。"""

    vector = RecordingVectorRepository([vector_hit(0, "学习方法甲", 0.9)])
    lexical = RecordingLexicalRepository([lexical_hit(3, "学习方法乙")])
    result = await service(vector, lexical).retrieve("学习方法", [scope()])
    scores = {chunk.chunk_index: chunk.score for chunk in result.chunks}
    assert scores[3] == 0.0
    assert result.top_score == 0.9


async def test_fusion_ranks_by_rrf_then_coverage_rerank_can_move_a_lexical_only_chunk_first() -> None:
    """融合只看名次（RRF），重排只看覆盖度；两者都不产生相似度（D-82/D-85/D-84）。"""

    vector = RecordingVectorRepository(
        [
            vector_hit(0, "学习很要紧", 0.95),
            vector_hit(1, "练习方法乙", 0.8),
        ]
    )
    lexical = RecordingLexicalRepository([lexical_hit(3, "学习方法丙")])
    result = await service(vector, lexical).retrieve("学习方法", [scope()])
    assert [chunk.chunk_index for chunk in result.chunks] == [3, 1, 0]
    assert [chunk.score for chunk in result.chunks] == [0.0, 0.8, 0.95]
    # 排在第一位的是纯词法命中，confidence 口径仍取向量侧最高相似度（D-83）
    assert result.top_score == 0.95
    assert lexical.calls[0].query == "'学' <-> '习' | '习' <-> '方' | '方' <-> '法'"
    # 「练习方法乙」并不含词组「习方」，但跨词邻接让它字面命中，覆盖度升到 2/3 并压过 1/3 的 0 号块：
    # 这就是 D-81 登记的结构性假阳性，重排只整理次序、修不掉它，根治归 S6 的真分词器
    assert lexical_coverage("练习方法乙", ("学习", "习方", "方法")) == pytest.approx(2 / 3)
    assert lexical_coverage("学习很要紧", ("学习", "习方", "方法")) == pytest.approx(1 / 3)


async def test_both_channels_receive_the_same_authorization_scope_and_limit() -> None:
    """词法通道天然比向量通道宽，同一条授权谓词是必须而不是便利（D-87 红线、§7.2）。"""

    scopes = [scope(), scope(OTHER_DOCUMENT, "v2")]
    vector = RecordingVectorRepository([vector_hit(0, "学习方法甲", 0.9)])
    lexical = RecordingLexicalRepository([lexical_hit(1, "学习方法乙")])
    await service(vector, lexical, lexical_candidate_limit=40).retrieve("学习方法", scopes)
    assert [call.scopes for call in vector.calls] == [tuple(scopes)]
    assert [call.scopes for call in lexical.calls] == [tuple(scopes)]
    assert vector.calls[0].limit == 40
    assert lexical.calls[0].limit == 40


async def test_candidate_limit_is_raised_by_the_three_fold_floor() -> None:
    """候选集取 max(输出上限*3, 词法候选上限)，保证融合至少有 3 倍候选可排（D-91）。"""

    vector = RecordingVectorRepository([vector_hit(0, "学习方法甲", 0.9)])
    lexical = RecordingLexicalRepository([])
    await service(vector, lexical, top_k=6, lexical_candidate_limit=5).retrieve("学习方法", [scope()])
    assert vector.calls[0].limit == 18
    assert lexical.calls[0].limit == 18


async def test_candidate_limit_is_the_per_channel_cap_while_top_k_is_the_output_cap() -> None:
    """top_k 的语义已从「单通道取回上限」改为「最终输出上限」，这是最容易写错的一处（D-91）。"""

    vector = RecordingVectorRepository([vector_hit(index, f"学习方法{index}", 0.9 - index / 100) for index in range(8)])
    lexical = RecordingLexicalRepository([lexical_hit(index, f"学习方法{index}") for index in range(8)])
    result = await service(vector, lexical, top_k=6).retrieve("学习方法", [scope()], top_k=2)
    assert vector.calls[0].limit == 50
    assert lexical.calls[0].limit == 50
    assert len(result.chunks) == 2
    assert [chunk.index for chunk in result.chunks] == [0, 1]


async def test_requested_top_k_is_still_clamped_by_the_service_ceiling() -> None:
    vector = RecordingVectorRepository([vector_hit(index, f"学习方法{index}", 0.9) for index in range(10)])
    lexical = RecordingLexicalRepository([])
    result = await service(vector, lexical, top_k=6).retrieve("学习方法", [scope()], top_k=99)
    assert len(result.chunks) == 6


async def test_min_score_drops_vector_rank_but_does_not_block_lexical_recall() -> None:
    """min_score 是余弦语义的门槛，只作用于向量名次；词法通道补召回不受它约束（D-83/D-88）。"""

    vector = RecordingVectorRepository([vector_hit(0, "学习方法甲", 0.2)])
    lexical = RecordingLexicalRepository([lexical_hit(0, "学习方法甲")])
    result = await service(vector, lexical, min_score=0.9).retrieve("学习方法", [scope()])
    assert [chunk.chunk_index for chunk in result.chunks] == [0]
    assert result.chunks[0].score == 0.0
    assert result.top_score == 0.0


async def test_short_query_skips_the_lexical_channel_without_marking_a_fallback() -> None:
    """单字查询走词法就是洪水形态，跳过是前置条件而不是优化项（D-97(b)、§7.4）。"""

    vector = RecordingVectorRepository([vector_hit(0, "学习方法甲", 0.9)])
    lexical = ExplodingLexicalRepository()
    result = await service(vector, lexical, lexical_min_query_chars=2).retrieve("学", [scope()])
    assert [chunk.chunk_index for chunk in result.chunks] == [0]
    assert result.lexical_fallback is False


async def test_lexical_failure_degrades_to_vector_only_and_marks_the_fallback() -> None:
    """词法是增量能力，它挂掉时系统行为等于 S5 之前，但退化必须在观测上可见（D-88）。"""

    vector = RecordingVectorRepository([vector_hit(0, "学习方法甲", 0.9)])
    lexical = FailingLexicalRepository()
    result = await service(vector, lexical).retrieve("学习方法", [scope()])
    assert [chunk.chunk_index for chunk in result.chunks] == [0]
    assert result.lexical_fallback is True
    assert lexical.calls == 1


@pytest.mark.parametrize(
    "error",
    [LexicalStoreUnavailable("down"), LexicalStoreError("shape"), TimeoutError("slow")],
)
async def test_every_declared_lexical_failure_mode_degrades(error: Exception) -> None:
    vector = RecordingVectorRepository([vector_hit(0, "学习方法甲", 0.9)])
    lexical = FailingLexicalRepository(error)
    result = await service(vector, lexical).retrieve("学习方法", [scope()])
    assert result.lexical_fallback is True
    assert len(result.chunks) == 1


async def test_slow_lexical_query_is_cut_at_the_deadline_and_degrades() -> None:
    now = [0.0]
    deadline = Deadline(2000, clock=lambda: now[0])
    vector = ClockAdvancingVectorRepository([vector_hit(0, "学习方法甲", 0.9)], now, 1.8)
    result = await service(vector, SlowLexicalRepository()).retrieve("学习方法", [scope()], deadline=deadline)
    assert result.lexical_fallback is True
    assert [chunk.chunk_index for chunk in result.chunks] == [0]


async def test_expired_deadline_before_the_lexical_query_skips_it_as_a_fallback() -> None:
    now = [0.0]
    deadline = Deadline(2000, clock=lambda: now[0])
    vector = ClockAdvancingVectorRepository([vector_hit(0, "学习方法甲", 0.9)], now, 2.5)
    lexical = ExplodingLexicalRepository()
    result = await service(vector, lexical).retrieve("学习方法", [scope()], deadline=deadline)
    assert result.lexical_fallback is True
    assert len(result.chunks) == 1


async def test_vector_failure_still_fails_closed_while_lexical_is_healthy() -> None:
    """向量不可用仍是 fail-closed，词法通道不能把它救成「检索成功」（D-88 ①）。"""

    class BrokenVectorRepository:
        async def search(self, embedding: object, scopes: Sequence[DocumentScope], top_k: int) -> list[VectorHit]:
            raise VectorStoreUnavailable("down")

    lexical = RecordingLexicalRepository([lexical_hit(0, "学习方法甲")])
    with pytest.raises(RetrievalUnavailable):
        await service(BrokenVectorRepository(), lexical).retrieve("学习方法", [scope()])
    assert lexical.calls == []


async def test_disabled_lexical_recall_never_touches_the_repository() -> None:
    vector = RecordingVectorRepository([vector_hit(0, "学习方法甲", 0.9)])
    lexical = ExplodingLexicalRepository()
    result = await service(vector, lexical, lexical_enabled=False).retrieve("学习方法", [scope()])
    assert result.lexical_fallback is False
    assert len(result.chunks) == 1


async def test_absent_lexical_repository_behaves_exactly_like_the_single_channel_release() -> None:
    vector = RecordingVectorRepository([vector_hit(0, "学习方法甲", 0.9)])
    result = await service(vector, None).retrieve("学习方法", [scope()])
    assert result.lexical_fallback is False
    assert result.chunks[0].score == 0.9


async def test_lexical_only_chunks_are_pii_masked_before_being_returned() -> None:
    """PII 掩码在融合与重排之后对最终候选集执行一次，词法通道不得绕过（§4.4.4 红线）。"""

    vector = RecordingVectorRepository([])
    lexical = RecordingLexicalRepository([lexical_hit(0, "联系电话 13800138000 学习方法")])
    result = await service(vector, lexical).retrieve("学习方法", [scope()])
    assert result.masked_chunks == 1
    assert result.pii_categories == ("PHONE",)
    assert "13800138000" not in result.chunks[0].content
    assert "[PHONE]" in result.chunks[0].content


async def test_empty_scopes_short_circuit_before_any_channel_runs() -> None:
    vector = RecordingVectorRepository([vector_hit(0, "学习方法甲", 0.9)])
    lexical = ExplodingLexicalRepository()
    result = await service(vector, lexical).retrieve("学习方法", [])
    assert result.chunks == ()
    assert vector.calls == []


def test_rrf_accumulates_reciprocal_ranks_from_both_channels() -> None:
    first = (DOCUMENT_ID, 0)
    second = (OTHER_DOCUMENT, 1)
    fused = reciprocal_rank_fusion([first, second], [second], 60)
    assert fused[first] == pytest.approx(1 / 61)
    assert fused[second] == pytest.approx(1 / 62 + 1 / 61)


def test_rrf_ties_are_broken_deterministically_by_document_and_chunk() -> None:
    late = (OTHER_DOCUMENT, 5)
    early = (DOCUMENT_ID, 5)
    fused = reciprocal_rank_fusion([late], [early], 60)
    assert fused[late] == fused[early]
    ordered = sorted(fused, key=lambda key: (-fused[key], str(key[0]), key[1]))
    assert ordered == [early, late]


def test_lexical_coverage_is_a_ratio_over_the_query_surfaces() -> None:
    surfaces = ("学习", "方法")
    assert lexical_coverage("学习有很多种方法", surfaces) == 1.0
    assert lexical_coverage("学习方法", surfaces) == 1.0
    assert lexical_coverage("只谈学习", surfaces) == 0.5
    assert lexical_coverage("完全无关", surfaces) == 0.0
    assert lexical_coverage("任何内容", ()) == 0.0


def test_lexical_coverage_folds_case_on_both_sides_like_the_simple_config() -> None:
    """E38：PG 的 simple 两侧都折叠大小写，Python 侧的覆盖度判定必须同口径。"""

    assert lexical_coverage("见 Config.Py 文件", ("config.py",)) == 1.0


def spans_by_name(exporter: InMemorySpanExporter) -> dict[str, object]:
    return {span.name: span for span in exporter.get_finished_spans()}


async def test_healthy_hybrid_run_writes_the_documented_span_tree() -> None:
    """§7 声明这些属性是排查「为什么这条引用没带来」的唯一抓手，没有回归用例它就只是一份文档。"""

    exporter = InMemorySpanExporter()
    tracer, _provider = build_tracing(make_settings(), exporter=exporter)
    vector = RecordingVectorRepository([vector_hit(0, "学习方法甲", 0.9)])
    lexical = RecordingLexicalRepository([lexical_hit(3, "学习方法乙")])
    await service(vector, lexical, tracer=tracer).retrieve("学习方法", [scope()])

    spans = spans_by_name(exporter)
    assert set(spans) == {"rag.retrieve", "rag.embed", "vector.query", "lexical.query", "retrieval.fuse", "rag.rerank"}
    assert spans["rag.retrieve"].parent is None  # type: ignore[attr-defined]
    assert spans["retrieval.fuse"].parent.span_id == spans["rag.retrieve"].context.span_id  # type: ignore[attr-defined]
    assert spans["lexical.query"].attributes["lexical.enabled"] == "true"  # type: ignore[attr-defined]
    assert spans["lexical.query"].attributes["lexical.phrase_count"] == 3  # type: ignore[attr-defined]
    assert spans["retrieval.fuse"].attributes["fusion_rrf_k"] == 60  # type: ignore[attr-defined]
    assert spans["retrieval.fuse"].attributes["vector.candidate_count"] == 1  # type: ignore[attr-defined]
    assert spans["retrieval.fuse"].attributes["lexical.candidate_count"] == 1  # type: ignore[attr-defined]
    assert spans["rag.rerank"].attributes["reranker"] == "lexical-coverage-v1"  # type: ignore[attr-defined]
    assert spans["rag.rerank"].attributes["retrieval.kept"] == 2  # type: ignore[attr-defined]
    assert "lexical_fallback" not in spans["rag.retrieve"].attributes  # type: ignore[attr-defined]


async def test_degraded_lexical_run_marks_the_fallback_on_both_spans() -> None:
    exporter = InMemorySpanExporter()
    tracer, _provider = build_tracing(make_settings(), exporter=exporter)
    vector = RecordingVectorRepository([vector_hit(0, "学习方法甲", 0.9)])
    result = await service(vector, FailingLexicalRepository(), tracer=tracer).retrieve("学习方法", [scope()])

    spans = spans_by_name(exporter)
    assert result.lexical_fallback is True
    assert spans["lexical.query"].attributes["lexical_fallback"] is True  # type: ignore[attr-defined]
    # 根 span 也要有：排查入口是 rag.retrieve，不是它下面的某一段
    assert spans["rag.retrieve"].attributes["lexical_fallback"] is True  # type: ignore[attr-defined]
    assert spans["rag.retrieve"].attributes["retrieval.chunk_count"] == 1  # type: ignore[attr-defined]


async def test_degraded_lexical_run_logs_structured_fields_without_the_question(caplog) -> None:
    with caplog.at_level(logging.WARNING, logger="docqa_ai.rag.service"):
        vector = RecordingVectorRepository([vector_hit(0, "学习方法甲", 0.9)])
        await service(vector, FailingLexicalRepository()).retrieve("学习方法", [scope()])

    record = next(item for item in caplog.records if item.name == "docqa_ai.rag.service")
    assert record.step == "retrieval"
    assert record.operation == "lexical.query"
    assert record.status == "degraded"
    assert "学习方法" not in record.getMessage()


async def test_rerank_reorders_kept_chunks_without_touching_scores() -> None:
    """D-104 ② 的可执行形态：名次由第三方决定，`score` 一个字节都不动。

    若实现把 `relevance_score` 写进 `score`，下面的分数断言会读成第三方给的数，
    而 Java 侧的 0..1 校验照样通过——所以这条只能在这里钉住，不能指望下游兜。
    """

    vector = RecordingVectorRepository(
        [
            vector_hit(0, "学习方法甲", 0.9),
            vector_hit(1, "学习方法乙", 0.8),
            vector_hit(2, "学习方法丙", 0.7),
        ]
    )
    reranker = ScriptedRerankProvider()
    result = await service(vector, RecordingLexicalRepository([]), reranker=reranker).retrieve("学习方法", [scope()])
    assert [chunk.chunk_index for chunk in result.chunks] == [2, 1, 0]
    assert [chunk.score for chunk in result.chunks] == [0.7, 0.8, 0.9]
    assert [chunk.index for chunk in result.chunks] == [0, 1, 2]
    assert result.top_score == 0.9
    assert result.rerank_fallback is False


async def test_noop_rerank_leaves_the_result_identical_to_a_run_without_rerank() -> None:
    """D-113 的离线不变性：注入 `NoopRerank` 与完全不注入必须逐字段相等，否则「默认不联网」只是空话。"""

    def hits() -> list[VectorHit]:
        return [vector_hit(0, "学习方法甲", 0.9), vector_hit(1, "练习方法乙", 0.8)]

    without = await service(RecordingVectorRepository(hits()), RecordingLexicalRepository([])).retrieve(
        "学习方法", [scope()]
    )
    with_noop = await service(
        RecordingVectorRepository(hits()),
        RecordingLexicalRepository([]),
        reranker=NoopRerank(),
    ).retrieve("学习方法", [scope()])
    assert with_noop == without


async def test_offline_assembly_only_adds_the_three_rerank_span_keys() -> None:
    """S6-3 的观测对账口径：offline 注入的 `NoopRerank` 不改结果，只给 span 加三个**加性**键。

    任何按「属性集合完全相等」写的观测断言都会在这条通道上红；对账必须承认这三个键存在，
    同时不得把 `offline-noop` 读成「rerank 已生效」——它恰恰是「通道未外呼」的证据。
    """

    def hits() -> list[VectorHit]:
        return [vector_hit(0, "学习方法甲", 0.9), vector_hit(1, "练习方法乙", 0.8)]

    exporter = InMemorySpanExporter()
    tracer, _provider = build_tracing(make_settings(), exporter=exporter)
    offline_reranker = build_reranker(make_settings(), None)
    assert isinstance(offline_reranker, NoopRerank)
    with_noop = await service(
        RecordingVectorRepository(hits()),
        RecordingLexicalRepository([]),
        reranker=offline_reranker,
        tracer=tracer,
    ).retrieve("学习方法", [scope()])
    without = await service(RecordingVectorRepository(hits()), RecordingLexicalRepository([])).retrieve(
        "学习方法", [scope()]
    )
    assert with_noop == without

    rerank_span = spans_by_name(exporter)["rag.rerank"]
    kept = len(with_noop.chunks)
    assert rerank_span.attributes["rerank.model"] == "offline-noop"  # type: ignore[attr-defined]
    assert rerank_span.attributes["rerank.document_count"] == kept  # type: ignore[attr-defined]
    assert rerank_span.attributes["rerank.returned"] == kept  # type: ignore[attr-defined]


async def test_rerank_sends_masked_candidates_and_a_masked_query() -> None:
    """D-116：rerank 的入参就是召回正文本身，掩码必须发生在它之前而不是之后。

    查询侧也重掩一次——生产上传进来的已是掩码文本，但外发红线不能依赖调用方自觉。
    """

    vector = RecordingVectorRepository([vector_hit(0, "联系电话 13800138000 学习方法", 0.9)])
    reranker = ScriptedRerankProvider()
    result = await service(vector, RecordingLexicalRepository([]), reranker=reranker).retrieve(
        "我的号码是 13800138000", [scope()]
    )
    assert len(reranker.calls) == 1
    sent_query = reranker.calls[0].query
    sent_documents = reranker.calls[0].documents
    assert "13800138000" not in sent_query
    assert "[PHONE]" in sent_query
    assert "13800138000" not in sent_documents[0]
    assert "[PHONE]" in sent_documents[0]
    assert "13800138000" not in result.chunks[0].content
    assert result.masked_chunks == 1


@pytest.mark.parametrize(
    "error",
    [RerankUnavailable("down"), RerankOutputInvalid("shape"), TimeoutError("slow")],
)
async def test_rerank_failure_degrades_to_local_order_and_marks_the_fallback(error: Exception) -> None:
    """D-103：rerank 挂掉等于退回融合次序，块一个不少，对外响应体一个字段都不变。"""

    def hits() -> list[VectorHit]:
        return [vector_hit(0, "学习方法甲", 0.9), vector_hit(1, "学习方法乙", 0.8)]

    baseline = await service(RecordingVectorRepository(hits()), RecordingLexicalRepository([])).retrieve(
        "学习方法", [scope()]
    )
    reranker = ScriptedRerankProvider(error=error)
    result = await service(
        RecordingVectorRepository(hits()),
        RecordingLexicalRepository([]),
        reranker=reranker,
    ).retrieve("学习方法", [scope()])
    assert [chunk.chunk_index for chunk in result.chunks] == [chunk.chunk_index for chunk in baseline.chunks]
    assert result.rerank_fallback is True
    assert len(reranker.calls) == 1


async def test_expired_deadline_before_rerank_never_calls_the_provider() -> None:
    """预算走完就不能再产生外呼：真实调用按次数记账，一次超时的外呼也是钱。"""

    now = [0.0]
    deadline = Deadline(2000, clock=lambda: now[0])
    vector = ClockAdvancingVectorRepository([vector_hit(0, "学习方法甲", 0.9)], now, 2.5)
    result = await service(
        vector,
        RecordingLexicalRepository([]),
        reranker=ExplodingRerankProvider(),
        rerank_timeout_s=3.0,
    ).retrieve("学习方法", [scope()], deadline=deadline)
    assert result.rerank_fallback is True
    assert [chunk.chunk_index for chunk in result.chunks] == [0]


async def test_partial_rerank_output_reorders_without_dropping_a_chunk() -> None:
    """`top_n` 少于候选数是合法返回，但缺失的块只能让位、不能被第三方删掉。"""

    def strategy(size: int) -> list[RerankedItem]:
        assert size == 3
        return [RerankedItem(2, 0.9), RerankedItem(0, 0.1)]

    vector = RecordingVectorRepository(
        [
            vector_hit(0, "学习方法甲", 0.9),
            vector_hit(1, "学习方法乙", 0.8),
            vector_hit(2, "学习方法丙", 0.7),
        ]
    )
    result = await service(
        vector, RecordingLexicalRepository([]), reranker=ScriptedRerankProvider(strategy=strategy)
    ).retrieve("学习方法", [scope()])
    assert [chunk.chunk_index for chunk in result.chunks] == [2, 0, 1]
    assert result.rerank_fallback is False


@pytest.mark.parametrize(
    "items",
    [[RerankedItem(0, 0.9), RerankedItem(0, 0.5)], [RerankedItem(0, 0.9), RerankedItem(7, 0.5)]],
    ids=["duplicate-index", "out-of-range-index"],
)
async def test_unusable_rerank_indexes_fall_back_to_local_order(items: list[RerankedItem]) -> None:
    """可疑名次一律不采用：宁可退回本地次序，也不按一份对不上号的输出重排引用。"""

    def strategy(size: int) -> list[RerankedItem]:
        assert size == 2
        return items

    vector = RecordingVectorRepository([vector_hit(0, "学习方法甲", 0.9), vector_hit(1, "学习方法乙", 0.8)])
    result = await service(
        vector,
        RecordingLexicalRepository([]),
        reranker=ScriptedRerankProvider(strategy=strategy),
    ).retrieve("学习方法", [scope()])
    assert [chunk.chunk_index for chunk in result.chunks] == [0, 1]
    assert result.rerank_fallback is True


async def test_rerank_writes_its_own_span_attributes_and_degrades_on_both_spans() -> None:
    """「rerank 全失败」与「rerank 没参与」必须可区分（D-103 的观测代价）。"""

    exporter = InMemorySpanExporter()
    tracer, _provider = build_tracing(make_settings(), exporter=exporter)
    reranker = ScriptedRerankProvider(error=RerankUnavailable("down"))
    await service(
        RecordingVectorRepository([vector_hit(0, "学习方法甲", 0.9)]),
        RecordingLexicalRepository([]),
        reranker=reranker,
        tracer=tracer,
    ).retrieve("学习方法", [scope()])

    spans = spans_by_name(exporter)
    rerank_span = spans["rag.rerank"]
    assert rerank_span.attributes["reranker"] == "lexical-coverage-v1"  # type: ignore[attr-defined]
    assert rerank_span.attributes["rerank.model"] == "scripted-rerank"  # type: ignore[attr-defined]
    assert rerank_span.attributes["rerank.document_count"] == 1  # type: ignore[attr-defined]
    assert rerank_span.attributes["rerank_fallback"] is True  # type: ignore[attr-defined]
    assert spans["rag.retrieve"].attributes["rerank_fallback"] is True  # type: ignore[attr-defined]

    healthy_exporter = InMemorySpanExporter()
    healthy_tracer, _healthy_provider = build_tracing(make_settings(), exporter=healthy_exporter)
    await service(
        RecordingVectorRepository([vector_hit(0, "学习方法甲", 0.9)]),
        RecordingLexicalRepository([]),
        reranker=ScriptedRerankProvider(),
        tracer=healthy_tracer,
    ).retrieve("学习方法", [scope()])
    healthy = spans_by_name(healthy_exporter)
    assert healthy["rag.rerank"].attributes["rerank.returned"] == 1  # type: ignore[attr-defined]
    assert "rerank_fallback" not in healthy["rag.retrieve"].attributes  # type: ignore[attr-defined]


async def test_degraded_rerank_logs_structured_fields_without_the_question(caplog) -> None:
    with caplog.at_level(logging.WARNING, logger="docqa_ai.rag.service"):
        reranker = ScriptedRerankProvider(error=RerankUnavailable("down"))
        vector = RecordingVectorRepository([vector_hit(0, "学习方法甲", 0.9)])
        await service(vector, RecordingLexicalRepository([]), reranker=reranker).retrieve("学习方法", [scope()])

    record = next(item for item in caplog.records if getattr(item, "operation", None) == "rerank")
    assert record.levelno == logging.WARNING
    assert record.step == "retrieval"
    assert record.status == "degraded"
    assert "RerankUnavailable" in record.getMessage()
    assert "学习方法" not in record.getMessage()


class UnreachableLedger:
    """Redis 抖动时账本自己抛 `BudgetUnavailable`；它不是 `ExperimentBudgetExhausted`，形态必须分开翻译。"""

    async def snapshot(self):
        raise BudgetUnavailable("redis experiment budget read failed")

    async def authorize_call(self, estimated_tokens: int) -> bool:
        raise BudgetUnavailable("redis experiment budget write failed")

    async def settle_call(self, reserved: int, actual: int):
        raise BudgetUnavailable("redis experiment budget write failed")


@pytest.mark.parametrize(
    ("store", "expected_reason"),
    [
        (InMemoryExperimentBudgetStore(0, 0), "experiment budget exhausted"),
        (UnreachableLedger(), "experiment budget unavailable"),
    ],
    ids=["quota-refused", "ledger-unreachable"],
)
async def test_embedding_gate_failure_keeps_the_existing_retrieval_unavailable(store, expected_reason: str) -> None:
    """D-119 的检索侧残余：请求级预检放行后并发把额度花完，这里退回既有 503，不新增错误码。"""

    embedder = BudgetedEmbeddingProvider(OfflineEmbeddingProvider(), store)
    retrieval = RetrievalService(RecordingVectorRepository([vector_hit(0, "学习方法甲", 0.9)]), embedder)
    with pytest.raises(RetrievalUnavailable, match=expected_reason):
        await retrieval.retrieve("学习方法", [scope()])


@pytest.mark.parametrize(
    "store",
    [InMemoryExperimentBudgetStore(0, 0), UnreachableLedger()],
    ids=["quota-refused", "ledger-unreachable"],
)
async def test_rerank_gate_failure_degrades_to_fused_order_without_egress(store) -> None:
    """名次是增量能力：额度拦下它和它自己挂掉同形——零外呼、块一个不少、响应体一个字段都不变。"""

    def hits() -> list[VectorHit]:
        return [vector_hit(0, "学习方法甲", 0.9), vector_hit(1, "学习方法乙", 0.8)]

    baseline = await service(RecordingVectorRepository(hits()), RecordingLexicalRepository([])).retrieve(
        "学习方法", [scope()]
    )
    inner = ScriptedRerankProvider()
    result = await service(
        RecordingVectorRepository(hits()),
        RecordingLexicalRepository([]),
        reranker=BudgetedRerankProvider(inner, store),
    ).retrieve("学习方法", [scope()])

    assert [chunk.chunk_index for chunk in result.chunks] == [chunk.chunk_index for chunk in baseline.chunks]
    assert result.rerank_fallback is True
    assert inner.calls == []
