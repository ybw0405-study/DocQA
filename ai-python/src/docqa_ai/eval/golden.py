"""可溯源语料黄金集的离线检索与评测。

与现有 eval harness 相互独立：不改动入库基线，因此不影响 CI 的
``--check-baseline`` 门禁。候选池只取真实法规条文块，检索用离线确定性
特征哈希向量，保证任何机器上都能复现同一组数字。
"""

from __future__ import annotations

import argparse
import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from docqa_ai.eval.corpus import load_corpus
from docqa_ai.providers.embeddings import OfflineEmbeddingProvider, cosine_similarity

GOLDEN_PATH = Path(__file__).with_name("golden_set.json")
REAL_DOCUMENT_ID = "real-legal"
NEGATIVE_HIT_THRESHOLD = 0.35


@dataclass(frozen=True)
class _IndexedChunk:
    index: int
    vector: list[float]


class GoldenIndex:
    """只读检索索引：构造时一次性编码候选块，查询时只编码问句。"""

    def __init__(self, dimensions: int = 1024) -> None:
        self._provider = OfflineEmbeddingProvider(dimensions=dimensions)
        docs = [doc for doc in load_corpus() if doc.document_id == REAL_DOCUMENT_ID]
        if not docs:
            raise RuntimeError(f"corpus 中找不到 {REAL_DOCUMENT_ID}")
        # 离线 provider 的 embed 是纯计算、无 IO；直接调用其确定性编码函数，
        # 避免每次查询创建事件循环，把调度开销混进延迟测量。
        self.chunks = [_IndexedChunk(index, self._provider._embed_one(text)) for index, text in docs[0].chunks]

    def search(self, question: str, top_k: int = 5) -> list[tuple[int, float]]:
        vector = self._provider._embed_one(question)
        scored = [(chunk.index, cosine_similarity(vector, chunk.vector)) for chunk in self.chunks]
        scored.sort(key=lambda item: (-item[1], item[0]))
        return scored[:top_k]


def load_golden_set(path: Path = GOLDEN_PATH) -> dict[str, Any]:
    with path.open(encoding="utf-8") as handle:
        payload: dict[str, Any] = json.load(handle)
    return payload


def evaluate(
    index: GoldenIndex,
    golden: dict[str, Any],
    *,
    negative_threshold: float = NEGATIVE_HIT_THRESHOLD,
) -> dict[str, Any]:
    """计算 recall@k / MRR 与负例误召回，并给出逐条结果便于人工复核。"""
    recalls: dict[int, list[float]] = {1: [], 3: [], 5: []}
    reciprocal_ranks: list[float] = []
    by_variant: dict[str, list[float]] = {}
    per_query: list[dict[str, Any]] = []

    for query in golden["queries"]:
        returned = index.search(query["question"], top_k=5)
        relevant = set(query["relevant"])
        hit_at_1 = bool(returned and returned[0][0] in relevant)
        for k in (1, 3, 5):
            recalls[k].append(float(any(idx in relevant for idx, _ in returned[:k])))
        rank = next((r for r, (idx, _) in enumerate(returned, 1) if idx in relevant), None)
        reciprocal_ranks.append(1.0 / rank if rank else 0.0)
        by_variant.setdefault(query["variant"], []).append(float(hit_at_1))
        per_query.append(
            {
                "query_id": query["query_id"],
                "variant": query["variant"],
                "source": query["source"],
                "top1": returned[0][0] if returned else None,
                "relevant": query["relevant"],
                "hit_at_1": hit_at_1,
                "top_score": round(returned[0][1], 4) if returned else 0.0,
            }
        )

    negatives: list[dict[str, Any]] = []
    for query in golden["negative_queries"]:
        returned = index.search(query["question"], top_k=1)
        score = returned[0][1] if returned else 0.0
        negatives.append(
            {
                "query_id": query["query_id"],
                "top_score": round(score, 4),
                "false_hit": score >= negative_threshold,
            }
        )

    return {
        "query_count": len(per_query),
        "negative_query_count": len(negatives),
        "recall_at_k": {str(k): round(sum(v) / len(v), 4) for k, v in recalls.items()},
        "mrr": round(sum(reciprocal_ranks) / len(reciprocal_ranks), 4),
        "by_variant": {variant: round(sum(hits) / len(hits), 4) for variant, hits in sorted(by_variant.items())},
        "negative_top_score_mean": round(sum(item["top_score"] for item in negatives) / len(negatives), 4),
        "negative_false_hit_rate": round(sum(bool(item["false_hit"]) for item in negatives) / len(negatives), 4),
        "negative_threshold": negative_threshold,
        "per_query": per_query,
        "negatives": negatives,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="DocQA 可溯源语料黄金集离线评测")
    parser.add_argument("--report", type=Path, help="把完整结果写入 JSON 文件")
    parser.add_argument("--quiet", action="store_true", help="只输出摘要")
    args = parser.parse_args()

    payload = evaluate(GoldenIndex(), load_golden_set())
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        with args.report.open("w", encoding="utf-8", newline="\n") as handle:
            json.dump(payload, handle, ensure_ascii=False, indent=2)
            handle.write("\n")

    if args.quiet:
        print(
            f"queries={payload['query_count']} recall@1={payload['recall_at_k']['1']} "
            f"recall@5={payload['recall_at_k']['5']} mrr={payload['mrr']} "
            f"neg_false_hit={payload['negative_false_hit_rate']}"
        )
    else:
        print(
            json.dumps(
                {k: v for k, v in payload.items() if k not in {"per_query", "negatives"}}, ensure_ascii=False, indent=2
            )
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
