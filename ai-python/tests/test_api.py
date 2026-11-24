from __future__ import annotations

import json
import os
import sys
import uuid
from datetime import UTC, datetime

import httpx
import pytest
from fakeredis.aioredis import FakeRedis
from opentelemetry.sdk.trace.export.in_memory_span_exporter import InMemorySpanExporter

from docqa_ai.agent.service import AgentAskService
from docqa_ai.api.main import (
    build_default_service,
    build_embedder,
    build_experiment_ledger,
    build_mcp_provider,
    build_mcp_servers,
    build_repositories,
    build_reranker,
    create_app,
)
from docqa_ai.config import Settings
from docqa_ai.governance.audit import new_event
from docqa_ai.governance.budget import BudgetSnapshot, InMemoryBudgetStore
from docqa_ai.governance.experiment_budget import RedisExperimentBudgetStore
from docqa_ai.governance.experiment_egress import BudgetedEmbeddingProvider, BudgetedRerankProvider
from docqa_ai.governance.kill_switch import StaticKillSwitch
from docqa_ai.protocol.errors import deadline_exceeded
from docqa_ai.protocol.models import PROTOCOL_VERSION, AgentAskResponse, Citation
from docqa_ai.providers.embeddings import OPENAI_COMPATIBLE_MAX_BATCH_ROWS, OfflineEmbeddingProvider
from docqa_ai.providers.planner import OfflinePlanner
from docqa_ai.providers.rerank import NoopRerank
from docqa_ai.rag.lexical_repository import (
    PgLexicalRepository,
    UnavailableLexicalRepository,
)
from docqa_ai.rag.repository import (
    InMemoryVectorRepository,
    PgVectorRepository,
    UnavailableVectorRepository,
)
from docqa_ai.rag.service import RetrievalService
from docqa_ai.tools.registry import ToolRegistry
from docqa_ai.tracing import build_tracing
from tests.conftest import DOCUMENT_ID, TRACE_ID, make_settings, request_payload


def response_for(trace_id: str = TRACE_ID) -> AgentAskResponse:
    return AgentAskResponse(
        protocol_version=PROTOCOL_VERSION,
        trace_id=trace_id,
        answer="ok",
        citations=[
            Citation(document_id=DOCUMENT_ID, chunk_index=0, score=0.9, snippet="snippet"),
        ],
        confidence=0.9,
        budget_status="OK",
        token_used=42,
        tool_calls_made=0,
        rounds_used=1,
        audit_events=[new_event("AGENT_COMPLETED", "agent_service", tokenCount=42)],
        finished_at=datetime.now(UTC),
    )


class FakeService:
    def __init__(self, response: AgentAskResponse | None = None, error: Exception | None = None) -> None:
        self.response = response or response_for()
        self.error = error
        self.called = False
        self.deadline = None

    async def ask(self, request: object, *, deadline: object = None) -> AgentAskResponse:
        self.called = True
        self.deadline = deadline
        if self.error is not None:
            raise self.error
        return self.response


def real_service(*, kill: bool = False) -> AgentAskService:
    return AgentAskService(
        make_settings(),
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
        StaticKillSwitch(kill),
        RetrievalService(InMemoryVectorRepository(), OfflineEmbeddingProvider()),
        OfflinePlanner(),
        ToolRegistry([]),
    )


def client_for(app: object) -> httpx.AsyncClient:
    # Starlette re-raises after the 500 handler, so let the sanitized response through.
    transport = httpx.ASGITransport(app=app, raise_app_exceptions=False)
    return httpx.AsyncClient(transport=transport, base_url="http://test")


async def test_health_endpoint() -> None:
    app = create_app(make_settings(), agent_service=FakeService())
    async with client_for(app) as client:
        response = await client.get("/health")
    assert response.status_code == 200
    assert response.json() == {
        "status": "ok",
        "service": "docqa-ai",
        "protocolVersion": PROTOCOL_VERSION,
    }


async def test_default_redis_client_decodes_hash_fields() -> None:
    from opentelemetry import trace

    _, redis_client = build_default_service(make_settings(), trace.get_tracer("test"))
    try:
        assert redis_client.connection_pool.connection_kwargs["decode_responses"] is True
    finally:
        await redis_client.aclose()


async def test_success_response_uses_camel_case_contract() -> None:
    fake = FakeService()
    app = create_app(make_settings(), agent_service=fake)  # type: ignore[arg-type]
    async with client_for(app) as client:
        response = await client.post("/agent/ask", json=request_payload())
    assert response.status_code == 200
    body = response.json()
    assert set(body) == {
        "protocolVersion",
        "traceId",
        "answer",
        "citations",
        "confidence",
        "budgetStatus",
        "tokenUsed",
        "toolCallsMade",
        "roundsUsed",
        "auditEvents",
        "finishedAt",
    }
    assert body["citations"][0]["documentId"] == str(DOCUMENT_ID)
    assert body["auditEvents"][0]["eventType"] == "AGENT_COMPLETED"
    assert fake.called is True
    assert fake.deadline is not None
    assert fake.deadline.remaining_ms() > 0


@pytest.mark.parametrize(
    ("body", "content_type", "expected_field"),
    [
        (b"{not json", "application/json", "body"),
        (b"[]", "application/json", "body"),
        (b"{}", "text/plain", "body"),
        (
            json.dumps({**request_payload(), "traceId": "bad"}).encode(),
            "application/json",
            "traceId",
        ),
        (
            json.dumps({**request_payload(), "tokenBudget": 0}).encode(),
            "application/json",
            "tokenBudget",
        ),
        (
            json.dumps({**request_payload(), "unexpected": True}).encode(),
            "application/json",
            "unexpected",
        ),
    ],
)
async def test_protocol_violations_return_422_problem_detail(
    body: bytes,
    content_type: str,
    expected_field: str,
) -> None:
    fake = FakeService()
    app = create_app(make_settings(), agent_service=fake)  # type: ignore[arg-type]
    async with client_for(app) as client:
        response = await client.post("/agent/ask", content=body, headers={"Content-Type": content_type})
    assert response.status_code == 422
    problem = response.json()
    assert problem["code"] == "AGT-UPSTREAM-004"
    assert problem["status"] == 422
    assert problem["instance"] == "/agent/ask"
    assert any(expected_field in error["field"] for error in problem["errors"])
    assert fake.called is False


async def test_oversized_body_is_rejected_before_parsing() -> None:
    app = create_app(make_settings(), agent_service=FakeService())
    async with client_for(app) as client:
        response = await client.post(
            "/agent/ask",
            content=b"x" * (256 * 1024 + 1),
            headers={"Content-Type": "application/json"},
        )
    assert response.status_code == 413
    assert response.json()["code"] == "AGT-UPSTREAM-004"


async def test_chunked_oversized_body_is_rejected_without_buffering_it_all() -> None:
    async def oversized():
        for _ in range(300):
            yield b"x" * 1024

    app = create_app(make_settings(), agent_service=FakeService())
    async with client_for(app) as client:
        response = await client.post(
            "/agent/ask",
            content=oversized(),
            headers={"Content-Type": "application/json"},
        )
    assert response.status_code == 413
    assert response.json()["code"] == "AGT-UPSTREAM-004"


async def test_pii_in_masked_question_returns_422_with_audit_events() -> None:
    email = "leak@example.com"
    payload = request_payload(question=f"联系 {email}", piiMaskedQuestion=f"联系 {email}")
    app = create_app(make_settings(), agent_service=real_service())
    async with client_for(app) as client:
        response = await client.post("/agent/ask", json=payload)
    assert response.status_code == 422
    problem = response.json()
    assert problem["code"] == "AGT-VALIDATION-003"
    assert [event["eventType"] for event in problem["auditEvents"]] == [
        "PII_DIFFERENTIAL_CHECK",
        "PII_LEAK_BLOCKED",
    ]
    assert email not in response.text


async def test_kill_switch_returns_503_with_audit_event() -> None:
    app = create_app(make_settings(), agent_service=real_service(kill=True))
    async with client_for(app) as client:
        response = await client.post("/agent/ask", json=request_payload())
    assert response.status_code == 503
    problem = response.json()
    assert problem["code"] == "AGT-UPSTREAM-003"
    assert problem["auditEvents"][0]["eventType"] == "AGENT_KILL_SWITCH"


async def test_deadline_error_is_mapped_to_504() -> None:
    fake = FakeService(error=deadline_exceeded(trace_id=TRACE_ID))
    app = create_app(make_settings(), agent_service=fake)  # type: ignore[arg-type]
    async with client_for(app) as client:
        response = await client.post("/agent/ask", json=request_payload())
    assert response.status_code == 504
    assert response.json()["code"] == "AGT-UPSTREAM-002"


async def test_unhandled_error_is_sanitized() -> None:
    fake = FakeService(error=RuntimeError("secret dsn"))
    app = create_app(make_settings(), agent_service=fake)  # type: ignore[arg-type]
    async with client_for(app) as client:
        response = await client.post("/agent/ask", json=request_payload())
    assert response.status_code == 500
    assert response.json()["code"] == "AGT-INTERNAL-001"
    assert "secret" not in response.text


async def test_traceparent_header_continues_the_trace() -> None:
    settings = make_settings()
    exporter = InMemorySpanExporter()
    tracer, provider = build_tracing(settings, exporter=exporter)
    app = create_app(
        settings,
        agent_service=FakeService(),  # type: ignore[arg-type]
        tracer=tracer,
        tracer_provider=provider,
        span_exporter=exporter,
    )
    span_id = "1" * 16
    async with client_for(app) as client:
        response = await client.post(
            "/agent/ask",
            json=request_payload(),
            headers={"traceparent": f"00-{TRACE_ID}-{span_id}-01"},
        )
    assert response.status_code == 200
    spans = [span for span in exporter.get_finished_spans() if span.name == "http.server.agent"]
    assert len(spans) == 1
    assert f"{spans[0].context.trace_id:032x}" == TRACE_ID
    assert spans[0].attributes["trace_continuation"] is True


async def test_missing_traceparent_annotates_the_expected_trace_id() -> None:
    settings = make_settings()
    exporter = InMemorySpanExporter()
    tracer, provider = build_tracing(settings, exporter=exporter)
    app = create_app(
        settings,
        agent_service=FakeService(),  # type: ignore[arg-type]
        tracer=tracer,
        tracer_provider=provider,
        span_exporter=exporter,
    )
    async with client_for(app) as client:
        response = await client.post("/agent/ask", json=request_payload())
    assert response.status_code == 200
    assert any(span.attributes.get("expected_trace_id") == TRACE_ID for span in exporter.get_finished_spans())


async def test_trace_mismatch_is_annotated_on_the_span() -> None:
    settings = make_settings()
    exporter = InMemorySpanExporter()
    tracer, provider = build_tracing(settings, exporter=exporter)
    app = create_app(
        settings,
        agent_service=FakeService(),  # type: ignore[arg-type]
        tracer=tracer,
        tracer_provider=provider,
        span_exporter=exporter,
    )
    other_trace = uuid.uuid4().hex
    async with client_for(app) as client:
        response = await client.post(
            "/agent/ask",
            json=request_payload(),
            headers={"traceparent": f"00-{other_trace}-{'2' * 16}-01"},
        )
    assert response.status_code == 200
    span = next(span for span in exporter.get_finished_spans() if span.name == "http.server.agent")
    assert span.attributes["trace.mismatch"] is True


def test_configured_pg_wires_both_retrieval_channels_together() -> None:
    vector, lexical = build_repositories(make_settings(pg_dsn="postgresql://study@127.0.0.1:5432/docqa"))
    # 只有一路接上 PG 是最危险的装配形态：词法通道会永久降级，而每次请求都看不出来。
    assert isinstance(vector, PgVectorRepository)
    assert isinstance(lexical, PgLexicalRepository)


def test_missing_pg_leaves_both_retrieval_channels_fail_closed() -> None:
    vector, lexical = build_repositories(make_settings(pg_dsn=""))
    assert isinstance(vector, UnavailableVectorRepository)
    assert isinstance(lexical, UnavailableLexicalRepository)


def real_egress_settings(**overrides: object) -> Settings:
    """满足启动守卫的「切真」配置：额度为正（②）+ 独立向量空间。"""

    data: dict[str, object] = {
        "embedding_provider": "openai",
        "embedding_base_url": "https://example.invalid/compatible-mode/v1",
        "pg_table": "chunk_embedding_real",
        "experiment_budget": 200,
        "experiment_token_budget": 200_000,
    }
    data.update(overrides)
    return make_settings(**data)


def test_offline_assembly_leaves_the_experiment_ledger_unarmed() -> None:
    """默认配置的账本必须是 None：不构造 ⇒ 一个 Redis 键都不写，离线不变性由结构保证。"""

    settings = make_settings()
    assert build_experiment_ledger(settings, FakeRedis(decode_responses=True)) is None
    assert isinstance(build_embedder(settings, None), OfflineEmbeddingProvider)
    reranker = build_reranker(settings, None)
    assert isinstance(reranker, NoopRerank)
    # 通道存在但不外呼，span 上只能写这条通道自己的名字，不能写配置里的第三方模型名
    assert reranker.model == "offline-noop"


def test_real_embedding_assembly_wraps_the_provider_with_the_ledger() -> None:
    settings = real_egress_settings()
    ledger = build_experiment_ledger(settings, FakeRedis(decode_responses=True))
    assert isinstance(ledger, RedisExperimentBudgetStore)
    embedder = build_embedder(settings, ledger)
    assert isinstance(embedder, BudgetedEmbeddingProvider)
    # 端点自报的批量上限必须透传，否则 worker 退回配置的 32 行，真实端点 11 行就 400
    assert embedder.max_batch_rows == OPENAI_COMPATIBLE_MAX_BATCH_ROWS
    assert embedder.dimensions == settings.embedding_dimensions


def test_dashscope_rerank_assembly_wraps_the_provider_with_the_ledger() -> None:
    settings = real_egress_settings(
        rerank_provider="dashscope",
        rerank_base_url="https://example.invalid/api/v1/services/rerank/text-rerank/text-rerank",
        rerank_api_key="rerank-key-placeholder",
        rerank_model="gte-rerank-v2",
    )
    ledger = build_experiment_ledger(settings, FakeRedis(decode_responses=True))
    reranker = build_reranker(settings, ledger)
    assert isinstance(reranker, BudgetedRerankProvider)
    assert reranker.model == "gte-rerank-v2"


def test_rerank_only_egress_still_arms_the_ledger() -> None:
    """名次也花真实额度：只切 rerank 的组合同样要记账。"""

    settings = make_settings(
        rerank_provider="dashscope",
        rerank_base_url="https://example.invalid/rerank",
        rerank_api_key="rerank-key-placeholder",
        experiment_budget=10,
        experiment_token_budget=10_000,
    )
    ledger = build_experiment_ledger(settings, FakeRedis(decode_responses=True))
    assert isinstance(ledger, RedisExperimentBudgetStore)


async def test_offline_default_service_carries_no_ledger() -> None:
    from opentelemetry import trace

    service, redis_client = build_default_service(make_settings(), trace.get_tracer("test"))
    try:
        # 私有字段是装配面唯一的证据入口：离线默认必须既没有账本也没有被包装的 Provider
        assert service._experiment is None
        assert isinstance(service._retrieval._embedder, OfflineEmbeddingProvider)
        assert isinstance(service._retrieval._reranker, NoopRerank)
    finally:
        await redis_client.aclose()


async def test_real_assembly_shares_one_ledger_across_every_egress_point() -> None:
    """四个外呼点必须是**同一本账**：各建一份账就等于四个互不知情的天花板（②）。"""

    from opentelemetry import trace

    settings = real_egress_settings(
        rerank_provider="dashscope",
        rerank_base_url="https://example.invalid/rerank",
        rerank_api_key="rerank-key-placeholder",
        rerank_model="gte-rerank-v2",
    )
    service, redis_client = build_default_service(settings, trace.get_tracer("test"))
    try:
        ledger = service._experiment
        assert isinstance(ledger, RedisExperimentBudgetStore)
        assert isinstance(service._retrieval._embedder, BudgetedEmbeddingProvider)
        assert isinstance(service._retrieval._reranker, BudgetedRerankProvider)
        assert service._retrieval._embedder._store is ledger
        assert service._retrieval._reranker._store is ledger
        # 生成侧不套装饰器，同一个对象由 AgentAskService 传给 graph._plan 的 reserved / actual 接缝
        assert service._experiment is ledger
        assert service._retrieval._reranker.model == "gte-rerank-v2"
    finally:
        await redis_client.aclose()


MCP_SERVER_CONFIG = {
    "name": "dict",
    "url": "https://mcp.example.com/mcp",
    "allowedTools": ["lookup"],
    "tokenEnv": "DICT_MCP_TOKEN",
}


def test_mcp_disabled_builds_no_client_at_all() -> None:
    """同法：关闭时连客户端都不构造，零外呼是结构事实而不是调用点自觉。"""

    settings = make_settings()
    assert build_mcp_servers(settings) == ()
    assert build_mcp_provider(settings) is None


def test_mcp_servers_read_the_token_from_the_configured_environment(monkeypatch: pytest.MonkeyPatch) -> None:
    import docqa_ai.api.main as main

    captured: dict[str, object] = {}

    class SpyClient:
        def __init__(
            self,
            url: str,
            *,
            token: str = "",
            timeout_s: float = 3.0,
            max_response_bytes: int = 262144,
        ) -> None:
            captured.update(url=url, token=token, timeout_s=timeout_s, max_response_bytes=max_response_bytes)

    monkeypatch.setattr(main, "McpHttpClient", SpyClient)
    monkeypatch.setenv("DICT_MCP_TOKEN", "s3cret-token")
    settings = make_settings(mcp_enabled=True, mcp_servers=[MCP_SERVER_CONFIG], mcp_call_timeout_ms=2500)

    servers = build_mcp_servers(settings)

    assert len(servers) == 1
    assert servers[0].name == "dict"
    assert servers[0].allowed_tools == ("lookup",)
    assert captured == {
        "url": "https://mcp.example.com/mcp",
        "token": "s3cret-token",
        "timeout_s": 2.5,
        "max_response_bytes": 262144,
    }
    assert "s3cret-token" not in str(settings.redacted_summary())


def test_mcp_servers_are_parsed_from_json_environment(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("MCP_TEST_MCP_ENABLED", "true")
    monkeypatch.setenv("MCP_TEST_MCP_SERVERS", json.dumps([MCP_SERVER_CONFIG]))
    settings = Settings(_env_file=None, _env_prefix="MCP_TEST_")
    assert settings.mcp_enabled is True
    assert [server.name for server in settings.mcp_servers] == ["dict"]
    assert settings.mcp_servers[0].allowed_tools == ["lookup"]
    assert settings.mcp_servers[0].token_env == "DICT_MCP_TOKEN"
    assert settings.redacted_summary()["mcp_server_count"] == 1


def test_duplicate_mcp_server_names_are_rejected() -> None:
    with pytest.raises(ValueError):
        make_settings(mcp_enabled=True, mcp_servers=[MCP_SERVER_CONFIG, MCP_SERVER_CONFIG])


def test_mcp_stdio_config_requires_a_command_and_excludes_http_fields() -> None:
    with pytest.raises(ValueError, match="command is required"):
        make_settings(mcp_enabled=True, mcp_servers=[{"name": "time", "transport": "stdio"}])
    with pytest.raises(ValueError, match="url is only valid"):
        make_settings(
            mcp_enabled=True,
            mcp_servers=[{"name": "time", "transport": "stdio", "command": "C:/time.exe", "url": "https://x/mcp"}],
        )
    with pytest.raises(ValueError, match="only valid when transport=stdio"):
        make_settings(
            mcp_enabled=True,
            mcp_servers=[{"name": "dict", "transport": "http", "url": "https://x/mcp", "command": "C:/dict.exe"}],
        )


def test_mcp_stdio_server_is_parsed_from_json_environment(monkeypatch: pytest.MonkeyPatch) -> None:
    entry = {
        "name": "echo",
        "transport": "stdio",
        "command": sys.executable,
        "args": ["server.py"],
        "env": ["MCP_ECHO_TOKEN"],
        "allowedTools": ["echo"],
    }
    monkeypatch.setenv("MCP_STDIO_MCP_ENABLED", "true")
    monkeypatch.setenv("MCP_STDIO_MCP_SERVERS", json.dumps([entry]))
    settings = Settings(_env_file=None, _env_prefix="MCP_STDIO_")
    parsed = settings.mcp_servers[0]
    assert parsed.transport == "stdio"
    assert parsed.command == sys.executable
    assert parsed.args == ["server.py"]
    assert parsed.env == ["MCP_ECHO_TOKEN"]
    assert parsed.token_env == ""


def test_unavailable_stdio_server_is_skipped_not_fatal() -> None:
    settings = make_settings(
        mcp_enabled=True,
        mcp_servers=[
            {
                "name": "time",
                "transport": "stdio",
                "command": "C:/definitely-missing/mcp-server-time.exe",
                "allowedTools": ["get_current_time"],
            }
        ],
    )

    assert build_mcp_servers(settings) == ()


def test_mcp_stdio_server_is_wired_to_the_sdk_client(monkeypatch: pytest.MonkeyPatch) -> None:
    import docqa_ai.api.main as main

    captured: dict[str, object] = {}

    class SpySdkClient:
        def __init__(self, name: str, config: object, **kwargs: object) -> None:
            captured.update(name=name, config=config, kwargs=kwargs)

    monkeypatch.setattr(main, "SdkMcpClient", SpySdkClient)
    settings = make_settings(
        mcp_enabled=True,
        mcp_servers=[
            {
                "name": "echo",
                "transport": "stdio",
                "command": sys.executable,
                "args": ["server.py"],
                "cwd": os.getcwd(),
                "allowedTools": ["echo"],
            }
        ],
        mcp_call_timeout_ms=2500,
        mcp_startup_timeout_ms=7000,
        mcp_max_concurrency=2,
        mcp_failure_threshold=4,
        mcp_circuit_open_seconds=30,
    )

    servers = build_mcp_servers(settings)

    assert len(servers) == 1
    assert servers[0].name == "echo"
    assert servers[0].allowed_tools == ("echo",)
    assert captured["name"] == "echo"
    config = captured["config"]
    assert config.command == sys.executable
    assert config.args == ("server.py",)
    assert config.cwd == os.getcwd()
    assert captured["kwargs"] == {
        "timeout_s": 2.5,
        "startup_timeout_s": 7.0,
        "max_concurrency": 2,
        "failure_threshold": 4,
        "circuit_open_s": 30.0,
    }
