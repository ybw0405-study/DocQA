"""评估 harness 的回归测试：锁住离线确定性基线的关键指标不漂移。

离线基线（OfflineEmbeddingProvider + InMemoryVectorRepository）是完全确定性的，
任何对语料、指标、检索链路的改动都会反映在这些数字上。这里锁下限，防止：
- 语料被无意增删导致 query_count 漂移；
- 指标计算被改坏（Recall@1 掉到 0）；
- 检索链路回归（跨主题简单查询应该 100% 命中 R@1）。
"""

from __future__ import annotations

import asyncio
import os

import pytest

from docqa_ai.eval import run_eval
from docqa_ai.eval.corpus import load_corpus
from docqa_ai.eval.metrics import compute_metrics, evaluate_queries


def test_corpus_has_expected_coverage() -> None:
    docs = load_corpus()
    # 8 跨主题 + 1 劳动法细粒度 + 1 真实条文
    assert len(docs) == 10
    total_queries = sum(len(d.queries) for d in docs)
    assert total_queries == 21


def test_metrics_are_self_consistent() -> None:
    returned = [("doc", 2), ("doc", 0), ("doc", 1)]
    relevant = [("doc", 0)]
    r = compute_metrics("q", "test", returned, relevant, ks=(1, 3))

    assert r.recall_at_k[1] == 0.0 # 相关块不在第 1 位
    assert r.recall_at_k[3] == 1.0 # 相关块在第 2 位，前 3 召回
    assert r.mrr == 0.5 # 相关块排第 2 → 1/2


def test_empty_relevant_is_zero_not_error() -> None:
    r = compute_metrics("q", "test", [("d", 0)], [], ks=(1, 3))
    assert r.recall_at_k[1] == 0.0
    assert r.mrr == 0.0
    assert r.ndcg_at_k[1] == 0.0


def test_evaluate_queries_averages_correctly() -> None:
    a = compute_metrics("a", "x", [("d", 0)], [("d", 0)], ks=(1,))
    b = compute_metrics("b", "y", [("d", 1), ("d", 0)], [("d", 0)], ks=(1,))
    summary = evaluate_queries([a, b], ks=(1,))

    assert summary.query_count == 2
    assert summary.recall_at_k[1] == 0.5 # (1.0 + 0.0) / 2
    assert summary.mrr == 0.75 # (1.0 + 0.5) / 2


def test_offline_baseline_meets_minimum_quality() -> None:
    """离线基线必须满足下限：跨主题与真实条文简单查询应 100% R@1，整体 Recall@3 全召回。

    这里锁的是「检索链路没有回归」，不是「离线哈希的语义上限」——同主题细粒度
    查询（labor-q1/q2）在离线哈希下 R@1 偏低是已知且合理的（见 eval/corpus.py 注释）。
    """
    payload = asyncio.run(run_eval())

    assert payload["query_count"] == 21
    assert payload["recall_at_k"]["1"] >= 0.7
    assert payload["recall_at_k"]["3"] == 1.0
    assert payload["mrr"] >= 0.85

    # 跨主题 + 真实条文（8 + 4 = 12 条简单查询）必须 R@1 全中
    simple_ids = {
        f"{name}-q1"
        for name in (
            "economics",
            "medicine",
            "history",
            "cooking",
            "astronomy",
            "computer",
            "music",
            "geography",
        )
    } | {"real-q1", "real-q2", "real-q3", "real-q4"}
    for q in payload["per_query"]:
        if q["query_id"] in simple_ids:
            assert q["recall_at_k"]["1"] == 1.0, f"{q['query_id']} 应 R@1=1.0"


@pytest.mark.skipif(
    not os.getenv("DASHSCOPE_API_KEY"),
    reason="真实 provider 评测需要 DASHSCOPE_API_KEY（本地手动跑，不进 CI）",
)
def test_real_provider_semantic_quality_smoke(monkeypatch: pytest.MonkeyPatch) -> None:
    """真实语义模型冒烟：仅当环境里注入 DASHSCOPE_API_KEY 时运行。

    锁住「真实 embedding/rerank 显著优于离线哈希」这一关键事实——语义模型应把
    同主题细粒度查询（labor-q1/q2）的 Recall@1 从 0 拉到 1.0。CI 无 key，自动 skip。
    """
    monkeypatch.setenv("DOCQA_EVAL_EMBEDDING", "openai")
    monkeypatch.setenv("DOCQA_EMBEDDING_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1")
    monkeypatch.setenv("DOCQA_EMBEDDING_API_KEY", os.environ["DASHSCOPE_API_KEY"])
    monkeypatch.setenv("DOCQA_EMBEDDING_MODEL", "text-embedding-v3")
    monkeypatch.setenv("DOCQA_EVAL_RERANK", "openai")
    monkeypatch.setenv(
        "DOCQA_RERANK_BASE_URL",
        "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank",
    )
    monkeypatch.setenv("DOCQA_RERANK_API_KEY", os.environ["DASHSCOPE_API_KEY"])
    monkeypatch.setenv("DOCQA_RERANK_MODEL", "gte-rerank-v2")

    payload = asyncio.run(run_eval())

    assert payload["recall_at_k"]["1"] >= 0.95
    assert payload["mrr"] == 1.0

    # 决定性证据：语义模型把离线哈希下 R@1=0 的同主题细粒度查询拉到 1.0
    by_id = {q["query_id"]: q for q in payload["per_query"]}
    assert by_id["labor-q1"]["recall_at_k"]["1"] == 1.0
    assert by_id["labor-q2"]["recall_at_k"]["1"] == 1.0
