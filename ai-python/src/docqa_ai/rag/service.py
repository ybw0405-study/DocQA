from __future__ import annotations

import asyncio
import inspect
import logging
import time
import uuid
from collections.abc import Sequence
from dataclasses import dataclass, field
from typing import Any

from opentelemetry import trace
from opentelemetry.trace import Span, Tracer

from docqa_ai.business_metrics import BusinessMetrics
from docqa_ai.governance.budget import BudgetError, Deadline
from docqa_ai.governance.experiment_egress import ExperimentBudgetExhausted
from docqa_ai.logging_setup import log_event
from docqa_ai.pii.masker import MaskResult, mask_text
from docqa_ai.protocol.models import DocumentScope
from docqa_ai.providers.embeddings import (
    EmbeddingError,
    EmbeddingProvider,
    EmbeddingTimeout,
)
from docqa_ai.providers.rerank import RerankedItem, RerankError, RerankProvider
from docqa_ai.rag.lexical_repository import (
    LexicalHit,
    LexicalRepository,
    LexicalStoreError,
)
from docqa_ai.rag.repository import VectorHit, VectorRepository, VectorStoreError
from docqa_ai.text.lexical import build_lexical_query, extract_phrases

LOGGER = logging.getLogger(__name__)

RERANKER_NAME = "lexical-coverage-v1"

ChunkKey = tuple[uuid.UUID, int]


def _accepts_tenant_id(repository: object) -> bool:
    """Duck-typed Protocol：真实数据面实现带 tenant_id 参数，测试替身多为三参 search。

    构造期探测一次，让真实 PG/InMemory 实现拿到租户谓词，同时不强制所有替身同步改签名。
    """
    try:
        parameters = inspect.signature(repository.search).parameters
    except (TypeError, ValueError):
        return False
    return "tenant_id" in parameters or any(
        parameter.kind is inspect.Parameter.VAR_KEYWORD for parameter in parameters.values()
    )


async def _maybe_aclose(resource: object) -> None:
    closer = getattr(resource, "aclose", None)
    if closer is None:
        return
    result = closer()
    if inspect.isawaitable(result):
        await result


class RetrievalError(Exception):
    pass


class RetrievalUnavailable(RetrievalError):
    pass


class RetrievalDeadlineExceeded(RetrievalError):
    pass


@dataclass(frozen=True)
class RetrievedChunk:
    index: int
    document_id: uuid.UUID
    chunk_index: int
    content: str
    score: float
    metadata: dict[str, Any] = field(default_factory=dict)

    @property
    def snippet(self) -> str:
        return self.content[:200]


@dataclass(frozen=True)
class RetrievalResult:
    chunks: tuple[RetrievedChunk, ...]
    top_score: float
    masked_chunks: int
    pii_categories: tuple[str, ...]
    lexical_fallback: bool = False
    rerank_fallback: bool = False

    @classmethod
    def empty(cls) -> RetrievalResult:
        return cls(chunks=(), top_score=0.0, masked_chunks=0, pii_categories=(), lexical_fallback=False)


@dataclass(frozen=True)
class _Candidate:
    document_id: uuid.UUID
    chunk_index: int
    content: str
    score: float
    metadata: dict[str, Any]


@dataclass(frozen=True)
class _LexicalChannel:
    surfaces: tuple[str, ...] = ()
    hits: tuple[LexicalHit, ...] = ()
    enabled: str = "false"
    phrase_count: int = 0
    fallback: bool = False


def reciprocal_rank_fusion(
    vector_keys: Sequence[ChunkKey],
    lexical_keys: Sequence[ChunkKey],
    rrf_k: int,
) -> dict[ChunkKey, float]:
    """RRF 只累加名次倒数：ts_rank 与余弦相似度的值域、语义、区分度都不同，加权等于给未定标的分数定权重（D-82）。"""

    fused: dict[ChunkKey, float] = {}
    for ranking in (vector_keys, lexical_keys):
        for position, key in enumerate(ranking, start=1):
            fused[key] = fused.get(key, 0.0) + 1.0 / (rrf_k + position)
    return fused


def lexical_coverage(content: str, surfaces: Sequence[str]) -> float:
    """查询切分片段在块正文中的覆盖比例，即 lexical-coverage-v1 的重排键（D-85）。

    与词法召回通道是否实际执行无关：重排用的是同一套切分口径。子串判定两侧都折叠大小写，
    与 PG 的 simple 配置一致（E38）。它只能整理次序，修不掉跨词邻接造成的假阳性（D-81 的 E5）。
    """

    if not surfaces:
        return 0.0
    lowered = content.lower()
    hits = sum(1 for surface in surfaces if surface in lowered)
    return hits / len(surfaces)


class RetrievalService:
    """Embedding + authorized PGVector/lexical hybrid recall + RRF fusion + retrieval-time PII masking."""

    def __init__(
        self,
        repository: VectorRepository,
        embedder: EmbeddingProvider,
        *,
        top_k: int = 6,
        min_score: float = 0.0,
        embedding_timeout_s: float = 10.0,
        lexical_repository: LexicalRepository | None = None,
        lexical_enabled: bool = True,
        lexical_candidate_limit: int = 50,
        fusion_rrf_k: int = 60,
        lexical_min_query_chars: int = 2,
        reranker: RerankProvider | None = None,
        rerank_timeout_s: float = 3.0,
        tracer: Tracer | None = None,
        business: BusinessMetrics | None = None,
        vector_collection: str = "",
        embedding_dimensions: int = 1024,
        require_tenant_id: bool = False,
    ) -> None:
        self._repository = repository
        self._embedder = embedder
        self._top_k = top_k
        self._min_score = min_score
        self._embedding_timeout_s = embedding_timeout_s
        self._lexical_repository = lexical_repository
        self._lexical_enabled = lexical_enabled and lexical_repository is not None
        self._lexical_candidate_limit = lexical_candidate_limit
        self._fusion_rrf_k = fusion_rrf_k
        self._lexical_min_query_chars = lexical_min_query_chars
        self._reranker = reranker
        self._rerank_timeout_s = rerank_timeout_s
        self._tracer = tracer or trace.get_tracer("docqa_ai")
        # 未注入时整段埋点退化为 no-op：`RetrievalService` 在测试与被 `DocumentSearchTool`
        # 直接构造的场景里都不该被迫持有一个 Prometheus 注册表。
        self._business = business
        self._vector_collection = vector_collection
        self._embedding_dimensions = embedding_dimensions
        self._vector_tenant_aware = _accepts_tenant_id(repository)
        self._lexical_tenant_aware = _accepts_tenant_id(lexical_repository) if lexical_repository is not None else False
        self._require_tenant_id = require_tenant_id
        # J3：组装层显式开启严格模式时，仓库必须真的支持 tenant 谓词，否则拒绝启动，
        # 而不是在每次请求里静默退回「仅 document scope 隔离」（原 duck-typing 软开关）。
        if require_tenant_id and not self._vector_tenant_aware:
            raise ValueError("vector repository is not tenant-aware; refusing to build RetrievalService")
        if require_tenant_id and self._lexical_enabled and not self._lexical_tenant_aware:
            raise ValueError("lexical repository is not tenant-aware; refusing to build RetrievalService")

    def _assert_tenant_ready(self, tenant_id: object | None, tenant_aware: bool, channel: str) -> None:
        if not self._require_tenant_id:
            return
        if tenant_id is None:
            raise RetrievalUnavailable(f"tenant_id is required for {channel} retrieval")
        if not tenant_aware:
            raise RetrievalUnavailable(f"{channel} repository is not tenant-aware; refusing to query")

    async def retrieve(
        self,
        question: str,
        scopes: Sequence[DocumentScope],
        *,
        deadline: Deadline | None = None,
        top_k: int | None = None,
        tenant_id: object | None = None,
    ) -> RetrievalResult:
        """检索入口：只做计时外壳，真正的流程在 `_retrieve`。

        `result` 取值收敛为四个（`OK` / `FALLBACK` / `EMPTY` / `ERROR`）：§4.3 的「RAG 慢查询」
        要按**是否走了降级通道**分别设阈值，混在一个标签里会被词法/重排回退稀释。
        `index_version` 只取首个作用域——跨作用域时多文档版本本就可能不同，取全量会让
        该标签的取值集合随文档数膨胀（这是唯一一处有意的有损折叠）。
        """

        index_version = scopes[0].index_version if scopes else ""
        started_at = time.perf_counter()
        try:
            retrieved = await self._retrieve(question, scopes, deadline=deadline, top_k=top_k, tenant_id=tenant_id)
        except Exception:
            # 任何异常都必须留一条 ERROR 样本，否则失败检索在耗时分布里彻底消失。
            self._observe_retrieval(started_at, "ERROR", index_version)
            raise
        if not retrieved.chunks:
            result = "EMPTY"
        elif retrieved.lexical_fallback or retrieved.rerank_fallback:
            result = "FALLBACK"
        else:
            result = "OK"
        self._observe_retrieval(started_at, result, index_version)
        return retrieved

    def _observe_retrieval(self, started_at: float, result: str, index_version: str) -> None:
        if self._business is None:
            return
        self._business.rag_retrieval(
            seconds=time.perf_counter() - started_at,
            result=result,
            index_version=index_version,
        )

    async def _retrieve(
        self,
        question: str,
        scopes: Sequence[DocumentScope],
        *,
        deadline: Deadline | None = None,
        top_k: int | None = None,
        tenant_id: object | None = None,
    ) -> RetrievalResult:
        if not scopes:
            return RetrievalResult.empty()
        # top_k 是最终输出上限，不是单通道取回上限；两路各取更大候选集，融合重排后再截断（D-91）
        output_limit = min(top_k or self._top_k, self._top_k)
        candidate_limit = max(output_limit * 3, self._lexical_candidate_limit)
        with self._tracer.start_as_current_span("rag.retrieve") as span:
            span.set_attribute("retrieval.top_k", output_limit)
            span.set_attribute("retrieval.scope_count", len(scopes))
            embedding = await self._embed(question, deadline)
            vector_hits = await self._search_vector(embedding, scopes, candidate_limit, tenant_id)
            lexical = await self._search_lexical(question, scopes, candidate_limit, deadline, tenant_id)
            with self._tracer.start_as_current_span("retrieval.fuse") as fuse_span:
                fuse_span.set_attribute("fusion_rrf_k", self._fusion_rrf_k)
                fuse_span.set_attribute("vector.candidate_count", len(vector_hits))
                fuse_span.set_attribute("lexical.candidate_count", len(lexical.hits))
                candidates = self._fuse(vector_hits, lexical.hits)
            with self._tracer.start_as_current_span("rag.rerank") as rerank_span:
                rerank_span.set_attribute("reranker", RERANKER_NAME)
                ranked = sorted(candidates, key=lambda item: -lexical_coverage(item.content, lexical.surfaces))
                kept = ranked[:output_limit]
                rerank_span.set_attribute("retrieval.kept", len(kept))
                rerank_span.set_attribute("retrieval.dropped", len(ranked) - len(kept))
                # D-116：掩码提到名次决定之前发生。它本来就要对 kept 逐篇做，换的只是时机，
                # 因此外发给第三方的每一串正文都已经过 `pii-v1`；落库与对外的 content 仍是掩码后的同一份。
                masked_items = [mask_text(candidate.content) for candidate in kept]
                order, rerank_fallback = await self._rerank(question, masked_items, rerank_span, deadline)
            if lexical.fallback:
                span.set_attribute("lexical_fallback", True)
            if rerank_fallback:
                span.set_attribute("rerank_fallback", True)
            chunks: list[RetrievedChunk] = []
            categories: set[str] = set()
            masked_chunks = 0
            for position, source in enumerate(order):
                candidate = kept[source]
                masked = masked_items[source]
                if masked.count:
                    masked_chunks += 1
                    categories.update(masked.categories)
                chunks.append(
                    RetrievedChunk(
                        index=position,
                        document_id=candidate.document_id,
                        chunk_index=candidate.chunk_index,
                        content=masked.masked,
                        score=candidate.score,
                        metadata=candidate.metadata,
                    )
                )
            # 重排后 chunks[0] 不再保证是最高相似度，top_score 必须显式取最大值。
            # 只被词法命中的块 score=0.0，所以它不会把 confidence 抬上去（D-84）。
            top_score = max((chunk.score for chunk in chunks), default=0.0)
            span.set_attribute("retrieval.chunk_count", len(chunks))
            span.set_attribute("retrieval.top_score", top_score)
            span.set_attribute("retrieval.masked_chunks", masked_chunks)
            return RetrievalResult(
                chunks=tuple(chunks),
                top_score=top_score,
                masked_chunks=masked_chunks,
                pii_categories=tuple(sorted(categories)),
                lexical_fallback=lexical.fallback,
                rerank_fallback=rerank_fallback,
            )

    async def _search_vector(
        self,
        embedding: tuple[float, ...],
        scopes: Sequence[DocumentScope],
        candidate_limit: int,
        tenant_id: object | None = None,
    ) -> list[VectorHit]:
        self._assert_tenant_ready(tenant_id, self._vector_tenant_aware, "vector")
        with self._tracer.start_as_current_span("vector.query") as query_span:
            started_at = time.perf_counter()
            try:
                if tenant_id is not None and self._vector_tenant_aware:
                    hits = await self._repository.search(embedding, scopes, candidate_limit, tenant_id=tenant_id)
                else:
                    hits = await self._repository.search(embedding, scopes, candidate_limit)
            except VectorStoreError as exc:
                self._observe_vector_search(started_at, "ERROR")
                query_span.set_attribute("error.type", type(exc).__name__)
                raise RetrievalUnavailable("vector query failed") from exc
            self._observe_vector_search(started_at, "OK")
            query_span.set_attribute("vector.hit_count", len(hits))
        return sorted(
            (hit for hit in hits if hit.score >= self._min_score),
            key=lambda hit: (-hit.score, str(hit.document_id), hit.chunk_index),
        )

    def _observe_vector_search(self, started_at: float, result: str) -> None:
        if self._business is None:
            return
        self._business.vector_search(
            seconds=time.perf_counter() - started_at,
            collection=self._vector_collection,
            result=result,
        )

    async def _search_lexical(
        self,
        question: str,
        scopes: Sequence[DocumentScope],
        candidate_limit: int,
        deadline: Deadline | None,
        tenant_id: object | None = None,
    ) -> _LexicalChannel:
        repository = self._lexical_repository
        if not self._lexical_enabled or repository is None:
            return _LexicalChannel()
        self._assert_tenant_ready(tenant_id, self._lexical_tenant_aware, "lexical")
        phrases = extract_phrases(question)
        surfaces = tuple(phrase.surface for phrase in phrases)
        with self._tracer.start_as_current_span("lexical.query") as span:
            # 实际发出的词位数必须可观测，否则事后无法区分「没召回」与「没参与」（D-97(c)）
            span.set_attribute("lexical.phrase_count", len(phrases))
            lexical_query = build_lexical_query(question)
            effective_chars = sum(len(phrase.surface) for phrase in phrases)
            if lexical_query is None or effective_chars < self._lexical_min_query_chars:
                span.set_attribute("lexical.enabled", "skipped_short_query")
                return _LexicalChannel(surfaces=surfaces, enabled="skipped_short_query", phrase_count=len(phrases))
            span.set_attribute("lexical.enabled", "true")
            if deadline is not None and deadline.remaining_seconds() <= 0.0:
                return self._degrade(span, surfaces, len(phrases), "deadline expired before lexical query")
            if tenant_id is not None and self._lexical_tenant_aware:
                search = repository.search(lexical_query, scopes, candidate_limit, tenant_id=tenant_id)
            else:
                search = repository.search(lexical_query, scopes, candidate_limit)
            try:
                if deadline is None:
                    hits = await search
                else:
                    hits = await asyncio.wait_for(search, timeout=deadline.remaining_seconds())
            except (LexicalStoreError, TimeoutError) as exc:
                return self._degrade(
                    span,
                    surfaces,
                    len(phrases),
                    f"lexical recall degraded to vector-only ({type(exc).__name__})",
                )
            span.set_attribute("lexical.hit_count", len(hits))
            return _LexicalChannel(
                surfaces=surfaces,
                hits=tuple(hits),
                enabled="true",
                phrase_count=len(phrases),
            )

    def _degrade(
        self,
        span: Span,
        surfaces: tuple[str, ...],
        phrase_count: int,
        reason: str,
    ) -> _LexicalChannel:
        """D-88：词法是增量能力，它挂掉时系统行为等于 S5 之前；但退化必须在观测上可见。"""

        span.set_attribute("lexical_fallback", True)
        log_event(LOGGER, logging.WARNING, reason, step="retrieval", operation="lexical.query", status="degraded")
        return _LexicalChannel(surfaces=surfaces, enabled="true", phrase_count=phrase_count, fallback=True)

    async def _rerank(
        self,
        question: str,
        masked_items: Sequence[MaskResult],
        span: Span,
        deadline: Deadline | None,
    ) -> tuple[list[int], bool]:
        """返回 `kept` 的下标顺序；Provider 只决定名次，永不改 `score`（D-104）。

        不注入 Provider 时这条通道根本不执行，默认配置与 S5 逐字节一致（D-113）。
        """

        provider = self._reranker
        size = len(masked_items)
        if provider is None or size == 0:
            return list(range(size)), False
        timeout = self._rerank_timeout_s
        if deadline is not None:
            timeout = min(timeout, deadline.remaining_seconds())
        if timeout <= 0.05:
            return self._rerank_degrade(span, size, "deadline expired before rerank")
        # 查询侧的掩码由调用方完成（`agent/service.py` 传入的就是掩码后的问题），但外发红线不能依赖调用方自觉：
        # `mask_text` 幂等（E66 的探针断言），重掩一次对已掩码文本是恒等的，对未掩码文本则堵死最后的缺口。
        query = mask_text(question).masked
        span.set_attribute("rerank.model", provider.model)
        span.set_attribute("rerank.document_count", size)
        try:
            items = await asyncio.wait_for(
                provider.rerank(query, [item.masked for item in masked_items], timeout),
                timeout=timeout,
            )
        except (RerankError, TimeoutError) as exc:
            return self._rerank_degrade(
                span,
                size,
                f"rerank degraded to fused order ({type(exc).__name__})",
            )
        except (ExperimentBudgetExhausted, BudgetError) as exc:
            # D-119：额度拦下 rerank 与 rerank 自身失败同形——名次是增量能力，退回融合次序即可，响应体一个字段不变。
            return self._rerank_degrade(
                span,
                size,
                f"rerank skipped to fused order ({type(exc).__name__})",
            )
        span.set_attribute("rerank.returned", len(items))
        order = self._order_from_items(items, size)
        if order is None:
            return self._rerank_degrade(span, size, "rerank output was unusable")
        return order, False

    @staticmethod
    def _rerank_degrade(span: Span, size: int, reason: str) -> tuple[list[int], bool]:
        """D-103 与 D-88 同形：rerank 是增量能力，它挂掉等于退回融合次序，对外响应体一个字段都不变。"""

        span.set_attribute("rerank_fallback", True)
        log_event(LOGGER, logging.WARNING, reason, step="retrieval", operation="rerank", status="degraded")
        return list(range(size)), True

    @staticmethod
    def _order_from_items(items: Sequence[RerankedItem], size: int) -> list[int] | None:
        """Provider 返回的列表按相关性降序，这里把它变成 `kept` 的下标顺序。

        下标越界或重复即整份输出作废（宁可退回本地次序，也不按可疑名次重排）；
        **条数少于候选数是合法的部分返回**（`top_n` 语义），缺失的下标补在末尾——
        名次可以让位，块不能让第三方悄悄删掉，那会变成一个新的静默缺失面。
        """

        order: list[int] = []
        seen: set[int] = set()
        for item in items:
            if not 0 <= item.index < size or item.index in seen:
                return None
            seen.add(item.index)
            order.append(item.index)
        order.extend(index for index in range(size) if index not in seen)
        return order

    def _fuse(self, vector_hits: Sequence[VectorHit], lexical_hits: Sequence[LexicalHit]) -> list[_Candidate]:
        candidates: dict[ChunkKey, _Candidate] = {}
        for hit in vector_hits:
            key = (hit.document_id, hit.chunk_index)
            candidates[key] = _Candidate(
                document_id=hit.document_id,
                chunk_index=hit.chunk_index,
                content=hit.content,
                score=hit.score,
                metadata=dict(hit.metadata),
            )
        for hit in lexical_hits:
            key = (hit.document_id, hit.chunk_index)
            if key not in candidates:
                # 向量没给的块只靠词法进候选集，相似度未知就写 0，不伪造证据（D-84）
                candidates[key] = _Candidate(
                    document_id=hit.document_id,
                    chunk_index=hit.chunk_index,
                    content=hit.content,
                    score=0.0,
                    metadata=dict(hit.metadata),
                )
        fused = reciprocal_rank_fusion(
            [(hit.document_id, hit.chunk_index) for hit in vector_hits],
            [(hit.document_id, hit.chunk_index) for hit in lexical_hits],
            self._fusion_rrf_k,
        )
        ordered = sorted(fused, key=lambda key: (-fused[key], str(key[0]), key[1]))
        return [candidates[key] for key in ordered]

    async def _embed(self, question: str, deadline: Deadline | None) -> tuple[float, ...]:
        timeout = self._embedding_timeout_s
        if deadline is not None:
            timeout = min(timeout, deadline.remaining_seconds())
        if timeout <= 0.05:
            raise RetrievalDeadlineExceeded("deadline expired before embedding")
        with self._tracer.start_as_current_span("rag.embed"):
            try:
                vectors = await asyncio.wait_for(
                    self._embedder.embed([question], timeout),
                    timeout=timeout,
                )
            except TimeoutError as exc:
                raise RetrievalDeadlineExceeded("embedding timed out") from exc
            except EmbeddingTimeout as exc:
                raise RetrievalUnavailable("embedding timed out") from exc
            except EmbeddingError as exc:
                raise RetrievalUnavailable("embedding failed") from exc
            except ExperimentBudgetExhausted as exc:
                raise RetrievalUnavailable("experiment budget exhausted") from exc
            except BudgetError as exc:
                raise RetrievalUnavailable("experiment budget unavailable") from exc
        if len(vectors) != 1 or len(vectors[0]) != self._embedding_dimensions:
            raise RetrievalUnavailable("embedding dimension mismatch")
        return vectors[0]

    async def aclose(self) -> None:
        """关闭共享 httpx client（CON-2）；offline / noop 替身没有 aclose，安全跳过。"""
        await _maybe_aclose(self._embedder)
        await _maybe_aclose(self._reranker)
