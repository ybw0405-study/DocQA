from __future__ import annotations

import math
from dataclasses import dataclass
from typing import Protocol

import httpx


class RerankError(Exception):
    pass


class RerankTimeout(RerankError):
    pass


class RerankUnavailable(RerankError):
    pass


class RerankOutputInvalid(RerankError):
    pass


@dataclass(frozen=True)
class RerankedItem:
    """名次载体：`index` 指回传入 `documents` 的原下标，`score` 是第三方相关性分。

    `score` 不得对外冒充相似度，只用于决定名次：不同 rerank 厂商的分值域与归一化方式
    各不相同，直接暴露会让调用方误以为是可比的相似度。
    """

    index: int
    score: float


class RerankProvider(Protocol):
    model: str

    async def rerank(self, query: str, documents: list[str], timeout_s: float) -> list[RerankedItem]: ...


class NoopRerank:
    """离线形态：原样返回融合次序，零外呼，保证默认配置与 CI 不联网。"""

    def __init__(self, model: str = "offline-noop") -> None:
        self.model = model

    async def rerank(self, query: str, documents: list[str], timeout_s: float) -> list[RerankedItem]:
        return [RerankedItem(index, 0.0) for index in range(len(documents))]


class DashScopeRerankProvider:
    """DashScope 原生 rerank 端点（E46：该能力不在 OpenAI 兼容面上，形态也不同）。

    正文由调用方负责掩码后再传入；本类不记录请求与响应内容。
    """

    def __init__(
        self,
        base_url: str,
        api_key: str,
        model: str,
        client: httpx.AsyncClient | None = None,
    ) -> None:
        self.model = model
        self._base_url = base_url.rstrip("/")
        self._api_key = api_key
        # CON-2：共享 client，注入的由调用方所有、自建的由 aclose() 释放。
        self._client = (
            client
            if client is not None
            else httpx.AsyncClient(limits=httpx.Limits(max_connections=20, max_keepalive_connections=10))
        )
        self._owns_client = client is None

    async def rerank(self, query: str, documents: list[str], timeout_s: float) -> list[RerankedItem]:
        if not documents:
            return []
        payload = {
            "model": self.model,
            "input": {"query": query, "documents": documents},
            "parameters": {"return_documents": False, "top_n": len(documents)},
        }
        headers = {"Content-Type": "application/json"}
        if self._api_key:
            headers["Authorization"] = f"Bearer {self._api_key}"
        client = self._client
        try:
            response = await client.post(
                self._base_url,
                json=payload,
                headers=headers,
                timeout=httpx.Timeout(max(0.1, timeout_s), connect=min(3.0, max(0.1, timeout_s))),
            )
        except httpx.TimeoutException as exc:
            raise RerankTimeout("rerank request timed out") from exc
        except httpx.HTTPError as exc:
            raise RerankUnavailable("rerank request failed") from exc
        if response.status_code >= 400:
            raise RerankUnavailable(f"rerank provider returned {response.status_code}")
        try:
            results = response.json()["output"]["results"]
            items = [RerankedItem(index=int(row["index"]), score=float(row["relevance_score"])) for row in results]
        except (KeyError, IndexError, TypeError, ValueError) as exc:
            raise RerankOutputInvalid("rerank response is malformed") from exc
        if len({item.index for item in items}) != len(items):
            raise RerankOutputInvalid("rerank response has duplicate indexes")
        if any(item.index < 0 or item.index >= len(documents) for item in items):
            raise RerankOutputInvalid("rerank response has an out-of-range index")
        if any(not math.isfinite(item.score) for item in items):
            raise RerankOutputInvalid("rerank response has a non-finite score")
        return sorted(items, key=lambda item: (-item.score, item.index))

    async def aclose(self) -> None:
        if self._owns_client:
            await self._client.aclose()
