from __future__ import annotations

import dataclasses

import pytest

from docqa_ai.governance.budget import BudgetUnavailable
from docqa_ai.governance.experiment_budget import InMemoryExperimentBudgetStore
from docqa_ai.governance.experiment_egress import (
    REQUEST_OVERHEAD_TOKENS,
    BudgetedEmbeddingProvider,
    BudgetedRerankProvider,
    ExperimentBudgetExhausted,
)
from docqa_ai.providers.embeddings import EmbeddingError
from docqa_ai.providers.rerank import RerankedItem
from docqa_ai.providers.tokens import estimate_tokens


class RecordingEmbedder:
    """记录实收文本的 embedding 替身：`calls` 是「拒绝即零外呼」这条判据的载体。"""

    model = "text-embedding-v3"
    dimensions = 1024
    max_batch_rows = 10

    def __init__(self, error: Exception | None = None) -> None:
        self.calls: list[list[str]] = []
        self._error = error

    async def embed(self, texts: list[str], timeout_s: float) -> list[list[float]]:
        self.calls.append(list(texts))
        if self._error is not None:
            raise self._error
        return [[0.0] * self.dimensions for _ in texts]


class RecordingReranker:
    model = "gte-rerank-v2"

    def __init__(self, error: Exception | None = None) -> None:
        self.calls: list[tuple[str, tuple[str, ...]]] = []
        self._error = error

    async def rerank(self, query: str, documents: list[str], timeout_s: float) -> list[RerankedItem]:
        self.calls.append((query, tuple(documents)))
        if self._error is not None:
            raise self._error
        return [RerankedItem(index, 0.5) for index in reversed(range(len(documents)))]


class ExplodingLedger:
    """账本自身读写失败：装饰器必须把 BudgetUnavailable 原样交给调用点翻译（竞态形态之一）。"""

    async def snapshot(self):  # pragma: no cover - must not be reached
        raise AssertionError("the decorators must not read a snapshot")

    async def authorize_call(self, estimated_tokens: int) -> bool:
        raise BudgetUnavailable("redis experiment budget write failed")

    async def settle_call(self, reserved: int, actual: int):  # pragma: no cover - must not be reached
        raise AssertionError("no settlement may follow a refused reservation")


def reservation(texts: list[str], query: str = "") -> int:
    total = sum(estimate_tokens(text) for text in texts)
    if query:
        total += estimate_tokens(query)
    return total + REQUEST_OVERHEAD_TOKENS


async def test_embedding_reservation_is_the_row_sum_plus_one_request_overhead() -> None:
    """一次 `embed` 是一趟 HTTP，因此预订只加一次封装开销，不是每行加一次。"""

    inner = RecordingEmbedder()
    store = InMemoryExperimentBudgetStore(10, 100_000)
    texts = ["Python 的 GIL 是什么？", "chunk text"]

    vectors = await BudgetedEmbeddingProvider(inner, store).embed(texts, 1.0)

    assert len(vectors) == 2
    assert inner.calls == [texts]
    snapshot = await store.snapshot()
    assert snapshot.calls_used == 1
    assert snapshot.tokens_used == reservation(texts)


async def test_embedding_refusal_raises_before_any_byte_leaves() -> None:
    inner = RecordingEmbedder()
    store = InMemoryExperimentBudgetStore(1, 100_000)
    embedder = BudgetedEmbeddingProvider(inner, store)
    await embedder.embed(["first"], 1.0)

    with pytest.raises(ExperimentBudgetExhausted):
        await embedder.embed(["second"], 1.0)

    assert inner.calls == [["first"]]
    snapshot = await store.snapshot()
    assert snapshot.calls_used == 1
    assert snapshot.tokens_used == reservation(["first"])


@pytest.mark.parametrize(("call_limit", "token_limit"), [(0, 100_000), (10, 0), (0, 0)])
async def test_unarmed_quota_refuses_the_first_call(call_limit: int, token_limit: int) -> None:
    """额度 0 是 fail-closed，不是「不限」：启动守卫已拒绝这种组合上线，这里钉住装饰器的行为。"""

    inner = RecordingEmbedder()
    embedder = BudgetedEmbeddingProvider(inner, InMemoryExperimentBudgetStore(call_limit, token_limit))
    with pytest.raises(ExperimentBudgetExhausted):
        await embedder.embed(["any"], 1.0)
    assert inner.calls == []


async def test_failed_embedding_keeps_the_reservation() -> None:
    """只预订不回补：端点报错时钱可能已经花了，宁可多记也不能少记（②）。"""

    inner = RecordingEmbedder(error=EmbeddingError("endpoint returned 500"))
    store = InMemoryExperimentBudgetStore(10, 100_000)
    with pytest.raises(EmbeddingError):
        await BudgetedEmbeddingProvider(inner, store).embed(["text"], 1.0)
    assert (await store.snapshot()).calls_used == 1


async def test_decorated_embedder_passes_provider_identity_through() -> None:
    """worker 的批量上限取 `min(配置, provider.max_batch_rows)`，属性不透传就会退回默认值。"""

    inner = RecordingEmbedder()
    embedder = BudgetedEmbeddingProvider(inner, InMemoryExperimentBudgetStore(10, 100_000))
    assert embedder.model == inner.model
    assert embedder.dimensions == inner.dimensions
    assert embedder.max_batch_rows == inner.max_batch_rows


async def test_rerank_reservation_covers_query_and_every_candidate() -> None:
    inner = RecordingReranker()
    store = InMemoryExperimentBudgetStore(10, 100_000)
    documents = ["候选一", "候选二"]

    items = await BudgetedRerankProvider(inner, store).rerank("查询", documents, 1.0)

    assert [item.index for item in items] == [1, 0]
    assert inner.calls == [("查询", tuple(documents))]
    snapshot = await store.snapshot()
    assert snapshot.calls_used == 1
    assert snapshot.tokens_used == reservation(documents, query="查询")


async def test_empty_candidate_set_short_circuits_before_the_ledger() -> None:
    """没有候选就没有外呼，也不该占一次额度。"""

    inner = RecordingReranker()
    store = InMemoryExperimentBudgetStore(10, 100_000)
    assert await BudgetedRerankProvider(inner, store).rerank("查询", [], 1.0) == []
    assert inner.calls == []
    assert (await store.snapshot()).calls_used == 0


async def test_rerank_refusal_never_calls_the_provider() -> None:
    inner = RecordingReranker()
    store = InMemoryExperimentBudgetStore(1, 100_000)
    reranker = BudgetedRerankProvider(inner, store)
    await reranker.rerank("查询", ["第一批"], 1.0)

    with pytest.raises(ExperimentBudgetExhausted):
        await reranker.rerank("查询", ["第二批"], 1.0)

    assert [call[1] for call in inner.calls] == [("第一批",)]


def test_decorated_reranker_keeps_the_model_name() -> None:
    inner = RecordingReranker()
    reranker = BudgetedRerankProvider(inner, InMemoryExperimentBudgetStore(10, 100_000))
    assert reranker.model == inner.model


async def test_ledger_failure_is_not_swallowed_by_the_gate() -> None:
    embedder = BudgetedEmbeddingProvider(RecordingEmbedder(), ExplodingLedger())
    with pytest.raises(BudgetUnavailable):
        await embedder.embed(["text"], 1.0)

    reranker = BudgetedRerankProvider(RecordingReranker(), ExplodingLedger())
    with pytest.raises(BudgetUnavailable):
        await reranker.rerank("查询", ["候选"], 1.0)


async def test_the_ledger_holds_only_counters_never_the_outgoing_text() -> None:
    """§1.11 的数据面声明：账本键里只有两个整数，外发正文一个字节都不落 Redis（PII 红线）。"""

    inner = RecordingEmbedder()
    store = InMemoryExperimentBudgetStore(10, 100_000)
    await BudgetedEmbeddingProvider(inner, store).embed(["手机号 13800138000"], 1.0)

    assert inner.calls == [["手机号 13800138000"]]
    snapshot = await store.snapshot()
    assert set(dataclasses.asdict(snapshot)) == {"calls_used", "tokens_used", "call_limit", "token_limit"}
    assert "13800138000" not in str(snapshot)
