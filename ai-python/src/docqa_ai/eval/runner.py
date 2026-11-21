"""评估 runner：建索引 → 逐查询检索 → 计算指标 → 输出报告。

默认离线确定性：OfflineEmbeddingProvider（特征哈希）+ InMemoryVectorRepository，
完全不依赖网络与 API key，结果逐字节可复现，适合 CI 防回归。

真实 provider：设置 DOCQA_EVAL_EMBEDDING=openai 并注入
DOCQA_EMBEDDING_BASE_URL / DOCQA_EMBEDDING_API_KEY / DOCQA_EMBEDDING_MODEL 后，
用生产语义模型跑真实质量数字。rerank 同理（DOCQA_EVAL_RERANK）。
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
import sys
import uuid
from dataclasses import dataclass
from typing import Any

from docqa_ai.eval.corpus import CorpusDocument, load_corpus
from docqa_ai.eval.metrics import ChunkRef, QueryResult, compute_metrics, evaluate_queries
from docqa_ai.protocol.models import DocumentScope
from docqa_ai.providers.embeddings import (
    OfflineEmbeddingProvider,
    OpenAIEmbeddingProvider,
)
from docqa_ai.providers.rerank import NoopRerank
from docqa_ai.rag.repository import ChunkRecord, InMemoryVectorRepository
from docqa_ai.rag.service import RetrievalService

LOGGER = logging.getLogger(__name__)

_KS = (1, 3, 5)
_DEFAULT_VERSION = "v1"
_DEFAULT_DIMENSIONS = 1024


def _build_embedder() -> Any:
    """按环境变量选择 embedding provider，默认离线确定性哈希。"""
    mode = os.getenv("DOCQA_EVAL_EMBEDDING", "offline").strip().lower()
    if mode == "openai":
        base_url = os.getenv("DOCQA_EMBEDDING_BASE_URL", "")
        api_key = os.getenv("DOCQA_EMBEDDING_API_KEY", "")
        model = os.getenv("DOCQA_EMBEDDING_MODEL", "text-embedding-v3")
        if not base_url or not api_key:
            raise SystemExit(
                "DOCQA_EVAL_EMBEDDING=openai 需要 DOCQA_EMBEDDING_BASE_URL 与 "
                "DOCQA_EMBEDDING_API_KEY（真实评测，不写任何默认密钥）"
            )
        return OpenAIEmbeddingProvider(
            base_url=base_url,
            api_key=api_key,
            model=model,
            dimensions=_DEFAULT_DIMENSIONS,
        )
    return OfflineEmbeddingProvider(dimensions=_DEFAULT_DIMENSIONS)


def _build_reranker() -> Any:
    """按环境变量选择 rerank provider，默认 Noop（保持 fused order）。"""
    mode = os.getenv("DOCQA_EVAL_RERANK", "").strip().lower()
    if mode in ("", "noop", "off"):
        return NoopRerank()
    if mode == "openai":
        from docqa_ai.providers.rerank import DashScopeRerankProvider

        base_url = os.getenv("DOCQA_RERANK_BASE_URL", "")
        api_key = os.getenv("DOCQA_RERANK_API_KEY", "")
        model = os.getenv("DOCQA_RERANK_MODEL", "gte-rerank-v2")
        if not base_url or not api_key:
            raise SystemExit("DOCQA_EVAL_RERANK=openai 需要 DOCQA_RERANK_BASE_URL 与 API_KEY")
        return DashScopeRerankProvider(base_url=base_url, api_key=api_key, model=model)
    raise SystemExit(f"unknown DOCQA_EVAL_RERANK={mode}")


@dataclass
class _IndexedDoc:
    document_id: uuid.UUID
    scope: DocumentScope
    chunk_ids: dict[int, uuid.UUID]


async def _index_document(
    doc: CorpusDocument,
    embedder: Any,
    repository: InMemoryVectorRepository,
    *,
    version: str,
) -> _IndexedDoc:
    """把单个文档的块写入内存向量仓库，返回文档级定位信息。"""
    document_id = uuid.uuid5(uuid.NAMESPACE_URL, f"docqa-eval:{doc.document_id}")
    texts = [content for _, content in doc.chunks]
    vectors = await embedder.embed(texts, timeout_s=30.0)
    chunk_ids: dict[int, uuid.UUID] = {}
    for (chunk_index, content), vector in zip(doc.chunks, vectors, strict=True):
        vector_id = uuid.uuid5(document_id, f"chunk:{chunk_index}")
        chunk_ids[chunk_index] = vector_id
        repository.add(
            ChunkRecord(
                vector_id=vector_id,
                document_id=document_id,
                chunk_index=chunk_index,
                content=content,
                embedding=tuple(vector),
                metadata={"index_version": version},
            )
        )
    return _IndexedDoc(
        document_id=document_id,
        scope=DocumentScope(document_id=document_id, index_version=version),
        chunk_ids=chunk_ids,
    )


async def _run_query(
    question: str,
    query_id: str,
    relevant: tuple[int, ...],
    indexed: _IndexedDoc,
    service: RetrievalService,
) -> QueryResult:
    result = await service.retrieve(question, [indexed.scope])
    returned: list[ChunkRef] = [
        (str(indexed.document_id), chunk.chunk_index) for chunk in result.chunks
    ]
    relevant_refs = [(str(indexed.document_id), idx) for idx in relevant]
    return compute_metrics(query_id, question, returned, relevant_refs, ks=_KS)


async def run_eval() -> dict[str, Any]:
    """执行完整评测，返回可序列化的汇总 dict。"""
    embedder = _build_embedder()
    reranker = _build_reranker()
    corpus = load_corpus()

    # 所有文档写入同一个 InMemoryVectorRepository，靠 DocumentScope 隔离（与真实语义一致）
    repository = InMemoryVectorRepository()
    indexed: dict[str, _IndexedDoc] = {}
    for doc in corpus:
        idx = await _index_document(doc, embedder, repository, version=_DEFAULT_VERSION)
        indexed[doc.document_id] = idx

    # lexical 通道在真实部署里依赖 PG；离线评测聚焦向量+rerank 链路质量，
    # 关闭词法通道以隔离评测目标（向量召回 + 重排排序）。
    service = RetrievalService(
        repository,
        embedder,
        top_k=6,
        min_score=0.0,
        lexical_enabled=False,
        reranker=reranker,
        embedding_dimensions=_DEFAULT_DIMENSIONS,
    )

    results: list[QueryResult] = []
    for doc in corpus:
        idx = indexed[doc.document_id]
        for query in doc.queries:
            qr = await _run_query(query.question, query.query_id, query.relevant, idx, service)
            results.append(qr)

    await _maybe_aclose(embedder)
    await _maybe_aclose(reranker)

    summary = evaluate_queries(results, ks=_KS)
    payload = summary.to_dict()
    payload["embedding"] = getattr(embedder, "model", "unknown")
    payload["reranker"] = getattr(reranker, "model", "none")
    return payload


async def _maybe_aclose(resource: Any) -> None:
    closer = getattr(resource, "aclose", None)
    if closer is not None:
        result = closer()
        if asyncio.iscoroutine(result):
            await result


def _render_table(payload: dict[str, Any]) -> str:
    lines: list[str] = []
    lines.append("=" * 72)
    lines.append("DocQA 检索质量评估报告")
    lines.append("=" * 72)
    lines.append("语料: 合成(跨主题 8 + 同主题细粒度 1×5) + 真实条文 4")
    lines.append(f"查询数: {payload['query_count']}")
    lines.append(f"embedding: {payload['embedding']}   rerank: {payload['reranker']}")
    lines.append("-" * 72)
    lines.append(f"{'指标':<18}{'Recall@1':>10}{'Recall@3':>10}{'Recall@5':>10}")
    r = payload["recall_at_k"]
    lines.append(f"{'Recall@k':<18}{r.get('1', 0):>10.3f}{r.get('3', 0):>10.3f}{r.get('5', 0):>10.3f}")
    lines.append(f"{'MRR':<18}{payload['mrr']:>10.3f}")
    n = payload["ndcg_at_k"]
    lines.append(f"{'nDCG@k':<18}{n.get('1', 0):>10.3f}{n.get('3', 0):>10.3f}{n.get('5', 0):>10.3f}")
    lines.append("-" * 72)
    lines.append("逐查询明细：")
    for q in payload["per_query"]:
        hit1 = "✓" if q["recall_at_k"].get("1", 0) >= 1.0 else "✗"
        lines.append(
            f"  {hit1}  {q['query_id']:<14} R@1={q['recall_at_k'].get('1',0):.2f} "
            f"MRR={q['mrr']:.2f}  {q['question']}"
        )
    lines.append("=" * 72)
    return "\n".join(lines)


def main() -> int:
    as_json = "--json" in sys.argv
    payload = asyncio.run(run_eval())
    if as_json:
        print(json.dumps(payload, ensure_ascii=False, indent=2))
    else:
        print(_render_table(payload))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
