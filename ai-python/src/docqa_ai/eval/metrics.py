"""检索质量指标：Recall@k、MRR、nDCG@k。

口径与信息检索标准一致，输入是「每个查询的黄金相关块集合」与「检索返回的块序列」，
输出单查询指标与汇总均值。所有指标对「相关块在返回列表里的名次」敏感，因此既能
衡量召回能力（Recall@k），也能衡量排序质量（MRR / nDCG@k）。

ground truth 用 (document_id, chunk_index) 唯一标识一个块；跨文档语料下仅按
chunk_index 会撞键，所以这里坚持用二元组。
"""

from __future__ import annotations

import math
from collections.abc import Sequence
from dataclasses import dataclass, field
from typing import Any

ChunkRef = tuple[str, int]  # (document_id, chunk_index)


@dataclass(frozen=True)
class QueryResult:
    query_id: str
    question: str
    returned: tuple[ChunkRef, ...]
    relevant: frozenset[ChunkRef]
    recall_at_k: dict[int, float] = field(default_factory=dict)
    mrr: float = 0.0
    ndcg_at_k: dict[int, float] = field(default_factory=dict)


def recall_at_k(returned: Sequence[ChunkRef], relevant: set[ChunkRef], k: int) -> float:
    """前 k 个结果里命中相关块的比例（占全部相关块）。相关集为空则记为 0（无召回可言）。"""
    if not relevant:
        return 0.0
    hit = len(set(returned[:k]) & relevant)
    return hit / len(relevant)


def reciprocal_rank(returned: Sequence[ChunkRef], relevant: set[ChunkRef]) -> float:
    """第一个相关块所在名次的倒数；未命中返回 0。"""
    for rank, ref in enumerate(returned, start=1):
        if ref in relevant:
            return 1.0 / rank
    return 0.0


def _dcg(returned: Sequence[ChunkRef], relevant: set[ChunkRef], k: int) -> float:
    gain = 0.0
    for rank, ref in enumerate(returned[:k], start=1):
        if ref in relevant:
            gain += 1.0 / math.log2(rank + 1)
    return gain


def ndcg_at_k(returned: Sequence[ChunkRef], relevant: set[ChunkRef], k: int) -> float:
    """归一化折损累计增益：理想排序是把所有相关块排在最前。

    二进制相关（相关/不相关）下，理想 DCG 是把 min(len(relevant), k) 个相关块排在
    前 min(len(relevant), k) 个位置。"""
    if not relevant:
        return 0.0
    ideal_k = min(len(relevant), k)
    ideal = sum(1.0 / math.log2(i + 1) for i in range(1, ideal_k + 1))
    if ideal == 0.0:
        return 0.0
    return _dcg(returned, relevant, k) / ideal


def compute_metrics(
    query_id: str,
    question: str,
    returned: Sequence[ChunkRef],
    relevant: Sequence[ChunkRef],
    ks: Sequence[int] = (1, 3, 5),
) -> QueryResult:
    rel = frozenset(relevant)
    return QueryResult(
        query_id=query_id,
        question=question,
        returned=tuple(returned),
        relevant=rel,
        recall_at_k={k: recall_at_k(returned, rel, k) for k in ks},
        mrr=reciprocal_rank(returned, rel),
        ndcg_at_k={k: ndcg_at_k(returned, rel, k) for k in ks},
    )


@dataclass(frozen=True)
class EvalSummary:
    query_count: int
    recall_at_k: dict[int, float]
    mrr: float
    ndcg_at_k: dict[int, float]
    per_query: tuple[QueryResult, ...]

    def to_dict(self) -> dict[str, Any]:
        def _str_keys(d: dict[int, float]) -> dict[str, float]:
            return {str(k): round(v, 4) for k, v in d.items()}

        return {
            "query_count": self.query_count,
            "recall_at_k": _str_keys(self.recall_at_k),
            "mrr": round(self.mrr, 4),
            "ndcg_at_k": _str_keys(self.ndcg_at_k),
            "per_query": [
                {
                    "query_id": q.query_id,
                    "question": q.question,
                    "recall_at_k": _str_keys(q.recall_at_k),
                    "mrr": round(q.mrr, 4),
                    "ndcg_at_k": _str_keys(q.ndcg_at_k),
                }
                for q in self.per_query
            ],
        }


def evaluate_queries(results: Sequence[QueryResult], ks: Sequence[int] = (1, 3, 5)) -> EvalSummary:
    """汇总多个查询的均值指标。"""
    if not results:
        return EvalSummary(query_count=0, recall_at_k={}, mrr=0.0, ndcg_at_k={}, per_query=())

    def _mean(values: Sequence[float]) -> float:
        return sum(values) / len(values)

    return EvalSummary(
        query_count=len(results),
        recall_at_k={k: _mean([r.recall_at_k[k] for r in results]) for k in ks},
        mrr=_mean([r.mrr for r in results]),
        ndcg_at_k={k: _mean([r.ndcg_at_k[k] for r in results]) for k in ks},
        per_query=tuple(results),
    )
