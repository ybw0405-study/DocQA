from __future__ import annotations

import json
from typing import Any

import httpx
import pytest
from pydantic import ValidationError

from docqa_ai.config import Settings
from docqa_ai.providers.rerank import (
    DashScopeRerankProvider,
    NoopRerank,
    RerankOutputInvalid,
    RerankTimeout,
    RerankUnavailable,
)
from tests.conftest import make_settings

NATIVE_URL = "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank"
DOCUMENTS = ["相关段落", "无关段落甲", "无关段落乙"]


def provider_returning(payload: object, status: int = 200) -> DashScopeRerankProvider:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(status, json=payload)  # type: ignore[arg-type]

    transport = httpx.MockTransport(handler)
    return DashScopeRerankProvider(
        NATIVE_URL, "test-key", "gte-rerank-v2", client=httpx.AsyncClient(transport=transport)
    )


def provider_raising(exc: Exception) -> DashScopeRerankProvider:
    async def handler(request: httpx.Request) -> httpx.Response:
        raise exc

    transport = httpx.MockTransport(handler)
    return DashScopeRerankProvider(
        NATIVE_URL, "test-key", "gte-rerank-v2", client=httpx.AsyncClient(transport=transport)
    )


def results_payload(rows: list[dict[str, Any]]) -> dict[str, Any]:
    return {"output": {"results": rows}, "usage": {"total_tokens": 79}}


async def test_noop_keeps_the_fusion_order_without_any_request() -> None:
    """默认形态必须原样返回，否则「rerank 没参与」与「rerank 全失败」在离线跑里不可区分（D-103 的前提）。"""

    items = await NoopRerank().rerank(query="问题", documents=DOCUMENTS, timeout_s=1.0)
    assert [item.index for item in items] == [0, 1, 2]
    assert [item.score for item in items] == [0.0, 0.0, 0.0]


async def test_noop_on_empty_documents_returns_empty() -> None:
    assert await NoopRerank().rerank("问题", [], timeout_s=1.0) == []


async def test_real_provider_uses_the_native_request_shape() -> None:
    """E46 实测的形态：嵌套 input / parameters，响应 output.results；不是 OpenAI 的 choices 或 data。"""

    seen: dict[str, Any] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["method"] = request.method
        seen["url"] = str(request.url)
        seen["body"] = json.loads(request.content)
        seen["auth"] = request.headers.get("authorization", "")
        return httpx.Response(200, json=results_payload([{"index": 0, "relevance_score": 0.45}]))

    provider = DashScopeRerankProvider(
        NATIVE_URL, "test-key", "gte-rerank-v2", client=httpx.AsyncClient(transport=httpx.MockTransport(handler))
    )
    await provider.rerank("问题", DOCUMENTS, timeout_s=1.0)
    assert seen["method"] == "POST"
    assert seen["url"] == NATIVE_URL
    assert seen["auth"] == "Bearer test-key"
    body = seen["body"]
    assert body["model"] == "gte-rerank-v2"
    assert body["input"] == {"query": "问题", "documents": DOCUMENTS}
    assert body["parameters"]["top_n"] == len(DOCUMENTS)
    assert body["parameters"]["return_documents"] is False


async def test_empty_documents_short_circuit_before_any_http_call() -> None:
    """空候选不该产生一次真实外呼，那是要消耗额度的。"""

    calls = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        return httpx.Response(200, json=results_payload([]))

    provider = DashScopeRerankProvider(
        NATIVE_URL, "test-key", "gte-rerank-v2", client=httpx.AsyncClient(transport=httpx.MockTransport(handler))
    )
    assert await provider.rerank("问题", [], timeout_s=1.0) == []
    assert calls == 0


async def test_items_are_resorted_by_score_and_map_back_to_input_indexes() -> None:
    """服务端已按分降序返回，但名次只在我们自己重排后才可信（E47 的 order=[0,2,1]）。"""

    provider = provider_returning(
        results_payload(
            [
                {"index": 0, "relevance_score": 0.4525},
                {"index": 2, "relevance_score": 0.0061},
                {"index": 1, "relevance_score": 0.006},
            ]
        )
    )
    items = await provider.rerank("问题", DOCUMENTS, timeout_s=1.0)
    assert [item.index for item in items] == [0, 2, 1]
    assert [item.score for item in items] == [0.4525, 0.0061, 0.006]


async def test_tie_breaking_is_stable_on_index() -> None:
    provider = provider_returning(
        results_payload(
            [
                {"index": 2, "relevance_score": 0.5},
                {"index": 0, "relevance_score": 0.5},
                {"index": 1, "relevance_score": 0.1},
            ]
        )
    )
    items = await provider.rerank("问题", DOCUMENTS, timeout_s=1.0)
    assert [item.index for item in items] == [0, 2, 1]


async def test_http_error_maps_to_rerank_unavailable() -> None:
    provider = provider_returning({"message": "boom"}, status=500)
    with pytest.raises(RerankUnavailable, match="rerank provider returned 500"):
        await provider.rerank("问题", DOCUMENTS, timeout_s=1.0)


async def test_transport_error_maps_to_rerank_unavailable() -> None:
    provider = provider_raising(httpx.ConnectError("down"))
    with pytest.raises(RerankUnavailable, match="rerank request failed"):
        await provider.rerank("问题", DOCUMENTS, timeout_s=1.0)


async def test_timeout_maps_to_rerank_timeout() -> None:
    """超时必须是独立异常类，D-103 的降级与 D-112 的一次重试都靠它判别。"""

    provider = provider_raising(httpx.ReadTimeout("slow"))
    with pytest.raises(RerankTimeout, match="rerank request timed out"):
        await provider.rerank("问题", DOCUMENTS, timeout_s=1.0)


@pytest.mark.parametrize(
    "payload",
    [
        {"unexpected": {}},
        {"output": {}},
        {"output": {"results": [{"index": 0}]}},
        {"output": {"results": [{"relevance_score": 0.5}]}},
        {"output": {"results": [{"index": "not-a-number", "relevance_score": 0.5}]}},
    ],
    ids=["no-output", "no-results", "no-score", "no-index", "index-not-number"],
)
async def test_malformed_responses_fail_closed(payload: dict[str, Any]) -> None:
    provider = provider_returning(payload)
    with pytest.raises(RerankOutputInvalid, match="rerank response is malformed"):
        await provider.rerank("问题", DOCUMENTS, timeout_s=1.0)


async def test_out_of_range_index_is_rejected() -> None:
    """越界下标一旦被信任就会让名次指向不存在的候选，必须在 Provider 里挡住。"""

    provider = provider_returning(results_payload([{"index": 3, "relevance_score": 0.9}]))
    with pytest.raises(RerankOutputInvalid, match="out-of-range index"):
        await provider.rerank("问题", DOCUMENTS, timeout_s=1.0)


async def test_duplicate_indexes_are_rejected() -> None:
    provider = provider_returning(
        results_payload(
            [
                {"index": 0, "relevance_score": 0.9},
                {"index": 0, "relevance_score": 0.4},
            ]
        )
    )
    with pytest.raises(RerankOutputInvalid, match="duplicate indexes"):
        await provider.rerank("问题", DOCUMENTS, timeout_s=1.0)


async def test_non_finite_score_is_rejected() -> None:
    """裸 NaN 字面量是第三方可能给出的畸形响应体，`json=` 序列化本身拒绝它，所以直接给字节。"""

    async def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            content=b'{"output":{"results":[{"index":0,"relevance_score":NaN}]}}',
            headers={"content-type": "application/json"},
        )

    transport = httpx.MockTransport(handler)
    provider = DashScopeRerankProvider(
        NATIVE_URL, "test-key", "gte-rerank-v2", client=httpx.AsyncClient(transport=transport)
    )
    with pytest.raises(RerankOutputInvalid, match="non-finite score"):
        await provider.rerank("问题", DOCUMENTS, timeout_s=1.0)


def test_offline_defaults_need_no_rerank_configuration() -> None:
    settings = make_settings()
    assert settings.rerank_provider == "offline"
    assert settings.rerank_base_url == ""
    assert settings.rerank_model == "offline-noop"


def test_dashscope_rerank_without_base_url_is_rejected() -> None:
    with pytest.raises(ValidationError, match="rerank_base_url is required"):
        make_settings(rerank_provider="dashscope", experiment_budget=10, experiment_token_budget=10000)


def test_dashscope_rerank_without_api_key_is_rejected() -> None:
    with pytest.raises(ValidationError, match="rerank_api_key is required"):
        make_settings(
            rerank_provider="dashscope",
            rerank_base_url=NATIVE_URL,
            experiment_budget=10,
            experiment_token_budget=10000,
        )


def test_dashscope_rerank_consumes_the_experiment_budget_guard() -> None:
    """rerank 是外呼，切真却不声明额度必须启动失败（D-106 把 rerank 也算进真实 Provider）。"""

    with pytest.raises(ValidationError, match="experiment_budget"):
        make_settings(rerank_provider="dashscope", rerank_base_url=NATIVE_URL, rerank_api_key="k")


def test_dashscope_rerank_with_limits_starts_fine() -> None:
    settings = make_settings(
        rerank_provider="dashscope",
        rerank_base_url=NATIVE_URL,
        rerank_api_key="secret-value",
        experiment_budget=50,
        experiment_token_budget=100000,
    )
    assert settings.rerank_provider == "dashscope"


def test_redacted_summary_reports_rerank_shape_and_holds_no_key() -> None:
    settings = make_settings(
        rerank_provider="dashscope",
        rerank_base_url=NATIVE_URL,
        rerank_model="gte-rerank-v2",
        rerank_api_key="secret-value",
        rerank_timeout_ms=2500,
        experiment_budget=50,
        experiment_token_budget=100000,
    )
    summary = settings.redacted_summary()
    assert summary["rerank_provider"] == "dashscope"
    assert summary["rerank_model"] == "gte-rerank-v2"
    assert summary["rerank_timeout_ms"] == 2500
    assert summary["rerank_credentials_configured"] is True
    assert "rerank_api_key" not in summary
    assert "secret-value" not in str(summary)


def test_rerank_keys_come_from_the_environment(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("DOCQA_AI_RERANK_PROVIDER", "dashscope")
    monkeypatch.setenv("DOCQA_AI_RERANK_BASE_URL", NATIVE_URL)
    monkeypatch.setenv("DOCQA_AI_RERANK_API_KEY", "env-key")
    monkeypatch.setenv("DOCQA_AI_RERANK_MODEL", "qwen3-rerank")
    monkeypatch.setenv("DOCQA_AI_EXPERIMENT_BUDGET", "50")
    monkeypatch.setenv("DOCQA_AI_EXPERIMENT_TOKEN_BUDGET", "100000")
    settings = Settings(_env_file=None)
    assert settings.rerank_provider == "dashscope"
    assert settings.rerank_model == "qwen3-rerank"
    assert settings.rerank_api_key.get_secret_value() == "env-key"


def test_rerank_timeout_is_bounded() -> None:
    with pytest.raises(ValidationError):
        make_settings(rerank_timeout_ms=0)
    with pytest.raises(ValidationError):
        make_settings(rerank_timeout_ms=16000)
