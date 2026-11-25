"""评估 runner：建索引 → 逐查询检索 → 计算指标 → 输出报告。

默认离线确定性：OfflineEmbeddingProvider（特征哈希）+ InMemoryVectorRepository，
完全不依赖网络与 API key，结果逐字节可复现，适合 CI 防回归。

真实 provider：设置 DOCQA_EVAL_EMBEDDING=openai 并注入
DOCQA_EMBEDDING_BASE_URL / DOCQA_EMBEDDING_API_KEY / DOCQA_EMBEDDING_MODEL 后，
用生产语义模型跑真实质量数字。rerank 同理（DOCQA_EVAL_RERANK）。

套件与基线：
    python -m docqa_ai.eval --suite retrieval|agent|all
    python -m docqa_ai.eval --suite all --check-baseline # 对照 baselines/*.json 门禁
    python -m docqa_ai.eval --suite all --update-baseline # 有意变更后重录基线
"""

from __future__ import annotations

import argparse
import asyncio
import json
import logging
import os
import sys
import uuid
from collections.abc import Sequence
from dataclasses import dataclass
from pathlib import Path
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
    tenant_id: str = "",
) -> _IndexedDoc:
    """把单个文档的块写入内存向量仓库，返回文档级定位信息。

    tenant_id 供 agent 套件使用：内存仓库与真实 SQL 一样执行租户谓词，
    不带租户标注的行会被带 tenant_id 的检索拒绝。检索评测不传，保持原语义。
    """
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
                tenant_id=tenant_id,
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
    returned: list[ChunkRef] = [(str(indexed.document_id), chunk.chunk_index) for chunk in result.chunks]
    relevant_refs = [(str(indexed.document_id), idx) for idx in relevant]
    return compute_metrics(query_id, question, returned, relevant_refs, ks=_KS)


async def index_corpus(
    embedder: Any,
    repository: InMemoryVectorRepository,
    corpus: Sequence[CorpusDocument],
    *,
    version: str = _DEFAULT_VERSION,
    tenant_id: str = "",
) -> dict[str, _IndexedDoc]:
    """把整套语料写入内存向量仓库；检索与 agent 两个套件共用同一建索引路径。"""
    indexed: dict[str, _IndexedDoc] = {}
    for doc in corpus:
        indexed[doc.document_id] = await _index_document(
            doc, embedder, repository, version=version, tenant_id=tenant_id
        )
    return indexed


async def run_eval() -> dict[str, Any]:
    """执行完整评测，返回可序列化的汇总 dict。"""
    embedder = _build_embedder()
    reranker = _build_reranker()
    corpus = load_corpus()

    # 所有文档写入同一个 InMemoryVectorRepository，靠 DocumentScope 隔离（与真实语义一致）
    repository = InMemoryVectorRepository()
    indexed = await index_corpus(embedder, repository, corpus)

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
    lines.append(f"embedding: {payload['embedding']} rerank: {payload['reranker']}")
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
            f" {hit1} {q['query_id']:<14} R@1={q['recall_at_k'].get('1', 0):.2f} MRR={q['mrr']:.2f} {q['question']}"
        )
    lines.append("=" * 72)
    return "\n".join(lines)


_BASELINE_DIR = Path(__file__).resolve().parent / "baselines"
# 基线数字与 run_eval 同口径 round(4)；比较留 1e-9 容差吸收浮点求和顺序差。
_BASELINE_EPSILON = 1e-9


def _retrieval_floors(payload: dict[str, Any]) -> dict[str, Any]:
    return {
        "query_count": payload["query_count"],
        "recall_at_k": payload["recall_at_k"],
        "mrr": payload["mrr"],
        "ndcg_at_k": payload["ndcg_at_k"],
    }


def _agent_floors(payload: dict[str, Any]) -> dict[str, Any]:
    return {
        "case_count": payload["case_count"],
        "pass_rate": payload["pass_rate"],
        "cases": [case["case_id"] for case in payload["cases"]],
    }


def update_baseline(suite: str, payload: dict[str, Any], *, baseline_dir: Path | None = None) -> Path:
    """把当前运行录为基线。只存门禁需要的字段，不存逐查询明细（基线要可人工审阅）。"""
    floors = _retrieval_floors(payload) if suite == "retrieval" else _agent_floors(payload)
    path = (baseline_dir or _BASELINE_DIR) / f"{suite}.json"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(floors, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return path


def baseline_violations(suite: str, payload: dict[str, Any], *, baseline_dir: Path | None = None) -> list[str]:
    """对照提交基线找回归：检索指标只许升不许降；agent 用例集必须一致且全过。"""
    path = (baseline_dir or _BASELINE_DIR) / f"{suite}.json"
    baseline = json.loads(path.read_text(encoding="utf-8"))
    violations: list[str] = []
    if suite == "retrieval":
        if payload["query_count"] != baseline["query_count"]:
            violations.append(f"query_count 漂移: 基线 {baseline['query_count']}，当前 {payload['query_count']}")
        for metric in ("recall_at_k", "ndcg_at_k"):
            for k, floor in baseline[metric].items():
                current = payload[metric].get(k, 0.0)
                if current < floor - _BASELINE_EPSILON:
                    violations.append(f"{metric}@{k} 回归: 基线 {floor}，当前 {current}")
        if payload["mrr"] < baseline["mrr"] - _BASELINE_EPSILON:
            violations.append(f"mrr 回归: 基线 {baseline['mrr']}，当前 {payload['mrr']}")
        return violations
    if [case["case_id"] for case in payload["cases"]] != baseline["cases"]:
        violations.append("agent 用例集与基线不一致（增删用例必须同步重录基线）")
    if payload["pass_rate"] < baseline["pass_rate"] - _BASELINE_EPSILON:
        violations.append(f"agent pass_rate 回归: 基线 {baseline['pass_rate']}，当前 {payload['pass_rate']}")
    violations.extend(
        f"agent 用例失败: {case['case_id']} ({case.get('detail', '')})"
        for case in payload["cases"]
        if not case["passed"]
    )
    return violations


def _render_agent(payload: dict[str, Any]) -> str:
    lines: list[str] = []
    lines.append("=" * 72)
    lines.append("DocQA agent 级确定性回归报告")
    lines.append("=" * 72)
    lines.append(f"用例数: {payload['case_count']} 通过率: {payload['pass_rate']:.3f}")
    lines.append("-" * 72)
    for case in payload["cases"]:
        mark = "✓" if case["passed"] else "✗"
        detail = case.get("detail", "")
        lines.append(f" {mark} {case['case_id']:<28} {detail}")
    lines.append("=" * 72)
    return "\n".join(lines)


def main(argv: Sequence[str] | None = None) -> int:
    # Windows 默认 GBK 控制台编不了报告里的 ✓/✗：替换降级，不能让门禁崩在打印上。
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(errors="replace")
    parser = argparse.ArgumentParser(prog="docqa_ai.eval", description="DocQA 离线评测与基线门禁")
    parser.add_argument("--suite", choices=("retrieval", "agent", "all"), default="retrieval")
    parser.add_argument("--json", action="store_true", dest="as_json", help="输出机器可读 JSON")
    parser.add_argument("--check-baseline", action="store_true", help="对照 baselines/*.json，回归即非零退出")
    parser.add_argument("--update-baseline", action="store_true", help="把本次运行重录为基线（有意变更时使用）")
    args = parser.parse_args(argv)
    if args.check_baseline and args.update_baseline:
        parser.error("--check-baseline 与 --update-baseline 互斥")

    suites = ("retrieval", "agent") if args.suite == "all" else (args.suite,)
    payload: dict[str, Any] = {}
    for suite in suites:
        if suite == "retrieval":
            payload["retrieval"] = asyncio.run(run_eval())
        else:
            # agent 套件依赖 fakeredis（dev/CI 锁内）；延迟导入让纯检索评测不背这个依赖。
            from docqa_ai.eval.agent import run_agent_eval

            payload["agent"] = asyncio.run(run_agent_eval())

    if args.update_baseline:
        for suite in suites:
            path = update_baseline(suite, payload[suite])
            print(f"基线已更新: {path}")
        return 0

    if args.as_json:
        print(json.dumps(payload, ensure_ascii=False, indent=2))
    else:
        if "retrieval" in payload:
            print(_render_table(payload["retrieval"]))
        if "agent" in payload:
            print(_render_agent(payload["agent"]))

    exit_code = 0
    if "agent" in payload and payload["agent"]["pass_rate"] < 1.0:
        exit_code = 1
    if args.check_baseline:
        for suite in suites:
            violations = baseline_violations(suite, payload[suite])
            for item in violations:
                print(f"基线门禁失败 [{suite}]: {item}", file=sys.stderr)
            if violations:
                exit_code = 1
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
