from __future__ import annotations

import hashlib
import math
import re
from typing import Protocol

import httpx

from docqa_ai.pii import normalize

_WORD = re.compile(r"[a-z0-9]+")
_CJK = re.compile(r"[\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff]")

# 单次 embed 调用的最大行数由 Provider 自报：它是端点属性，不是业务规则。
# 本地特征哈希不受任何端点约束，取值不小于 embedding_batch_size 的配置上界即永不成为瓶颈。
UNRESTRICTED_BATCH_ROWS = 128
# E45 实测 DashScope 兼容面 11 行返回 400、10 行通过；本次探针 R2 进一步证明
# 携带 dimensions 的 11 行请求同样 400，即该上限只按行数计，与请求参数无关。
OPENAI_COMPATIBLE_MAX_BATCH_ROWS = 10


class EmbeddingError(Exception):
    pass


class EmbeddingTimeout(EmbeddingError):
    pass


class EmbeddingUnavailable(EmbeddingError):
    pass


class EmbeddingProvider(Protocol):
    model: str
    dimensions: int
    max_batch_rows: int

    async def embed(self, texts: list[str], timeout_s: float) -> list[list[float]]: ...


class OfflineEmbeddingProvider:
    """Deterministic feature-hashing embedding for tests and offline demos.

    It is not a semantic model; it produces stable vectors so retrieval, budget and audit
    paths can be exercised without network access or model downloads.
    """

    def __init__(self, model: str = "offline-hashing-1024", dimensions: int = 1024) -> None:
        self.model = model
        self.dimensions = dimensions
        self.max_batch_rows = UNRESTRICTED_BATCH_ROWS

    async def embed(self, texts: list[str], timeout_s: float) -> list[list[float]]:
        return [self._embed_one(text) for text in texts]

    def _embed_one(self, text: str) -> list[float]:
        vector = [0.0] * self.dimensions
        for token, weight in self._tokens(text):
            digest = hashlib.blake2b(token.encode("utf-8"), digest_size=8).digest()
            value = int.from_bytes(digest, "big")
            index = value % self.dimensions
            sign = 1.0 if value & 1 else -1.0
            vector[index] += sign * weight
        norm = math.sqrt(sum(value * value for value in vector))
        if norm == 0.0:
            vector[0] = 1.0
            return vector
        return [value / norm for value in vector]

    @staticmethod
    def _tokens(text: str) -> list[tuple[str, float]]:
        normalized = normalize(text).lower()
        tokens: list[tuple[str, float]] = [(word, 1.0) for word in _WORD.findall(normalized)]
        cjk_chars = _CJK.findall(normalized)
        tokens.extend((char, 1.0) for char in cjk_chars)
        tokens.extend((cjk_chars[i] + cjk_chars[i + 1], 0.5) for i in range(len(cjk_chars) - 1))
        return tokens


class OpenAIEmbeddingProvider:
    """OpenAI-compatible /embeddings client. The API key is never logged."""

    def __init__(
        self,
        base_url: str,
        api_key: str,
        model: str,
        dimensions: int,
        client: httpx.AsyncClient | None = None,
        max_batch_rows: int = OPENAI_COMPATIBLE_MAX_BATCH_ROWS,
    ) -> None:
        if max_batch_rows < 1:
            raise ValueError("max_batch_rows must be at least 1")
        self.model = model
        self.dimensions = dimensions
        self.max_batch_rows = max_batch_rows
        self._base_url = base_url.rstrip("/")
        self._api_key = api_key
        # CON-2：构造时持有共享 client，避免每次 embed 都新建+销毁（含 TLS 握手）。
        # 注入的 client 由调用方所有；自建的由 aclose() 在应用关闭时释放。
        self._client = (
            client
            if client is not None
            else httpx.AsyncClient(limits=httpx.Limits(max_connections=20, max_keepalive_connections=10))
        )
        self._owns_client = client is None

    async def embed(self, texts: list[str], timeout_s: float) -> list[list[float]]:
        if not texts:
            return []
        headers = {"Content-Type": "application/json"}
        if self._api_key:
            headers["Authorization"] = f"Bearer {self._api_key}"
        # dimensions 必须显式发送：E43 实测不发送时 v4/v3 都「恰好」返回 1024 维，
        # 那是端点默认值而非承诺。声明后它成为请求约束，与下面的形状校验闭环。
        payload = {"model": self.model, "input": texts, "dimensions": self.dimensions}
        client = self._client
        try:
            response = await client.post(
                f"{self._base_url}/embeddings",
                json=payload,
                headers=headers,
                timeout=httpx.Timeout(max(0.1, timeout_s), connect=min(3.0, max(0.1, timeout_s))),
            )
        except httpx.TimeoutException as exc:
            raise EmbeddingTimeout("embedding request timed out") from exc
        except httpx.HTTPError as exc:
            raise EmbeddingUnavailable("embedding request failed") from exc
        if response.status_code >= 400:
            raise EmbeddingUnavailable(f"embedding provider returned {response.status_code}")
        try:
            body = response.json()
            rows = sorted(body["data"], key=lambda row: row["index"])
            vectors = [[float(value) for value in row["embedding"]] for row in rows]
        except (KeyError, TypeError, ValueError) as exc:
            raise EmbeddingUnavailable("embedding response is malformed") from exc
        if len(vectors) != len(texts) or any(len(vector) != self.dimensions for vector in vectors):
            raise EmbeddingUnavailable("embedding response has an unexpected shape")
        return vectors

    async def aclose(self) -> None:
        if self._owns_client:
            await self._client.aclose()


def cosine_similarity(left: list[float], right: list[float]) -> float:
    if len(left) != len(right) or not left:
        return 0.0
    dot = sum(a * b for a, b in zip(left, right, strict=True))
    left_norm = math.sqrt(sum(value * value for value in left))
    right_norm = math.sqrt(sum(value * value for value in right))
    if left_norm == 0.0 or right_norm == 0.0:
        return 0.0
    return max(-1.0, min(1.0, dot / (left_norm * right_norm)))
