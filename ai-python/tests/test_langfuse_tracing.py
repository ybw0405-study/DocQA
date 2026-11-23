"""PR2 LangFuse 第二路导出：启动守卫、管道形态与 span 脱敏的回归用例。"""

from __future__ import annotations

import base64

import httpx
import pytest
from opentelemetry.sdk.trace.export import BatchSpanProcessor, SimpleSpanProcessor
from opentelemetry.sdk.trace.export.in_memory_span_exporter import InMemorySpanExporter
from pydantic import ValidationError

from docqa_ai.agent.service import AgentAskService
from docqa_ai.api.main import create_app
from docqa_ai.governance.budget import BudgetSnapshot, InMemoryBudgetStore
from docqa_ai.governance.kill_switch import StaticKillSwitch
from docqa_ai.providers.embeddings import OfflineEmbeddingProvider
from docqa_ai.providers.planner import OfflinePlanner
from docqa_ai.rag.repository import InMemoryVectorRepository
from docqa_ai.rag.service import RetrievalService
from docqa_ai.tools.registry import ToolRegistry
from docqa_ai.tracing import build_tracing
from tests.conftest import make_settings, request_payload


def langfuse_settings(**overrides: object):
    base: dict[str, object] = {
        "langfuse_tracing_enabled": True,
        "langfuse_public_key": "pk-lf-test",
        "langfuse_secret_key": "sk-lf-test",
    }
    base.update(overrides)
    return make_settings(**base)


def span_processors(provider) -> tuple:
    # SDK 没有公开的 processor 枚举面；测试直接读多路复用器的内部列表。
    return provider._active_span_processor._span_processors


def test_disabled_by_default_keeps_the_existing_pipeline_untouched() -> None:
    exporter = InMemorySpanExporter()
    _, provider = build_tracing(make_settings(), exporter=exporter)
    processors = span_processors(provider)
    assert len(processors) == 1
    assert isinstance(processors[0], SimpleSpanProcessor)

    _, bare = build_tracing(make_settings())
    assert span_processors(bare) == ()


def test_enabled_appends_a_second_batch_processor_after_the_existing_one() -> None:
    exporter = InMemorySpanExporter()
    _, provider = build_tracing(langfuse_settings(), exporter=exporter)
    processors = span_processors(provider)
    assert len(processors) == 2
    assert isinstance(processors[0], SimpleSpanProcessor)
    assert isinstance(processors[1], BatchSpanProcessor)
    otlp = processors[1].span_exporter
    assert otlp._endpoint == "http://127.0.0.1:13000/api/public/otel/v1/traces"
    expected = base64.b64encode(b"pk-lf-test:sk-lf-test").decode()
    assert otlp._client._headers["authorization"] == f"Basic {expected}"


def test_enabled_without_otel_still_builds_the_langfuse_exporter() -> None:
    _, provider = build_tracing(langfuse_settings())
    processors = span_processors(provider)
    assert len(processors) == 1
    assert isinstance(processors[0], BatchSpanProcessor)


def test_enabled_without_keys_fails_fast() -> None:
    with pytest.raises(ValidationError, match="langfuse_public_key"):
        langfuse_settings(langfuse_public_key="")
    with pytest.raises(ValidationError, match="langfuse_public_key"):
        langfuse_settings(langfuse_secret_key="")


def test_non_loopback_endpoint_requires_the_opt_in_flag() -> None:
    with pytest.raises(ValidationError, match="langfuse_allow_private"):
        langfuse_settings(langfuse_otlp_endpoint="http://10.0.0.8:13000/api/public/otel/v1/traces")
    settings = langfuse_settings(
        langfuse_otlp_endpoint="http://10.0.0.8:13000/api/public/otel/v1/traces",
        langfuse_allow_private=True,
    )
    assert settings.langfuse_allow_private is True


def test_loopback_endpoints_need_no_opt_in() -> None:
    for host in ("127.0.0.1", "localhost", "[::1]"):
        settings = langfuse_settings(langfuse_otlp_endpoint=f"http://{host}:13000/api/public/otel/v1/traces")
        assert settings.langfuse_tracing_enabled is True


def test_invalid_endpoint_fails_fast() -> None:
    with pytest.raises(ValidationError, match="langfuse_otlp_endpoint"):
        langfuse_settings(langfuse_otlp_endpoint="not-a-url")


def test_redacted_summary_carries_flag_and_host_but_no_keys() -> None:
    summary = langfuse_settings().redacted_summary()
    assert summary["langfuse_tracing_enabled"] is True
    assert summary["langfuse_otlp_host"] == "127.0.0.1"
    dumped = str(summary)
    assert "pk-lf-test" not in dumped
    assert "sk-lf-test" not in dumped


async def test_no_span_attribute_carries_question_or_answer_text() -> None:
    """span 属性只允许计数/model/outcome：问题与回答原文不得进任何一路导出。"""

    question = "机密问题：青龙白虎朱雀玄武的年度预算是多少？"
    settings = make_settings()
    service = AgentAskService(
        settings,
        InMemoryBudgetStore(
            BudgetSnapshot(
                tokens_used=0,
                tool_calls_made=0,
                rounds_used=0,
                token_budget=8000,
                max_tool_calls=1,
                max_rounds=2,
                deadline_at="2026-10-27T10:00:10Z",
            )
        ),
        StaticKillSwitch(False),
        RetrievalService(InMemoryVectorRepository(), OfflineEmbeddingProvider()),
        OfflinePlanner(),
        ToolRegistry([]),
    )
    exporter = InMemorySpanExporter()
    tracer, provider = build_tracing(settings, exporter=exporter)
    app = create_app(
        settings,
        agent_service=service,
        tracer=tracer,
        tracer_provider=provider,
        span_exporter=exporter,
    )
    transport = httpx.ASGITransport(app=app, raise_app_exceptions=False)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.post(
            "/agent/ask",
            json=request_payload(question=question, piiMaskedQuestion=question),
        )
    assert response.status_code == 200
    answer = response.json()["answer"]
    assert answer

    spans = exporter.get_finished_spans()
    assert spans, "真实服务链路必须产生 span，否则本断言恒真"
    for span in spans:
        assert question not in span.name
        for key, value in (span.attributes or {}).items():
            text = str(value)
            assert question not in text, f"span 属性 {key} 携带了问题原文"
            assert answer not in text, f"span 属性 {key} 携带了回答原文"
