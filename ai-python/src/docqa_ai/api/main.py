from __future__ import annotations

import asyncio
import hmac
import json
import logging
import os
import re
import sys
import time
import uuid
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from typing import Any

import uvicorn
from fastapi import FastAPI, Request, Response
from fastapi.responses import JSONResponse, StreamingResponse
from langgraph.checkpoint.base import BaseCheckpointSaver
from opentelemetry import trace
from opentelemetry.instrumentation.fastapi import FastAPIInstrumentor
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import SpanExporter
from opentelemetry.trace import Tracer
from pydantic import ValidationError
from redis.asyncio import Redis

from docqa_ai.agent.checkpoint import build_checkpointer, setup_checkpointer
from docqa_ai.agent.service import AgentAskService
from docqa_ai.api.internal_auth import NONCE_HEADER, SIGNATURE_HEADER, TIMESTAMP_HEADER
from docqa_ai.api.internal_auth import verify as verify_internal_signature
from docqa_ai.business_metrics import BusinessMetrics
from docqa_ai.config import Settings
from docqa_ai.governance.budget import Deadline, RedisBudgetStore
from docqa_ai.governance.experiment_budget import ExperimentBudgetStore, RedisExperimentBudgetStore
from docqa_ai.governance.experiment_egress import BudgetedEmbeddingProvider, BudgetedRerankProvider
from docqa_ai.governance.kill_switch import RedisKillSwitch
from docqa_ai.governance.memory import MemoryStore, build_memory_store, setup_memory_store
from docqa_ai.governance.model_connection import (
    CredentialCipher,
    PlannerResolver,
    RedisModelConnectionStore,
    ResolvedPlanner,
)
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
    upstream_unavailable,
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
from docqa_ai.rag.pg_connection import PgConnectionProvider, build_pg_provider
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
_INTERNAL_TOKEN_HEADER = "x-internal-token"
_PRODUCTION_ENV_NAMES = ("DOCQA_ENV", "APP_ENV", "SPRING_PROFILES_ACTIVE", "DOCQA_AI_ENVIRONMENT")
_NON_PRODUCTION_VALUES = frozenset({"dev", "local", "test", "development", "testing", "ci"})
# 中间件白名单只留 /health*：探针族必须无需凭据可达；其余路径（含 /docs /openapi.json
# /redoc 与已迁走的 /metrics）一律校验，新增路由默认落在受保护一侧。
_PUBLIC_PATHS = frozenset({"/health", "/health/live", "/health/ready"})


def _is_public_path(path: str) -> bool:
    return path in _PUBLIC_PATHS or path.startswith("/health/")


def _runs_in_production() -> bool:
    """默认最严：只有显式声明 dev/local/test 才放宽，未设置任何信号一律按生产处理。

    反转前是「含 prod 才算生产」，而全仓没有任何地方设置这四个环境变量，
    导致按仓库自带配置启动时 8000 端口对所有网卡零鉴权。
    """
    for name in _PRODUCTION_ENV_NAMES:
        raw = os.getenv(name, "")
        if raw and any(part.strip().lower() in _NON_PRODUCTION_VALUES for part in raw.split(",")):
            return False
    return True


def _binds_non_loopback(host: str) -> bool:
    """监听地址是否对非本机开放。'' 与通配地址都视为开放。"""
    return host.strip().lower() in {"", "0.0.0.0", "::", "[::]"}


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


def build_repositories(
    settings: Settings,
    pg_provider: PgConnectionProvider | None = None,
) -> tuple[VectorRepository, LexicalRepository]:
    """两路仓库共用同一个 DSN 与同一个表名：同一条授权谓词的前提是同一份数据面。

    连接池由组装根注入，两路仓库共享同一个 provider；未注入时才各自直连（仅测试/离线路径）。
    """

    if not settings.pg_dsn:
        return UnavailableVectorRepository(), UnavailableLexicalRepository()
    provider = pg_provider or build_pg_provider(settings.pg_dsn, settings.pg_connect_timeout_s)
    return (
        PgVectorRepository(
            settings.pg_dsn,
            settings.pg_table,
            settings.pg_connect_timeout_s,
            settings.embedding_dimensions,
            connection_provider=provider,
        ),
        PgLexicalRepository(
            settings.pg_dsn,
            settings.pg_table,
            settings.pg_connect_timeout_s,
            connection_provider=provider,
        ),
    )


def build_experiment_ledger(settings: Settings, redis_client: Redis) -> ExperimentBudgetStore | None:
    """全局额度账本只在「真会往外发字节」的组合下构造。

    谓词与配置里的启动守卫同源（②）：三个 Provider 全离线**且未打开界面模型连接**
    ⇒ None ⇒ 一个 Redis 键都不写，默认配置与纯离线形态逐字节一致；只要有一个切真
    （或打开了界面连接）⇒ 守卫已保证额度为正，不会出现「额度 0 把每个请求都软降级」的假死。
    """

    real_egress = (
        settings.embedding_provider != "offline"
        or settings.llm_provider != "offline"
        or settings.rerank_provider != "offline"
        # 界面配置的模型连接同样真实外呼：env 全 offline 时若不构造账本，
        # 管理员在界面激活的真模型就成了不受额度约束的出口（守卫已保证此时额度为正）。
        or settings.model_connections_enabled
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
    """offline 也注入 `NoopRerank`：名次通道存在但不外呼，结果与不注入逐字段相等（有对应用例保证）。

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
    """关闭时不构造任何客户端，零外呼由结构保证，而不是靠调用点自觉。

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
                # 依赖未预置或密钥变量缺席只影响这一个 Server，不阻断问答面。
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
    *,
    pg_provider: PgConnectionProvider | None = None,
    checkpointer: BaseCheckpointSaver | None = None,
    memory: MemoryStore | None = None,
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
        # 离线抽取式 planner 用自己的身份：此前这里传 settings.llm_model，于是
        # 「provider=offline 却在审计里报 model=qwen-plus」——审计必须说真话。
        planner = OfflinePlanner()
    else:
        planner = OpenAIPlanner(
            settings.llm_base_url,
            settings.llm_api_key.get_secret_value(),
            settings.llm_model,
            tool_protocol=settings.tool_protocol,
        )
    # 单一组装根：进程内只建一次连接池，读侧两路仓库与 AgentAskService 的关闭钩子共用它。
    pg_provider = pg_provider or (
        build_pg_provider(settings.pg_dsn, settings.pg_connect_timeout_s) if settings.pg_dsn else None
    )
    repository, lexical_repository = build_repositories(settings, pg_provider)
    retrieval = RetrievalService(
        repository,
        embedder,
        top_k=settings.retrieval_top_k,
        min_score=settings.retrieval_min_score,
        embedding_timeout_s=settings.embedding_timeout_ms / 1000.0,
        embedding_dimensions=settings.embedding_dimensions,
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
        # 有真实数据面时强制租户信封：缺 tenantId 直接 fail-closed，不再静默退回 scope 隔离。
        require_tenant_id=bool(settings.pg_dsn),
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
    resolver = None
    if settings.model_connections_enabled:
        resolver = PlannerResolver(
            RedisModelConnectionStore(
                redis_client,
                settings.model_connection_key_prefix,
                settings.model_connection_cache_ms,
            ),
            CredentialCipher(
                settings.cred_master_key.get_secret_value(),
                settings.cred_previous_key_entries,
            ),
            ResolvedPlanner(planner=planner, model=planner.model, provider=settings.llm_provider),
            allowed_hosts=settings.llm_allowed_host_entries,
            allow_private=settings.llm_allow_private,
            tool_protocol=settings.tool_protocol,
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
        pg_provider=pg_provider,
        planner_resolver=resolver,
        checkpointer=checkpointer,
        redis=redis_client,
        memory=memory,
    )
    log_event(
        LOGGER,
        logging.INFO,
        "AI service initialized",
        provider=settings.llm_provider,
        model=settings.llm_model,
        webTools=len(web.tools) if web is not None else 0,
    )
    if resolver is not None:
        # 上面那行报的是 env 回退项；界面连接生效时实际模型以每请求解析结果为准。
        log_event(
            LOGGER,
            logging.INFO,
            "Model connection resolution enabled; env provider is now the fallback",
            step="model_connection",
            status="enabled",
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
    # 显式注入 settings 的是测试/嵌入式调用方，由调用方负责配置；生产入口必须走无参 create_app()。
    explicit_settings = settings is not None
    settings = settings or Settings()
    expected_token = settings.internal_token.get_secret_value()
    hmac_secret = settings.internal_hmac_secret.get_secret_value()
    startup_guard_error: str | None = None
    if not explicit_settings and _runs_in_production() and not (expected_token or hmac_secret):
        startup_guard_error = (
            "DOCQA_AI_INTERNAL_TOKEN or DOCQA_AI_INTERNAL_HMAC_SECRET must be configured "
            "in production: the data plane otherwise accepts unauthenticated identity fields"
        )
    # dev 模式默认只监听 loopback；若有人显式放宽到 0.0.0.0 又不配任何凭据，
    # 数据面就会把请求体里的 userId/tenantId 当成可信身份——必须在启动期挡住，
    # 除非部署者显式声明 allow_anonymous_api=true 承担这个风险。
    if (
        not explicit_settings
        and startup_guard_error is None
        and not expected_token
        and not hmac_secret
        and _binds_non_loopback(settings.api_host)
        and not settings.allow_anonymous_api
    ):
        startup_guard_error = (
            "data plane would listen on a non-loopback address without any internal "
            "credential; configure DOCQA_AI_INTERNAL_TOKEN (or HMAC secret), or set "
            "DOCQA_AI_ALLOW_ANONYMOUS_API=true explicitly to opt in"
        )
    if tracer is None:
        tracer, tracer_provider = build_tracing(settings, exporter=span_exporter)
    metrics, meter_provider = build_metrics(settings)
    # §4.2 的 Python 侧指标。实例随应用创建，注册表按实例隔离（见 business_metrics 模块注释）。
    business = business_metrics if business_metrics is not None else BusinessMetrics()

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        configure_logging(settings.log_level, settings.service_name)
        if app.state.startup_guard_error is not None:
            # 模块级 app = create_app() 在导入期不能抛错（ASGI server 要能加载模块），
            # 所以 fail-fast 落到启动阶段：生产缺内部令牌时进程直接起不来，而不是裸奔监听。
            raise RuntimeError(app.state.startup_guard_error)
        if app.state.owns_service:
            checkpointer = None
            if settings.checkpoint_enabled:
                # setup() 幂等且自带版本表；PG 不可达时启动直接失败，不留到运行期才暴露。
                checkpointer = build_checkpointer(settings)
                await setup_checkpointer(checkpointer)
            memory = None
            if settings.memory_enabled:
                # 与 checkpoint 同一守卫形态：启用即在启动期开池建表，失败不留到运行期。
                memory = build_memory_store(settings)
                await setup_memory_store(memory)
            service, redis_client = build_default_service(
                settings,
                app.state.tracer,
                app.state.metrics,
                app.state.business_metrics,
                checkpointer=checkpointer,
                memory=memory,
            )
            app.state.agent_service = service
            app.state.redis_client = redis_client
            # 账本上限先发布，管理面启动后立刻能读到 `limitsKnown=true`；
            # 失败只降级回显，不阻断启动（见 `publish_experiment_limits`）。
            await service.publish_experiment_limits()
            # 预热失败会被 provider 逐 Server 吞掉并保留空快照，不会阻断启动。
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
        version="2.2.0",
        lifespan=lifespan,
    )
    app.state.settings = settings
    app.state.startup_guard_error = startup_guard_error
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

    if expected_token or hmac_secret:

        @app.middleware("http")
        async def internal_token_guard(request: Request, call_next):
            """网关 → 数据面：默认拦截全部路径，白名单只留 /health*（方案 4.2 步 3）。

            配置了 internal_hmac_secret 时以 HMAC 签名（时间戳 + body）为唯一凭据，静态
            X-Internal-Token 头不再单独放行；未配置时沿用上一轮的静态 token 兼容期
            （Java WebClient 尚未接入签名，属跨服务业务边界，本轮不硬改）。
            """
            path = request.url.path
            if _is_public_path(path):
                return await call_next(request)
            if hmac_secret:
                body = await request.body()
                if len(body) > _MAX_BODY_BYTES:
                    return JSONResponse(
                        status_code=413,
                        content={"code": "AGT-AUTH-002", "detail": "signed body too large"},
                    )
                if not verify_internal_signature(
                    hmac_secret,
                    request.method,
                    path,
                    body,
                    request.headers.get(TIMESTAMP_HEADER, ""),
                    request.headers.get(SIGNATURE_HEADER, ""),
                    request.headers.get(NONCE_HEADER, ""),
                ):
                    return JSONResponse(
                        status_code=401,
                        content={"code": "AGT-AUTH-002", "detail": "internal signature missing or invalid"},
                    )
            elif not hmac.compare_digest(request.headers.get(_INTERNAL_TOKEN_HEADER, ""), expected_token):
                return JSONResponse(
                    status_code=401,
                    content={"code": "AGT-AUTH-001", "detail": "internal token missing or invalid"},
                )
            return await call_next(request)

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
        """兼容旧调用方：等价于 liveness。编排器请改用 /health/ready。"""
        return {
            "status": "ok",
            "service": settings.service_name,
            "protocolVersion": PROTOCOL_VERSION,
        }

    @app.get("/health/live", include_in_schema=False)
    async def liveness() -> dict[str, str]:
        return {"status": "ok", "service": settings.service_name}

    @app.get("/health/ready", include_in_schema=False)
    async def readiness() -> Response:
        """readiness 探真实依赖：任一依赖 down 返回 503，让编排器摘除实例。"""
        checks: dict[str, str] = {}
        redis_client = getattr(app.state, "redis_client", None)
        if redis_client is None:
            checks["redis"] = "unconfigured"
        else:
            try:
                await asyncio.wait_for(redis_client.ping(), timeout=0.5)
                checks["redis"] = "ok"
            except Exception:  # noqa: BLE001 - 探针只关心可达性，异常细节不进响应
                checks["redis"] = "down"
        service = getattr(app.state, "agent_service", None)
        pg_provider = getattr(service, "pg_provider", None)
        if pg_provider is None:
            checks["pg"] = "unconfigured"
        else:
            try:
                async with pg_provider.connection() as connection:
                    async with connection.cursor() as cursor:
                        await asyncio.wait_for(cursor.execute("SELECT 1"), timeout=0.5)
                checks["pg"] = "ok"
            except Exception:  # noqa: BLE001 - 同上，不把驱动细节暴露给 LB
                checks["pg"] = "down"
        degraded = any(value == "down" for value in checks.values())
        return JSONResponse(
            status_code=503 if degraded else 200,
            content={"status": "unavailable" if degraded else "ok", "checks": checks},
        )

    async def _read_ask_request(request: Request, instance: str) -> tuple[AgentAskRequest, str | None]:
        """两个问答端点共享的请求体现状（抽出，行为与抽出前逐字节一致）。

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
        """流式问答：响应恒为 text/event-stream，事件为 delta/done/error。

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
                yield _sse_frame("error", json.dumps(exc.to_problem().as_dict(), ensure_ascii=False))

        return StreamingResponse(frames(), media_type="text/event-stream")

    def _memory_store_or_503(request: Request) -> MemoryStore:
        """内部管理端点共用：记忆未启用时 503（不静默返回空清单假装功能存在）。"""
        service = getattr(request.app.state, "agent_service", None)
        memory = getattr(service, "memory", None)
        if memory is None:
            raise upstream_unavailable("Long-term memory is not enabled on this data plane.")
        return memory

    def _parse_identity(tenant_id: str, user_id: str, instance: str) -> tuple[uuid.UUID, uuid.UUID]:
        try:
            return uuid.UUID(tenant_id), uuid.UUID(user_id)
        except ValueError as exc:
            raise protocol_violation(
                "tenantId and userId must be UUIDs",
                errors=[FieldError(field="tenantId/userId", message="expected UUID values")],
                instance=instance,
            ) from exc

    @app.get("/internal/memories")
    async def internal_list_memories(request: Request, tenantId: str = "", userId: str = "") -> JSONResponse:
        """网关管理面代理入口：落在 internal-token/HMAC 中间件之后（白名单只豁免 /health*）。"""
        memory = _memory_store_or_503(request)
        tenant, user = _parse_identity(tenantId, userId, "/internal/memories")
        facts = await memory.list_facts(tenant, user)
        return JSONResponse(
            content={
                "items": [
                    {"key": fact.key, "text": fact.text, "createdAt": fact.created_at.isoformat()} for fact in facts
                ]
            }
        )

    @app.delete("/internal/memories/{key}")
    async def internal_delete_memory(request: Request, key: str, tenantId: str = "", userId: str = "") -> Response:
        memory = _memory_store_or_503(request)
        tenant, user = _parse_identity(tenantId, userId, f"/internal/memories/{key}")
        try:
            uuid.UUID(key)
        except ValueError:
            return JSONResponse(status_code=404, content={"detail": "memory not found"})
        if not await memory.forget(tenant, user, key):
            return JSONResponse(status_code=404, content={"detail": "memory not found"})
        return Response(status_code=204)

    return app


def build_metrics_app(business: BusinessMetrics) -> FastAPI:
    """方案 4.2 步 3：/metrics 从业务端口移到独立内网端口。

    主 app 不再注册任何指标端点；这个最小 app 只监听 metrics_host:metrics_port（默认
    loopback 9090），由 main() 在 metrics_exposed=True 时与主 server 一起起。生产默认
    metrics_exposed=False，因此默认零暴露面不变。
    """
    metrics_app = FastAPI(title="docqa-ai-metrics", docs_url=None, redoc_url=None, openapi_url=None)

    @metrics_app.get("/metrics", include_in_schema=False)
    async def prometheus_metrics() -> Response:
        return Response(
            content=business.render(),
            media_type="text/plain; version=0.0.4; charset=utf-8",
        )

    return metrics_app


app = create_app()


def main() -> None:
    settings = Settings()
    configure_logging(settings.log_level, settings.service_name)
    if _runs_in_production() and not settings.internal_token.get_secret_value():
        raise RuntimeError(
            "DOCQA_AI_INTERNAL_TOKEN must be configured in production: "
            "the data plane otherwise accepts unauthenticated identity fields"
        )
    if sys.platform == "win32":
        # uvicorn 在 Windows 上硬编码 ProactorEventLoop 并绕过全局策略，而 psycopg 异步连接
        # 会直接拒绝它，因此自建 Server 并用 Selector 循环驱动，否则 PGVector 检索恒定 503。
        asyncio.set_event_loop_policy(asyncio.WindowsSelectorEventLoopPolicy())
    asyncio.run(_serve(settings))


async def _serve(settings: Settings) -> None:
    """主业务端口 +（可选）独立内网 metrics 端口；后者只在 metrics_exposed=True 时监听。"""
    servers: list[uvicorn.Server] = [
        uvicorn.Server(uvicorn.Config(app, host=settings.api_host, port=settings.api_port, log_config=None))
    ]
    if settings.metrics_exposed:
        metrics_app = build_metrics_app(app.state.business_metrics)
        servers.append(
            uvicorn.Server(
                uvicorn.Config(
                    metrics_app,
                    host=settings.metrics_host,
                    port=settings.metrics_port,
                    log_config=None,
                )
            )
        )
    await asyncio.gather(*(server.serve() for server in servers))


if __name__ == "__main__":
    main()
