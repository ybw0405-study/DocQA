"""§4.2 Python 侧 9 项业务指标的契约测试。

判据一律读 **Prometheus 导出文本**而不是读内部字段：跨语言对齐的承诺是「序列名与标签一致」，
读内部对象验证不了后缀规则（Counter 补 `_total`、Histogram 自带 `_seconds` 不得再加后缀）。
"""

from __future__ import annotations

import uuid

import httpx
import pytest

from docqa_ai.api.main import create_app
from docqa_ai.business_metrics import BusinessMetrics
from docqa_ai.protocol.models import DocumentScope
from docqa_ai.providers.embeddings import OfflineEmbeddingProvider
from docqa_ai.providers.planner import PlannerOutput
from docqa_ai.rag.repository import (
    ChunkRecord,
    InMemoryVectorRepository,
    UnavailableVectorRepository,
)
from docqa_ai.rag.service import RetrievalService, RetrievalUnavailable
from tests.conftest import DOCUMENT_ID, make_settings
from tests.test_agent_graph import ScriptedPlanner, block, context, graph

# `observability.md` §4.2 中落在 Python 进程内的 9 项，逐字抄录，不得改写。
DOCUMENTED_SERIES = (
    "docqa_llm_calls_total",
    "docqa_llm_latency_seconds",
    "docqa_agent_rounds",
    "docqa_agent_tool_calls_total",
    "docqa_agent_tokens_total",
    "docqa_agent_budget_exhausted_total",
    "docqa_rag_retrieval_latency_seconds",
    "docqa_vector_search_latency_seconds",
    "docqa_pii_blocked_total",
)


def samples(text: str) -> list[tuple[str, dict[str, str], float]]:
    """极简 Prometheus 文本解析：只处理 `name{labels} value` 与纯 HELP/TYPE 注释。"""

    parsed: list[tuple[str, dict[str, str], float]] = []
    for line in text.splitlines():
        if not line or line.startswith("#"):
            continue
        head, _, raw_value = line.rpartition(" ")
        name, _, label_part = head.partition("{")
        labels: dict[str, str] = {}
        if label_part:
            for pair in label_part.rstrip("}").split(","):
                key, _, raw = pair.partition("=")
                labels[key.strip()] = raw.strip().strip('"')
        parsed.append((name, labels, float(raw_value)))
    return parsed


def value_of(text: str, name: str, **want: str) -> float:
    """把匹配样本求和：Histogram 的 `_count`/`_sum` 与 Counter 单样本都适用。"""

    total = 0.0
    for sample_name, labels, value in samples(text):
        if sample_name != name:
            continue
        if all(labels.get(key) == expected for key, expected in want.items()):
            total += value
    return total


def scope(document_id: uuid.UUID = DOCUMENT_ID, version: str = "v1") -> DocumentScope:
    return DocumentScope(document_id=document_id, index_version=version)


async def seed(embedder: OfflineEmbeddingProvider, content: str, *, index: int = 0) -> ChunkRecord:
    vector = (await embedder.embed([content], 1.0))[0]
    return ChunkRecord(
        vector_id=uuid.uuid4(),
        document_id=DOCUMENT_ID,
        chunk_index=index,
        content=content,
        embedding=tuple(vector),
        metadata={"index_version": "v1"},
    )


def client_for(app: object) -> httpx.AsyncClient:
    transport = httpx.ASGITransport(app=app, raise_app_exceptions=False)
    return httpx.AsyncClient(transport=transport, base_url="http://test")


# ------------------------------------------------------------------ 命名契约


def test_exports_every_documented_series_without_double_suffixes() -> None:
    business = BusinessMetrics()
    business.llm_call(status="success", model="qwen-plus", error_code=None, seconds=0.5)
    business.agent_round(2, "SUCCESS")
    business.agent_tool_call("calculator", "success")
    business.agent_tokens(token_in=10, token_out=5, model="qwen-plus")
    business.agent_budget_exhausted("token")
    business.rag_retrieval(seconds=0.2, result="OK", index_version="v1")
    business.vector_search(seconds=0.1, collection="chunks", result="OK")
    business.pii_blocked("input", "phone")

    text = business.render().decode()

    for series in DOCUMENTED_SERIES:
        assert series in text, f"{series} 未出现在导出文本中"
    # Java 的 Timer 已经负责加 `_seconds`，Python 侧若再加一次就和 §4.2 的名字对不上。
    assert "_seconds_seconds" not in text
    assert "_total_total" not in text


def test_registries_are_isolated_between_instances() -> None:
    first = BusinessMetrics()
    first.agent_tool_call("calculator", "success")
    # 第二次构造若共用全局 REGISTRY 会直接抛 `Duplicated timeseries`。
    second = BusinessMetrics()

    assert value_of(second.render().decode(), "docqa_agent_tool_calls_total") == 0.0


def test_blank_and_none_labels_fold_to_null() -> None:
    business = BusinessMetrics()
    business.llm_call(status="error", model="", error_code=None, seconds=0.01)

    text = business.render().decode()
    assert value_of(text, "docqa_llm_calls_total", status="error", model="NULL", error_code="NULL") == 1.0


def test_negative_durations_are_clamped_to_zero() -> None:
    business = BusinessMetrics()
    business.rag_retrieval(seconds=-1.0, result="OK", index_version="v1")

    text = business.render().decode()
    assert value_of(text, "docqa_rag_retrieval_latency_seconds_sum") == 0.0
    assert value_of(text, "docqa_rag_retrieval_latency_seconds_count") == 1.0


def test_non_positive_tokens_are_not_counted() -> None:
    business = BusinessMetrics()
    business.agent_tokens(token_in=0, token_out=-5, model="qwen-plus")

    assert value_of(business.render().decode(), "docqa_agent_tokens_total") == 0.0


# ------------------------------------------------------------------ 检索埋点


async def test_rag_retrieval_records_ok_with_index_version_and_collection() -> None:
    embedder = OfflineEmbeddingProvider()
    repository = InMemoryVectorRepository([await seed(embedder, "alpha")])
    business = BusinessMetrics()
    service = RetrievalService(repository, embedder, business=business, vector_collection="chunks_test")

    assert (await service.retrieve("alpha", [scope(version="v1")])).chunks

    text = business.render().decode()
    assert value_of(text, "docqa_rag_retrieval_latency_seconds_count", result="OK", index_version="v1") == 1.0
    assert (
        value_of(
            text,
            "docqa_vector_search_latency_seconds_count",
            result="OK",
            collection="chunks_test",
        )
        == 1.0
    )


async def test_rag_retrieval_records_empty_when_scope_list_is_empty() -> None:
    business = BusinessMetrics()
    service = RetrievalService(InMemoryVectorRepository(), OfflineEmbeddingProvider(), business=business)

    await service.retrieve("alpha", [])

    text = business.render().decode()
    assert value_of(text, "docqa_rag_retrieval_latency_seconds_count", result="EMPTY") == 1.0
    # 没有作用域就不该发生向量查询，这条序列必须是零样本。
    assert value_of(text, "docqa_vector_search_latency_seconds_count") == 0.0


async def test_rag_retrieval_records_error_and_vector_error_on_store_failure() -> None:
    business = BusinessMetrics()
    service = RetrievalService(UnavailableVectorRepository(), OfflineEmbeddingProvider(), business=business)

    with pytest.raises(RetrievalUnavailable):
        await service.retrieve("alpha", [scope()])

    text = business.render().decode()
    assert value_of(text, "docqa_rag_retrieval_latency_seconds_count", result="ERROR") == 1.0
    assert value_of(text, "docqa_vector_search_latency_seconds_count", result="ERROR") == 1.0


async def test_retrieval_service_without_business_metrics_is_a_noop() -> None:
    embedder = OfflineEmbeddingProvider()
    service = RetrievalService(InMemoryVectorRepository([await seed(embedder, "alpha")]), embedder)

    # 未注入时不得抛异常：`DocumentSearchTool` 等直接构造点不该被强制持有注册表。
    assert (await service.retrieve("alpha", [scope()])).chunks


# ------------------------------------------------------------------ Agent 埋点


async def test_final_round_records_llm_call_round_and_tokens() -> None:
    business = BusinessMetrics()
    planner = ScriptedPlanner(
        PlannerOutput(kind="final", answer="answer", citation_indexes=(1,), token_in=10, token_out=5)
    )

    await graph(planner, business=business).run(context(context_blocks=(block(0),)))

    text = business.render().decode()
    assert value_of(text, "docqa_llm_calls_total", status="success", model="offline-extractive") == 1.0
    assert value_of(text, "docqa_llm_latency_seconds_count", status="success") == 1.0
    assert value_of(text, "docqa_agent_rounds_count", status="SUCCESS") == 1.0
    assert value_of(text, "docqa_agent_tokens_total", direction="input") == 10.0
    assert value_of(text, "docqa_agent_tokens_total", direction="output") == 5.0


async def test_tool_round_records_tool_call_and_round_number() -> None:
    business = BusinessMetrics()
    planner = ScriptedPlanner(
        PlannerOutput(kind="tool", tool_name="calculator", tool_input="2+3", token_in=10, token_out=5),
        PlannerOutput(kind="final", answer="5", token_in=10, token_out=5),
    )

    await graph(planner, business=business).run(context(allowed_tools=("calculator",)))

    text = business.render().decode()
    assert value_of(text, "docqa_agent_tool_calls_total", tool="calculator", status="success") == 1.0
    # `_rounds_used` 取最大轮次：两轮问答的分布样本值是 2 而不是 1。
    assert value_of(text, "docqa_agent_rounds_sum", status="SUCCESS") == 2.0


# ------------------------------------------------------------------ 端点契约


async def test_metrics_endpoint_serves_prometheus_text_from_process_registry() -> None:
    business = BusinessMetrics()
    business.pii_blocked("input", "phone")
    app = create_app(make_settings(), agent_service=object(), business_metrics=business)  # type: ignore[arg-type]

    async with client_for(app) as client:
        response = await client.get("/metrics")

    assert response.status_code == 200
    assert response.headers["content-type"].startswith("text/plain")
    assert value_of(response.text, "docqa_pii_blocked_total", stage="input", category="phone") == 1.0


async def test_metrics_endpoint_is_absent_when_exposure_is_disabled() -> None:
    app = create_app(make_settings(metrics_exposed=False), agent_service=object())  # type: ignore[arg-type]

    async with client_for(app) as client:
        response = await client.get("/metrics")

    assert response.status_code == 404
