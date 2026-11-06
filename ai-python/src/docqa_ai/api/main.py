from __future__ import annotations

import asyncio
import json
import logging
import os
import re
import sys
import time
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from typing import Any

import uvicorn
from fastapi import FastAPI, Request, Response
from fastapi.responses import JSONResponse, StreamingResponse
from opentelemetry import trace
from opentelemetry.instrumentation.fastapi import FastAPIInstrumentor
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import SpanExporter
from opentelemetry.trace import Tracer
from pydantic import ValidationError
from redis.asyncio import Redis

from docqa_ai.agent.service import AgentAskService
from docqa_ai.business_metrics import BusinessMetrics
from docqa_ai.config import Settings
from docqa_ai.governance.budget import Deadline, RedisBudgetStore
from docqa_ai.governance.experiment_budget import ExperimentBudgetStore, RedisExperimentBudgetStore
from docqa_ai.governance.experiment_egress import BudgetedEmbeddingProvider, BudgetedRerankProvider
from docqa_ai.governance.kill_switch import RedisKillSwitch
from docqa_ai.governance.tool_rate_limit import RedisToolRateLimiter
from docqa_ai.logging_setup import configure_logging, log_event
from docqa_ai.metrics import ToolMetrics, build_metrics
from docqa_ai.pii.masker import mask_text
from docqa_ai.protocol.errors import (
    AGT_UPSTREAM_004,
    AgentError,
    deadline_exceeded,
    internal_error,
    protocol_violation,
)
from docqa_ai.protocol.models import (
    PROTOCOL_VERSION,
    STREAM_PROTOCOL_VERSION,
    AgentAskRequest,
    AgentAskResponse,
    FieldError,
)
from docqa_ai.providers.embeddings import (
    EmbeddingProvider,
    OfflineEmbeddingProvider,
    OpenAIEmbeddingProvider,
)
from docqa_ai.providers.planner import OfflinePlanner, OpenAIPlanner
from docqa_ai.providers.rerank import DashScopeRerankProvider, NoopRerank, RerankProvider
from docqa_ai.rag.lexical_repository import (
    LexicalRepository,
    PgLexicalRepository,
    UnavailableLexicalRepository,
)
from docqa_ai.rag.repository import (
    PgVectorRepository,
    UnavailableVectorRepository,
    VectorRepository,
)
from docqa_ai.rag.service import RetrievalService
from docqa_ai.tools import CalculatorTool, CurrentTimeTool, DocumentSearchTool, ToolRegistry, build_web_toolset
from docqa_ai.tools.mcp import (
    McpHttpClient,
    McpServer,
    McpStdioConfig,
    McpToolProvider,
    SdkMcpClient,
)
from docqa_ai.tracing import TraceContextMiddleware, annotate_expected_trace, build_tracing

LOGGER = logging.getLogger(__name__)

_MAX_BODY_BYTES = 256 * 1024
_MAX_ERRORS = 20
_TRACE_ID = re.compile(r"^[0-9a-f]{32}$")


def _payload_too_large() -> AgentError:
    return AgentError(
        code=AGT_UPSTREAM_004,
        status=413,
        title="Request payload too large",
        detail="The request body exceeds the accepted size.",
        problem_type="https://docqa.example/problems/agent-protocol",
    )


async def _read_limited_body(request: Request) -> bytes:
    body = bytearray()
    async for chunk in request.stream():
        body.extend(chunk)
        if len(body) > _MAX_BODY_BYTES:
            raise _payload_too_large()
    return bytes(body)


def _sse_frame(event: str, data: str) -> bytes:
    """单事件 SSE 帧：JSON 载荷单行（串内换行已被 json 转义），帧以空行收尾。"""
    return f"event: {event}\ndata: {data}\n\n".encode()


def build_repositories(settings: Settings) -> tuple[VectorRepository, LexicalRepository]:
    """两路仓库共用同一个 DSN 与同一个表名：同一条授权谓词的前提是同一份数据面（D-87）。"""

    if not settings.pg_dsn:
        return UnavailableVectorRepository(), UnavailableLexicalRepository()
    return (
        PgVectorRepository(
            settings.pg_dsn,
            settings.pg_table,
            settings.pg_connect_timeout_s,
            settings.embedding_dimensions,
        ),
        PgLexicalRepository(
            settings.pg_dsn,
            settings.pg_table,
            settings.pg_connect_timeout_s,
        ),
    )


def build_experiment_ledger(settings: Settings, redis_client: Redis) -> ExperimentBudgetStore | None:
    """全局额度账本只在「真会往外发字节」的组合下构造。

    谓词与配置里的启动守卫同源（D-106 ②）：三个 Provider 全离线 ⇒ None ⇒ 一个 Redis 键都不写，
    默认配置与 S5 逐字节一致（D-113）；只要有一个切真 ⇒ 守卫已保证额度为正，
    不会出现「额度 0 把每个请求都软降级」的假死。
    """

    real_egress = (
        settings.embedding_provider != "offline"
        or settings.llm_provider != "offline"
        or settings.rerank_provider != "offline"
    )
    if not real_egress:
        return None
    return RedisExperimentBudgetStore(
        redis_client,
        settings.experiment_budget,
        settings.experiment_token_budget,
        settings.budget_key_prefix,
    )


def build_embedder(settings: Settings, experiment: ExperimentBudgetStore | None) -> EmbeddingProvider:
    if settings.embedding_provider == "offline":
        return OfflineEmbeddingProvider(settings.embedding_model, settings.embedding_dimensions)
    provider = OpenAIEmbeddingProvider(
        settings.embedding_base_url,
        settings.embedding_api_key.get_secret_value(),
        settings.embedding_model,
        settings.embedding_dimensions,
    )
    if experiment is None:
        return provider
    return BudgetedEmbeddingProvider(provider, experiment)


def build_reranker(settings: Settings, experiment: ExperimentBudgetStore | None) -> RerankProvider:
    """offline 也注入 `NoopRerank`：名次通道存在但不外呼，结果与不注入逐字段相等（D-113 的用例保证）。

    这里用 `NoopRerank` 自己的模型名而不是配置里的 `rerank_model`——通道没有外呼，
    把真实模型名写到 span 上就是一条假的出网证据。
    """

    if settings.rerank_provider == "offline":
        return NoopRerank()
    provider = DashScopeRerankProvider(
        settings.rerank_base_url,
        settings.rerank_api_key.get_secret_value(),
        settings.rerank_model,
    )
    if experiment is None:
        return provider
    return BudgetedRerankProvider(provider, experiment)


def build_mcp_servers(settings: Settings) -> tuple[McpServer, ...]:
    """D-125 / D-133：关闭时不构造任何客户端，零外呼由结构保证，而不是靠调用点自觉。

    stdio 条目走官方 SDK 适配层（独立 Proactor 线程 + 熔断）；HTTP 条目沿用自研客户端。
    令牌只从配置指向的环境变量读取，既不进 Settings 字段，也不进日志。
    """

    if not settings.mcp_enabled:
        return ()
    timeout_s = settings.mcp_call_timeout_ms / 1000.0
    servers: list[McpServer] = []
    for entry in settings.mcp_servers:
        if entry.transport == "stdio":
            try:
                client = SdkMcpClient(
                    entry.name,
                    McpStdioConfig(
                        command=entry.command,
                        args=tuple(entry.args),
                        env_names=tuple(entry.env),
                        cwd=entry.cwd or None,
                    ),
                    timeout_s=timeout_s,
                    startup_timeout_s=settings.mcp_startup_timeout_ms / 1000.0,
                    max_concurrency=settings.mcp_max_concurrency,
                    failure_threshold=settings.mcp_failure_threshold,
                    circuit_open_s=settings.mcp_circuit_open_seconds,
                )
            except ValueError as exc:
                # 依赖未预置或密钥变量缺席只影响这一个 Server（G6），不阻断问答面。
                LOGGER.warning(
                    "MCP stdio server skipped: server=%s error=%s",
                    entry.name,
                    type(exc).__name__,
                )
                continue
        else:
            client = McpHttpClient(
                entry.url,
                token=os.environ.get(entry.token_env, "") if entry.token_env else "",
                timeout_s=timeout_s,
                max_response_bytes=settings.mcp_max_response_bytes,
            )
        servers.append(
            McpServer(
                entry.name,
                client,
                tuple(entry.allowed_tools),
                timeout_s=timeout_s,
            )
        )
    if not servers:
        log_event(LOGGER, logging.WARNING, "MCP enabled without configured servers", step="mcp")
    return tuple(servers)


def build_mcp_provider(settings: Settings, tracer: Tracer | None = None) -> McpToolProvider | None:
    servers = build_mcp_servers(settings)
    if not settings.mcp_enabled:
        return None
    return McpToolProvider(
        servers,
        ttl_seconds=settings.mcp_discovery_ttl_seconds,
        max_tools=settings.mcp_max_tools,
        tracer=tracer,
    )


def build_default_service(
    settings: Settings,
    tracer: Tracer,
    metrics: ToolMetrics | None = None,
    business: BusinessMetrics | None = None,
) -> tuple[AgentAskService, Redis]:
    redis_client = Redis.from_url(
        settings.redis_url,
        socket_timeout=settings.redis_socket_timeout_ms / 1000.0,
        socket_connect_timeout=settings.redis_socket_timeout_ms / 1000.0,
        decode_responses=True,
    )
    budget = RedisBudgetStore(redis_client, settings.budget_key_prefix)
    kill_switch = RedisKillSwitch(
        redis_client,
        settings.kill_switch_key,
        settings.kill_switch_cache_ms,
    )
    experiment = build_experiment_ledger(settings, redis_client)
    embedder = build_embedder(settings, experiment)
    if settings.llm_provider == "offline":
        planner = OfflinePlanner(settings.llm_model)
    else:
        planner = OpenAIPlanner(
            settings.llm_base_url,
            settings.llm_api_key.get_secret_value(),
            settings.llm_model,
        )
    repository, lexical_repository = build_repositories(settings)
    retrieval = RetrievalService(
        repository,
        embedder,
        top_k=settings.retrieval_top_k,
        min_score=settings.retrieval_min_score,
        embedding_timeout_s=settings.embedding_timeout_ms / 1000.0,
        lexical_repository=lexical_repository,
        lexical_enabled=settings.lexical_recall_enabled,
        lexical_candidate_limit=settings.retrieval_lexical_candidate_limit,
        fusion_rrf_k=settings.fusion_rrf_k,
        lexical_min_query_chars=settings.lexical_min_query_chars,
        reranker=build_reranker(settings, experiment),
        rerank_timeout_s=settings.rerank_timeout_ms / 1000.0,
        tracer=tracer,
        business=business,
        vector_collection=settings.pg_table,
    )
    web = build_web_toolset(settings, metrics=metrics)
    builtin_tools = [
        DocumentSearchTool(max_results=min(3, settings.retrieval_top_k)),
        CalculatorTool(),
        CurrentTimeTool(),
    ]
    if web is not None:
        builtin_tools.extend(web.tools)
    tools = ToolRegistry(builtin_tools)
    mcp = build_mcp_provider(settings, tracer)
    if metrics is not None and mcp is not None:
        metrics.bind_server_states(mcp.up_states)
    rate_limiter = (
        RedisToolRateLimiter(redis_client, settings.tool_rate_limit_per_minute)
        if settings.tool_rate_limit_enabled
        else None
    )
    service = AgentAskService(
        settings,
        budget,
        kill_switch,
        retrieval,
        planner,
        tools,
        tracer=tracer,
        experiment=experiment,
        mcp=mcp,
        web=web,
        rate_limiter=rate_limiter,
        metrics=metrics,
        business=business,
    )
    log_event(
        LOGGER,
        logging.INFO,
        "AI service initialized",
        provider=settings.llm_provider,
        model=settings.llm_model,
        webTools=len(web.tools) if web is not None else 0,
    )
    return service, redis_client


def _field_errors(exc: ValidationError) -> list[FieldError]:
    errors: list[FieldError] = []
    for item in exc.errors()[:_MAX_ERRORS]:
        location = ".".join(str(part) for part in item.get("loc", ())) or "body"
        message = mask_text(str(item.get("msg", "invalid value"))).masked[:200] or "invalid value"
        errors.append(FieldError(field=location[:200], message=message))
    return errors


def _trace_hint(payload: object, request: Request) -> str | None:
    if isinstance(payload, dict):
        candidate = payload.get("traceId")
        if isinstance(candidate, str) and _TRACE_ID.fullmatch(candidate):
            return candidate
    header = request.headers.get("x-trace-id")
    if header and _TRACE_ID.fullmatch(header):
        return header
    state_trace = getattr(request.state, "header_trace_id", None)
    if isinstance(state_trace, str) and _TRACE_ID.fullmatch(state_trace):
        return state_trace
    return None


def create_app(
    settings: Settings | None = None,
    *,
    agent_service: AgentAskService | None = None,
    tracer: Tracer | None = None,
    tracer_provider: TracerProvider | None = None,
    span_exporter: SpanExporter | None = None,
    business_metrics: BusinessMetrics | None = None,
) -> FastAPI:
    settings = settings or Settings()
    if tracer is None:
        tracer, tracer_provider = build_tracing(settings, exporter=span_exporter)
    metrics, meter_provider = build_metrics(settings)
    # §4.2 的 Python 侧指标。实例随应用创建，注册表按实例隔离（见 business_metrics 模块注释）。
    business = business_metrics if business_metrics is not None else BusinessMetrics()

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        configure_logging(settings.log_level, settings.service_name)
        if app.state.owns_service:
            service, redis_client = build_default_service(
                settings, app.state.tracer, app.state.metrics, app.state.business_metrics
            )
            app.state.agent_service = service
            app.state.redis_client = redis_client
            # 账本上限先发布，管理面启动后立刻能读到 `limitsKnown=true`（S9-5e）；
            # 失败只降级回显，不阻断启动（见 `publish_experiment_limits`）。
            await service.publish_experiment_limits()
            # 预热失败会被 provider 逐 Server 吞掉并保留空快照，不会阻断启动（D-125）。
            await service.prewarm_tools()
        yield
        if app.state.owns_service:
            service = getattr(app.state, "agent_service", None)
            if service is not None:
                await service.aclose()
        redis_client = getattr(app.state, "redis_client", None)
        if redis_client is not None:
            await redis_client.aclose()
        provider = getattr(app.state, "tracer_provider", None)
        if provider is not None:
            provider.shutdown()
        metrics_provider = getattr(app.state, "meter_provider", None)
        if metrics_provider is not None:
            metrics_provider.shutdown()

    app = FastAPI(
        title="docqa-ai",
        version="1.0.0",
        lifespan=lifespan,
    )
    app.state.settings = settings
    app.state.agent_service = agent_service
    app.state.owns_service = agent_service is None
    app.state.redis_client = None
    app.state.tracer = tracer
    app.state.tracer_provider = tracer_provider
    app.state.metrics = metrics
    app.state.meter_provider = meter_provider
    app.state.business_metrics = business

    if tracer_provider is not None:
        FastAPIInstrumentor.instrument_app(
            app,
            tracer_provider=tracer_provider,
            excluded_urls="health",
        )
    app.add_middleware(TraceContextMiddleware, tracer=tracer)

    @app.exception_handler(AgentError)
    async def agent_error_handler(request: Request, exc: AgentError) -> JSONResponse:
        problem = exc.to_problem()
        return JSONResponse(status_code=exc.status, content=problem.as_dict())

    @app.exception_handler(Exception)
    async def unhandled_error_handler(request: Request, exc: Exception) -> JSONResponse:
        trace_id = getattr(request.state, "header_trace_id", None)
        log_event(
            LOGGER,
            logging.ERROR,
            "Unhandled API error",
            step="api",
            httpStatus=500,
            errorType=type(exc).__name__,
        )
        error = internal_error(trace_id=trace_id if isinstance(trace_id, str) else None)
        return JSONResponse(status_code=error.status, content=error.to_problem().as_dict())

    @app.get("/health")
    async def health() -> dict[str, str]:
        return {
            "status": "ok",
            "service": settings.service_name,
            "protocolVersion": PROTOCOL_VERSION,
        }

    if settings.metrics_exposed:

        @app.get("/metrics", include_in_schema=False)
        async def prometheus_metrics() -> Response:
            """Prometheus 文本格式导出（§4.2 的 Python 侧 9 项）。

            刻意不挂鉴权：它和内网 scrape 的常规形态一致，且序列里没有 PII、没有用户维度。
            真正的边界是「Python 端口不对公网开放」（前端不直连 Python），需要更严时可置
            `DOCQA_AI_METRICS_EXPOSED=false` 或在反向代理层限制来源。
            """

            return Response(
                content=app.state.business_metrics.render(),
                media_type="text/plain; version=0.0.4; charset=utf-8",
            )

    async def _read_ask_request(request: Request, instance: str) -> tuple[AgentAskRequest, str | None]:
        """两个问答端点共享的请求体现状（S8-3 抽出，行为与抽出前逐字节一致）。

        返回解析后的请求与 trace 线索；一切形状错误在返回前抛出 JSON ProblemDetail——
        这类拒绝发生在任何 SSE 字节之前，因此两个端点都保持「首字节前拒绝 = 普通 JSON」。
        """
        content_type = request.headers.get("content-type", "").split(";", 1)[0].strip().lower()
        if content_type != "application/json":
            raise protocol_violation(
                "Content-Type must be application/json",
                errors=[FieldError(field="body", message="unsupported content type")],
                instance=instance,
            )
        content_length = request.headers.get("content-length")
        if content_length is not None:
            try:
                length = int(content_length)
            except ValueError:
                length = 0
            if length > _MAX_BODY_BYTES:
                error = _payload_too_large()
                error.instance = instance
                raise error
        body = await _read_limited_body(request)
        try:
            payload: Any = json.loads(body)
        except (json.JSONDecodeError, UnicodeDecodeError) as exc:
            raise protocol_violation(
                "Request body is not valid JSON",
                errors=[FieldError(field="body", message="invalid JSON")],
                instance=instance,
            ) from exc
        if not isinstance(payload, dict):
            raise protocol_violation(
                "Request body must be a JSON object",
                errors=[FieldError(field="body", message="expected a JSON object")],
                trace_id=_trace_hint(payload, request),
                instance=instance,
            )
        trace_hint = _trace_hint(payload, request)
        try:
            ask_request = AgentAskRequest.model_validate_json(body)
        except ValidationError as exc:
            raise protocol_violation(
                "Request body failed protocol validation",
                errors=_field_errors(exc),
                trace_id=trace_hint,
                instance=instance,
            ) from exc
        return ask_request, trace_hint

    def _align_trace(request: Request, ask_request: AgentAskRequest) -> None:
        header_trace = getattr(request.state, "header_trace_id", None)
        continuation = bool(getattr(request.state, "trace_continuation", False))
        if isinstance(header_trace, str) and header_trace != ask_request.trace_id:
            trace.get_current_span().set_attribute("trace.mismatch", True)
            trace.get_current_span().set_attribute("expected_trace_id", ask_request.trace_id)
        elif not continuation:
            annotate_expected_trace(ask_request.trace_id, False)

    def _remaining_deadline(received_at: float, ask_request: AgentAskRequest, instance: str) -> Deadline:
        elapsed_ms = int((time.monotonic() - received_at) * 1000)
        remaining_ms = ask_request.deadline_ms - elapsed_ms
        if remaining_ms <= 0:
            raise deadline_exceeded(trace_id=ask_request.trace_id, instance=instance)
        return Deadline(remaining_ms)

    @app.post("/agent/ask", response_model=AgentAskResponse)
    async def agent_ask(request: Request) -> AgentAskResponse:
        received_at = time.monotonic()
        ask_request, _trace_hint_value = await _read_ask_request(request, "/agent/ask")
        _align_trace(request, ask_request)
        deadline = _remaining_deadline(received_at, ask_request, "/agent/ask")
        service: AgentAskService = request.app.state.agent_service
        return await service.ask(ask_request, deadline=deadline)

    @app.post("/agent/ask/stream")
    async def agent_ask_stream(request: Request) -> Response:
        """流式问答（S8-3 / D-177）：响应恒为 text/event-stream，事件为 delta/done/error。

        请求体形狀错误与协议协商（protocolVersion 必须是 1.1）在升流前拒绝，返回普通
        JSON ProblemDetail（明确 4xx）；进入流之后的失败由 ask_stream 抛出的 AgentError
        统一转为 error 帧——网关的 meta 帧先于 Python 调用发出，治理拒绝只能以帧表达。
        """
        received_at = time.monotonic()
        ask_request, _trace_hint_value = await _read_ask_request(request, "/agent/ask/stream")
        if ask_request.protocol_version != STREAM_PROTOCOL_VERSION:
            raise protocol_violation(
                f"Stream endpoint requires protocolVersion {STREAM_PROTOCOL_VERSION}",
                errors=[
                    FieldError(
                        field="protocolVersion",
                        message=f"must be {STREAM_PROTOCOL_VERSION} for the stream endpoint",
                    )
                ],
                trace_id=ask_request.trace_id,
                instance="/agent/ask/stream",
            )
        _align_trace(request, ask_request)
        deadline = _remaining_deadline(received_at, ask_request, "/agent/ask/stream")
        service: AgentAskService = request.app.state.agent_service

        async def frames() -> AsyncIterator[bytes]:
            try:
                async for event in service.ask_stream(ask_request, deadline=deadline):
                    if event.kind == "delta":
                        yield _sse_frame("delta", json.dumps({"text": event.text}, ensure_ascii=False))
                    else:
                        if event.response is None:
                            raise AgentError(
                                code=AGT_UPSTREAM_004,
                                status=500,
                                title="Agent stream produced an invalid event",
                                detail="done event is missing the response payload",
                                problem_type="https://docqa.example/problems/agent-internal",
                            )
                        yield _sse_frame("done", event.response.model_dump_json(by_alias=True))
            except AgentError as exc:
                yield _sse_frame("error", json.dumps({"code": exc.code, "detail": exc.detail}, ensure_ascii=False))

        return StreamingResponse(frames(), media_type="text/event-stream")

    return app


app = create_app()


def main() -> None:
    settings = Settings()
    configure_logging(settings.log_level, settings.service_name)
    if sys.platform == "win32":
        # uvicorn 在 Windows 上硬编码 ProactorEventLoop 并绕过全局策略，而 psycopg 异步连接会直接拒绝它，
        # 因此这里自建 Server 并用 Selector 循环驱动，否则 PGVector 检索恒定 503。
        asyncio.set_event_loop_policy(asyncio.WindowsSelectorEventLoopPolicy())
        config = uvicorn.Config(app, host=settings.api_host, port=settings.api_port, log_config=None)
        asyncio.run(uvicorn.Server(config).serve())
        return
    uvicorn.run(app, host=settings.api_host, port=settings.api_port, log_config=None)


if __name__ == "__main__":
    main()
