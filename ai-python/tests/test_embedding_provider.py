"""批量上限与维度声明是 Provider 的自报属性，不是业务配置。

依据为 §1.8.1 的 E43 / E45 与 -2 前置探针 R1…R6（真实端点实测）：
11 行请求固定返回 400 且与是否携带 `dimensions` 无关；`dimensions` 被接受且生效
（1024→1024、768→768、v3 512→512）；`dimensions=0` 同样 400。
"""

from __future__ import annotations

import json
from typing import Any

import httpx
import pytest

from docqa_ai.providers.embeddings import (
    OPENAI_COMPATIBLE_MAX_BATCH_ROWS,
    UNRESTRICTED_BATCH_ROWS,
    OfflineEmbeddingProvider,
    OpenAIEmbeddingProvider,
)

BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1"


def embedding_response(rows: int, dimensions: int) -> dict[str, Any]:
    return {
        "data": [{"index": index, "embedding": [0.0] * dimensions, "object": "embedding"} for index in range(rows)],
        "model": "text-embedding-v4",
        "object": "list",
        "usage": {"total_tokens": 23 * rows},
    }


def capturing_provider(dimensions: int = 1024, **kwargs: Any) -> tuple[OpenAIEmbeddingProvider, dict[str, Any]]:
    captured: dict[str, Any] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured["body"] = json.loads(request.content)
        captured["auth"] = request.headers.get("authorization", "")
        return httpx.Response(200, json=embedding_response(len(captured["body"]["input"]), dimensions))

    client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    provider = OpenAIEmbeddingProvider(BASE_URL, "test-key", "text-embedding-v4", dimensions, client=client, **kwargs)
    return provider, captured


def test_offline_provider_is_never_the_batch_bottleneck() -> None:
    """本地特征哈希没有端点，任何合法配置值都不该被它压低（的「宽松上界」）。"""

    provider = OfflineEmbeddingProvider()
    assert provider.max_batch_rows == UNRESTRICTED_BATCH_ROWS
    assert provider.max_batch_rows >= 128, "embedding_batch_size 的配置上界是 128，自报值不得低于它"


def test_openai_compatible_default_matches_the_measured_endpoint_limit() -> None:
    """E45：真实端点 10 行通过、11 行 400，默认值就是这条实测线。"""

    provider = OpenAIEmbeddingProvider(BASE_URL, "test-key", "text-embedding-v4", 1024)
    assert provider.max_batch_rows == OPENAI_COMPATIBLE_MAX_BATCH_ROWS == 10


@pytest.mark.parametrize("value", [0, -1])
def test_non_positive_limit_is_rejected_at_construction(value: int) -> None:
    """0 会让 worker 的 min() 得出 batch_size=0，range 步长为 0 直接抛 ValueError——
    那会在解析路径上炸成内部错误，必须在构造时就拒掉。"""

    with pytest.raises(ValueError, match="max_batch_rows"):
        OpenAIEmbeddingProvider(BASE_URL, "test-key", "text-embedding-v4", 1024, max_batch_rows=value)


def test_a_larger_limit_is_a_configuration_choice_not_a_override(value: int = 32) -> None:
    """换成允许更大批次的 OpenAI 兼容端点时，调用方只改构造参数，worker 逻辑不动。"""

    provider = OpenAIEmbeddingProvider(BASE_URL, "test-key", "text-embedding-v4", 1024, max_batch_rows=value)
    assert provider.max_batch_rows == value


async def test_request_declares_dimensions_instead_of_relying_on_the_endpoint_default() -> None:
    """E43：不发送 dimensions 时端点「恰好」返回 1024，那是默认值不是承诺。"""

    provider, captured = capturing_provider()
    vectors = await provider.embed(["段落甲", "段落乙"], timeout_s=1.0)
    assert captured["body"]["dimensions"] == 1024
    assert captured["body"]["model"] == "text-embedding-v4"
    assert captured["body"]["input"] == ["段落甲", "段落乙"]
    assert captured["auth"] == "Bearer test-key"
    assert len(vectors) == 2


async def test_declared_dimensions_follow_the_provider() -> None:
    """R4 实测 dimensions=768 返回 768 维：请求里的值必须来自 Provider 自身，写死 1024 就成了假约束。"""

    provider, captured = capturing_provider(dimensions=768)
    vectors = await provider.embed(["段落甲"], timeout_s=1.0)
    assert captured["body"]["dimensions"] == 768
    assert len(vectors[0]) == 768
