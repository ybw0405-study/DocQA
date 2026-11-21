from __future__ import annotations

from docqa_ai.governance.experiment_budget import ExperimentBudgetStore
from docqa_ai.providers.embeddings import EmbeddingProvider
from docqa_ai.providers.rerank import RerankedItem, RerankProvider
from docqa_ai.providers.tokens import estimate_tokens

# `providers/tokens.py` 的 `estimate_messages_tokens` 给每条消息 +4 记的是请求封装开销；
# embedding / rerank 的封装开销（`model` / `dimensions` / `input` 数组标记）与一条消息同量级，
# 沿用同一个常数而不是另造一个数。
REQUEST_OVERHEAD_TOKENS = 4


class ExperimentBudgetExhausted(Exception):
    """额度门禁在**任何字节离开本机之前**拒绝了一次外呼（D-118 ②）。

    它是进程内异常，不映射新错误码：各调用点把它翻译成自己已有的降级或失败形态（D-119 / D-120）。
    """

    def __init__(self, operation: str) -> None:
        super().__init__(f"experiment budget exhausted before {operation}")
        self.operation = operation


class BudgetedEmbeddingProvider:
    """包在真实 embedding Provider 外层的额度门禁；离线 Provider 不经过这里。

    只做预订、不做回补：运行时读不到真实 `usage` 是 D-115 ② 的裁决（探针 E56 证明响应带
    `usage.total_tokens`，但那是**一次性观测**，把它接进运行时就要改 Provider 的返回类型）。
    因此账本停留在估算上界，宁可少花不多花。
    """

    def __init__(self, provider: EmbeddingProvider, store: ExperimentBudgetStore) -> None:
        self._provider = provider
        self._store = store
        self.model = provider.model
        self.dimensions = provider.dimensions
        self.max_batch_rows = provider.max_batch_rows

    async def embed(self, texts: list[str], timeout_s: float) -> list[list[float]]:
        reserved = sum(estimate_tokens(text) for text in texts) + REQUEST_OVERHEAD_TOKENS
        if not await self._store.authorize_call(reserved):
            raise ExperimentBudgetExhausted("embedding")
        return await self._provider.embed(texts, timeout_s)

    async def aclose(self) -> None:
        """把 CON-2 的共享 client 关闭透传到被包装的真实 Provider。"""
        closer = getattr(self._provider, "aclose", None)
        if closer is not None:
            await closer()


class BudgetedRerankProvider:
    """rerank 与 embedding 同形：名次是增量能力，花的是真实额度，所以必须进同一本账（D-106）。

    候选正文在这里已经是掩码文本（D-116 在 `rag/service.py` 的名次决定之前完成），
    估算因此与外发字节一一对应；`NoopRerank` 不经过这里。
    """

    def __init__(self, provider: RerankProvider, store: ExperimentBudgetStore) -> None:
        self._provider = provider
        self._store = store
        self.model = provider.model

    async def rerank(self, query: str, documents: list[str], timeout_s: float) -> list[RerankedItem]:
        if not documents:
            return []
        reserved = estimate_tokens(query) + sum(estimate_tokens(text) for text in documents)
        reserved += REQUEST_OVERHEAD_TOKENS
        if not await self._store.authorize_call(reserved):
            raise ExperimentBudgetExhausted("rerank")
        return await self._provider.rerank(query, documents, timeout_s)

    async def aclose(self) -> None:
        """把 CON-2 的共享 client 关闭透传到被包装的真实 Provider。"""
        closer = getattr(self._provider, "aclose", None)
        if closer is not None:
            await closer()
