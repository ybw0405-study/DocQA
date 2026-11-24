from __future__ import annotations

import asyncio
import contextlib
import inspect
import logging
import time
from collections.abc import AsyncIterator, Callable
from contextlib import asynccontextmanager
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import Literal

from langgraph.checkpoint.base import BaseCheckpointSaver
from opentelemetry import trace
from opentelemetry.trace import Tracer
from redis.asyncio import Redis
from redis.exceptions import RedisError

from docqa_ai.agent.checkpoint import close_checkpointer
from docqa_ai.agent.errors import CheckpointBusyError, DeadlineExceeded, PiiLeakError, PlannerProtocolError
from docqa_ai.agent.graph import BUDGET_FALLBACK_ANSWER, AgentGraph, GraphResult, question_fingerprint
from docqa_ai.business_metrics import BusinessMetrics
from docqa_ai.config import Settings
from docqa_ai.governance.audit import AuditCollector, ensure_at_least_one
from docqa_ai.governance.budget import (
    BudgetError,
    BudgetSnapshot,
    BudgetStore,
    Deadline,
)
from docqa_ai.governance.experiment_budget import ExperimentBudgetStore
from docqa_ai.governance.kill_switch import KillSwitch
from docqa_ai.governance.memory import MemoryStore, close_memory_store, memory_command_fact
from docqa_ai.governance.model_connection import PlannerResolver, ResolvedPlanner
from docqa_ai.governance.tool_rate_limit import ToolRateLimiter
from docqa_ai.logging_setup import log_event
from docqa_ai.metrics import ToolMetrics
from docqa_ai.pii.masker import VERSION as PII_VERSION
from docqa_ai.pii.masker import mask_text
from docqa_ai.protocol.errors import (
    AGT_INTERNAL_001,
    AGT_UPSTREAM_001,
    AGT_UPSTREAM_002,
    AGT_UPSTREAM_003,
    AgentError,
    deadline_exceeded,
    internal_error,
    kill_switch_open,
    pii_blocked,
    upstream_unavailable,
)
from docqa_ai.protocol.models import (
    PROTOCOL_VERSION,
    AgentAskRequest,
    AgentAskResponse,
    Citation,
    PendingApproval,
)
from docqa_ai.providers.planner import ContextBlock, PlanContext, Planner, PlannerTimeout, PlannerUnavailable
from docqa_ai.providers.tokens import estimate_tokens
from docqa_ai.rag.pg_connection import PgConnectionProvider
from docqa_ai.rag.service import (
    RetrievalDeadlineExceeded,
    RetrievalResult,
    RetrievalService,
    RetrievalUnavailable,
)
from docqa_ai.tools.mcp.provider import McpToolProvider
from docqa_ai.tools.registry import ToolRegistry
from docqa_ai.tools.web.toolset import WebToolset

LOGGER = logging.getLogger(__name__)

_INSUFFICIENT_ANSWER = "未能根据已授权资料生成可靠回答，请补充资料或缩小问题范围。"
_MAX_ANSWER = 8000
_MEMORY_ACK = "已记住这条信息，后续问答会把它作为参考；它不会改变工具与引用规则。"
_MEMORY_EMPTY = "记忆内容为空，未写入。请在「记住：」之后补充要记忆的事实。"


@dataclass(frozen=True)
class AskStreamEvent:
    """`ask_stream` 的事件单元：delta 为回答文本增量，done 携带完整 AgentAskResponse。

    失败不产出事件——按与 `ask` 相同的映射抛出 AgentError，由端点层统一转为 error 帧。
    """

    kind: Literal["delta", "done"]
    text: str = ""
    response: AgentAskResponse | None = None


@dataclass(frozen=True)
class _Prepared:
    """`ask` / `ask_stream` 共享前段的产出：图输入三件套加观测字段。"""

    history_truncated: bool
    mcp_tool_count: int
    plan_context: PlanContext
    tools: ToolRegistry
    resolved: ResolvedPlanner


def _audit_pii_block(collector: AuditCollector, categories: tuple[str, ...], count: int) -> None:
    collector.add(
        "PII_LEAK_BLOCKED",
        "agent_service",
        severity="ERROR",
        piiCategories=list(categories),
        piiCount=count,
        maskingVersion=PII_VERSION,
    )


def _map_agent_error(
    collector: AuditCollector,
    request: AgentAskRequest,
    exc: BaseException,
    deadline: Deadline,
) -> AgentError:
    """`ask` 与 `ask_stream` 共用的异常映射（ELE-2）。

    修复前两条路径各有一份逐字复制的 8 分支映射，改一处漏一处就会让同步与流式的错误码、
    审计事件分叉。这里返回一个 AgentError，由调用方 `raise ... from exc` 保持因果链。
    """
    if isinstance(exc, (DeadlineExceeded, RetrievalDeadlineExceeded)):
        collector.add(
            "AGENT_DEADLINE_EXCEEDED",
            "agent_service",
            severity="ERROR",
            errorCode=AGT_UPSTREAM_002,
        )
        return deadline_exceeded(audit_events=collector.events(), trace_id=request.trace_id)
    if isinstance(exc, PiiLeakError):
        _audit_pii_block(collector, exc.categories, exc.count)
        return pii_blocked(audit_events=collector.events(), trace_id=request.trace_id)
    if isinstance(exc, PlannerTimeout):
        expired = deadline.expired()
        collector.add(
            "AGENT_FAILED",
            "agent_service",
            severity="ERROR",
            errorCode=AGT_UPSTREAM_002 if expired else AGT_UPSTREAM_001,
        )
        error_factory = deadline_exceeded if expired else upstream_unavailable
        return error_factory(audit_events=collector.events(), trace_id=request.trace_id)
    if isinstance(
        exc, (PlannerUnavailable, PlannerProtocolError, RetrievalUnavailable, BudgetError, CheckpointBusyError)
    ):
        collector.add(
            "AGENT_FAILED",
            "agent_service",
            severity="ERROR",
            errorCode=AGT_UPSTREAM_001,
        )
        log_event(LOGGER, logging.ERROR, "Agent upstream failed", step="agent", errorType=type(exc).__name__)
        return upstream_unavailable(audit_events=collector.events(), trace_id=request.trace_id)
    collector.add(
        "AGENT_FAILED",
        "agent_service",
        severity="ERROR",
        errorCode=AGT_INTERNAL_001,
    )
    log_event(LOGGER, logging.ERROR, "Agent execution failed", step="agent", errorType=type(exc).__name__)
    return internal_error(audit_events=collector.events(), trace_id=request.trace_id)


async def _aclose_if_present(resource: object) -> None:
    closer = getattr(resource, "aclose", None)
    if closer is None:
        return
    result = closer()
    if inspect.isawaitable(result):
        await result


def _approval_predicate(settings: Settings):
    """高风险工具谓词：精确名或前缀通配（如 mcp__*）；未开启审批时返回 None。"""
    if not settings.tool_approval_enabled:
        return None
    entries = [e.strip() for e in settings.high_risk_tools.split(",") if e.strip()]

    def check(name: str) -> bool:
        return name in entries or any(e.endswith("*") and name.startswith(e[:-1]) for e in entries)

    return check


class AgentAskService:
    def __init__(
        self,
        settings: Settings,
        budget: BudgetStore,
        kill_switch: KillSwitch,
        retrieval: RetrievalService,
        planner: Planner,
        tools: ToolRegistry,
        *,
        tracer: Tracer | None = None,
        clock: Callable[[], datetime] | None = None,
        experiment: ExperimentBudgetStore | None = None,
        mcp: McpToolProvider | None = None,
        web: WebToolset | None = None,
        rate_limiter: ToolRateLimiter | None = None,
        metrics: ToolMetrics | None = None,
        business: BusinessMetrics | None = None,
        pg_provider: PgConnectionProvider | None = None,
        planner_resolver: PlannerResolver | None = None,
        checkpointer: BaseCheckpointSaver | None = None,
        redis: Redis | None = None,
        memory: MemoryStore | None = None,
    ) -> None:
        self._settings = settings
        self._budget = budget
        self._kill_switch = kill_switch
        self._retrieval = retrieval
        self._planner = planner
        self._planner_resolver = planner_resolver
        self._tools = tools
        self._tracer = tracer or trace.get_tracer("docqa_ai")
        self._clock = clock or (lambda: datetime.now(UTC))
        self._experiment = experiment
        self._mcp = mcp
        self._web = web
        self._rate_limiter = rate_limiter
        self._metrics = metrics
        self._business = business
        self._pg_provider = pg_provider
        self._checkpointer = checkpointer
        self._redis = redis
        self._memory = memory

    @property
    def pg_provider(self) -> PgConnectionProvider | None:
        """供 readiness 探针复用同一个连接池，不另开连接。"""
        return self._pg_provider

    @property
    def memory(self) -> MemoryStore | None:
        """供内部管理端点复用同一个 store；关闭时为 None，端点据此拒绝服务。"""
        return self._memory

    async def publish_experiment_limits(self) -> None:
        """启动时把实验额度上限发布到账本，供管理面回显。

        刻意 best-effort：Redis 不可用只让管理面的 `limitsKnown` 保持 false，
        不能让整个问答进程起不来——上限本身仍由配置在准入时强制执行，回显缺失不影响拦截。
        """

        if self._experiment is None:
            return
        try:
            await self._experiment.publish_limits()
        except BudgetError as exc:
            LOGGER.warning(
                "experiment budget limits were not published: error=%s",
                type(exc).__name__,
            )

    async def prewarm_tools(self) -> None:
        """lifespan 预热一次：首个请求不必承担第三方发现延迟。"""

        if self._mcp is not None:
            await self._mcp.tools(force=True)

    async def aclose(self) -> None:
        if self._mcp is not None:
            await self._mcp.aclose()
        if self._web is not None:
            await self._web.aclose()
        # CON-2 的共享 httpx client 与 CON-1 的连接池都在应用关闭时统一释放。
        await _aclose_if_present(self._planner)
        if self._planner_resolver is not None:
            # resolver 缓存的是界面连接构造的 planner；回退项就是上面那个，不重复关闭。
            await self._planner_resolver.aclose()
        await _aclose_if_present(self._retrieval)
        if self._pg_provider is not None:
            await self._pg_provider.aclose()
        if self._checkpointer is not None:
            await close_checkpointer(self._checkpointer)
        if self._memory is not None:
            await close_memory_store(self._memory)

    async def ask(self, request: AgentAskRequest, *, deadline: Deadline | None = None) -> AgentAskResponse:
        started = time.monotonic()
        deadline = deadline or Deadline(request.deadline_ms)
        collector = AuditCollector(clock=self._clock)
        try:
            prepared = await self._prepare(request, deadline, collector, started)
            if isinstance(prepared, AgentAskResponse):
                return prepared
            async with self._checkpoint_guard(request, deadline):
                decision = await self._checkpoint_decision(request, prepared, collector, started)
                if isinstance(decision, AgentAskResponse):
                    return decision
                thread_id, resume = decision
                if request.resume_approved is not None and not resume:
                    raise CheckpointBusyError("approval resume requires a resumable checkpoint")
                resume_value = (
                    {"approved": request.resume_approved} if resume and request.resume_approved is not None else None
                )
                graph = self._build_graph(request, deadline, prepared)
                with self._tracer.start_as_current_span("agent.graph") as span:
                    span.set_attribute("tenant.id", str(request.tenant_id))
                    span.set_attribute("agent.rounds_limit", request.max_rounds)
                    span.set_attribute("agent.tools_allowed", len(prepared.plan_context.allowed_tools))
                    span.set_attribute("agent.mcp_tools", prepared.mcp_tool_count)
                    result = await graph.run(
                        prepared.plan_context,
                        thread_id=thread_id,
                        resume=resume,
                        resume_value=resume_value,
                    )
                    span.set_attribute("agent.budget_exhausted", result.budget_exhausted)
                if thread_id is not None and not result.pending_approval:
                    # checkpoint 是崩溃恢复态而不是历史：终态完成即删，下一问永远从头开始。
                    # 审批挂起态保留 thread，等 ask:resume 续跑。
                    await self._checkpointer.adelete_thread(thread_id)
            final_snapshot = await self._budget.snapshot(request.trace_id)
            return self._build_response(
                request,
                result,
                final_snapshot,
                prepared.plan_context.context_blocks,
                prepared.history_truncated,
                collector,
                started,
                model=prepared.resolved.model,
            )
        except AgentError:
            raise
        except Exception as exc:  # noqa: BLE001 - 统一映射到脱敏 ProblemDetail
            raise _map_agent_error(collector, request, exc, deadline) from exc

    async def _prepare(
        self,
        request: AgentAskRequest,
        deadline: Deadline,
        collector: AuditCollector,
        started: float,
    ) -> _Prepared | AgentAskResponse:
        """`ask` 与 `ask_stream` 共享的治理前段：校验 / Kill Switch / 账本 /
        实验额度预检 / 检索 / 上下文装配 / MCP 发现。返回值是图输入；实验额度用尽时
        直接返回软降级响应（与同步路径同一 _build_response，零外呼零 delta）。"""
        with self._tracer.start_as_current_span("agent.validate"):
            question = self._validate_request_pii(request, collector)
        if await self._kill_switch.is_active(request.tenant_id):
            collector.add(
                "AGENT_KILL_SWITCH",
                "agent_service",
                severity="WARN",
                errorCode=AGT_UPSTREAM_003,
            )
            raise kill_switch_open(audit_events=collector.events(), trace_id=request.trace_id)
        if self._memory is not None:
            fact = memory_command_fact(question)
            if fact is not None:
                # 显式记忆指令：零外呼零检索，固定确认走同一 _build_response（既有审计键）。
                return await self._remember(request, fact, collector, started)
        # 解析提前到预算之前：软降级路径也要报同一个模型身份，不能两条路径各说一套。
        resolved = await self._resolve_planner(request)
        try:
            budget_snapshot = await self._budget.snapshot(request.trace_id)
        except BudgetError as exc:
            collector.add(
                "BUDGET_STATE_MISSING",
                "agent_service",
                severity="ERROR",
                errorCode=AGT_UPSTREAM_001,
            )
            log_event(
                LOGGER,
                logging.ERROR,
                "Budget snapshot unavailable",
                step="budget",
                errorType=type(exc).__name__,
            )
            raise upstream_unavailable(audit_events=collector.events(), trace_id=request.trace_id) from exc
        history, history_truncated = self._truncate_history(request)
        if self._experiment is not None:
            gate = await self._experiment.snapshot()
            if gate.calls_used >= gate.call_limit or gate.tokens_used >= gate.token_limit:
                #  ②：额度用尽即在检索前软降级，形态与图内的 `_budget_stop` 逐字段相同——
                # 零外呼、零引用、零新错误码，也不新增审计事件类型（Java 白名单不动）。
                # 账本读不到时不软降级：让 BudgetError 走既有 503 分支，宁可少花也不裸奔。
                log_event(
                    LOGGER,
                    logging.WARNING,
                    "Experiment budget exhausted before retrieval",
                    step="budget",
                    operation="experiment.snapshot",
                    status="degraded",
                    budgetStatus="BUDGET_EXHAUSTED",
                )
                return self._build_response(
                    request,
                    GraphResult(
                        answer=BUDGET_FALLBACK_ANSWER,
                        citation_indexes=(),
                        token_in=0,
                        token_out=0,
                        budget_exhausted=True,
                        tool_executions=(),
                    ),
                    budget_snapshot,
                    (),
                    history_truncated,
                    collector,
                    started,
                    model=resolved.model,
                )
        retrieved = await self._retrieve(question, request, deadline, collector)
        memory_notes: tuple[str, ...] = ()
        if self._memory is not None:
            try:
                memory_notes = await self._memory.recall(
                    request.tenant_id,
                    request.user_id,
                    limit=5,
                    max_chars=self._settings.memory_max_chars,
                )
            except Exception as exc:
                # 记忆读失败不静默降级：与检索失败同形，走既有 upstream_unavailable 映射。
                collector.add(
                    "AGENT_FAILED",
                    "agent_service",
                    severity="ERROR",
                    errorCode=AGT_UPSTREAM_001,
                )
                raise upstream_unavailable(audit_events=collector.events(), trace_id=request.trace_id) from exc
        blocks = self._context_blocks(retrieved, request, history, memory_notes)
        allowed_tools = tuple(request.deduplicated_tools())
        #  三层交集的第三层：注册表里只放「启动允许表 ∩ 实际发现」，
        # 第二层（请求 allowedTools）由 graph 的同一处校验兜住。发现失败只让工具消失，不阻断问答。
        tools = self._tools
        mcp_tool_count = 0
        if self._mcp is not None and any(name.startswith("mcp__") for name in allowed_tools):
            discovered = await self._mcp.tools()
            mcp_tool_count = len(discovered)
            if discovered:
                tools = self._tools.extended(discovered)
        plan_context = PlanContext(
            question=question,
            history=history,
            context_blocks=blocks,
            observations=(),
            allowed_tools=allowed_tools,
            round_number=1,
            max_rounds=request.max_rounds,
            max_output_tokens=self._max_output_tokens(budget_snapshot),
            model=resolved.model,
            tool_specs=tools.specs(allowed_tools),
            memory_notes=memory_notes,
        )
        return _Prepared(
            history_truncated=history_truncated,
            mcp_tool_count=mcp_tool_count,
            plan_context=plan_context,
            tools=tools,
            resolved=resolved,
        )

    async def _remember(
        self,
        request: AgentAskRequest,
        fact: str,
        collector: AuditCollector,
        started: float,
    ) -> AgentAskResponse:
        """显式记忆指令的落库与固定确认：零外呼、零检索、零新审计键。

        事实再按 v2 掩码复检：v1 客户端只承诺遮三类，银行卡这类残留在这里拒写（硬拦），
        绝不把未脱敏文本写进长期存储。确认响应复用 _build_response，形态与软降级一致。
        """

        assert self._memory is not None
        check = mask_text(fact)
        if check.count:
            self._audit_pii_block(collector, check.categories, check.count)
            raise pii_blocked(audit_events=collector.events(), trace_id=request.trace_id)
        text = check.masked.strip()
        try:
            budget_snapshot = await self._budget.snapshot(request.trace_id)
        except BudgetError as exc:
            # 与 _prepare 同一语义：预算 hash 读不到 ⇒ BUDGET_STATE_MISSING 503，不静默放行。
            collector.add(
                "BUDGET_STATE_MISSING",
                "agent_service",
                severity="ERROR",
                errorCode=AGT_UPSTREAM_001,
            )
            raise upstream_unavailable(audit_events=collector.events(), trace_id=request.trace_id) from exc
        if text:
            try:
                await self._memory.remember(request.tenant_id, request.user_id, text)
            except Exception as exc:
                collector.add(
                    "AGENT_FAILED",
                    "agent_service",
                    severity="ERROR",
                    errorCode=AGT_UPSTREAM_001,
                )
                raise upstream_unavailable(audit_events=collector.events(), trace_id=request.trace_id) from exc
        return self._build_response(
            request,
            GraphResult(
                answer=_MEMORY_ACK if text else _MEMORY_EMPTY,
                citation_indexes=(),
                token_in=0,
                token_out=0,
                budget_exhausted=False,
                tool_executions=(),
            ),
            budget_snapshot,
            (),
            False,
            collector,
            started,
            # 本路径没有解析界面连接也没有外呼：审计报启动期 planner 的身份，不虚构模型。
            model=getattr(self._planner, "model", self._settings.llm_model),
        )

    async def _resolve_planner(self, request: AgentAskRequest) -> ResolvedPlanner:
        """每请求解析一次：有界面连接就用它，没有就用启动期按 env 构造的那个。

        解析结果同时决定图用哪个 planner 与审计报哪个模型，两者不会各说一套。
        """
        if self._planner_resolver is None:
            return ResolvedPlanner(
                planner=self._planner,
                model=getattr(self._planner, "model", self._settings.llm_model),
                provider=self._settings.llm_provider,
            )
        return await self._planner_resolver.resolve(request.tenant_id)

    def _build_graph(
        self,
        request: AgentAskRequest,
        deadline: Deadline,
        prepared: _Prepared,
        *,
        answer_sink: Callable[[str], None] | None = None,
    ) -> AgentGraph:
        return AgentGraph(
            prepared.resolved.planner,
            prepared.tools,
            self._budget,
            deadline,
            request.trace_id,
            max_output_tokens=prepared.plan_context.max_output_tokens,
            planner_name=prepared.resolved.provider,
            scopes=tuple(request.document_scopes),
            retrieval=self._retrieval,
            tracer=self._tracer,
            experiment=self._experiment,
            max_observation_chars=self._settings.max_observation_chars,
            user_id=str(request.user_id),
            rate_limiter=self._rate_limiter,
            metrics=self._metrics,
            business=self._business,
            answer_sink=answer_sink,
            tenant_id=request.tenant_id,
            checkpointer=self._checkpointer,
            approval_check=_approval_predicate(self._settings),
        )

    @asynccontextmanager
    async def _checkpoint_guard(self, request: AgentAskRequest, deadline: Deadline) -> AsyncIterator[None]:
        """同一会话的 checkpoint 续跑单飞锁（SET NX，TTL=deadline）。

        被占用或 Redis 不可达都抛 CheckpointBusyError，走既有 upstream_unavailable 映射：
        两个请求同时重入同一个 thread 会重复执行工具、重复花费，宁可拒绝也不并发续跑。
        """

        if self._checkpointer is None:
            yield
            return
        if self._redis is None:
            raise CheckpointBusyError("checkpoint single-flight lock requires a redis client")
        key = f"agent:ckpt:{request.tenant_id}:{request.conversation_id}"
        try:
            acquired = await self._redis.set(key, request.trace_id, nx=True, px=max(1, deadline.remaining_ms()))
        except RedisError as exc:
            raise CheckpointBusyError("checkpoint lock unavailable") from exc
        if not acquired:
            raise CheckpointBusyError("checkpoint lock is held by another request")
        try:
            yield
        finally:
            await self._release_checkpoint_lock(key, request.trace_id)

    async def _release_checkpoint_lock(self, key: str, trace_id: str) -> None:
        # 只删自己持有的锁：TTL 到期后锁可能已属于下一个请求，无条件 DEL 会误释放。
        if self._redis is None:
            return
        try:
            pipe = self._redis.pipeline()
            try:
                await pipe.watch(key)
                if await pipe.get(key) == trace_id:
                    pipe.multi()
                    pipe.delete(key)
                    await pipe.execute()
                else:
                    await pipe.unwatch()
            finally:
                await pipe.reset()
        except RedisError:
            # 锁自带 TTL=deadline，释放失败最坏延迟到过期收敛，不影响预算硬停。
            LOGGER.warning("Checkpoint lock release failed: key=%s", key)

    async def _checkpoint_decision(
        self,
        request: AgentAskRequest,
        prepared: _Prepared,
        collector: AuditCollector,
        started: float,
    ) -> tuple[str | None, bool] | AgentAskResponse:
        """续跑判定：checkpoint 非空、未终态、问题指纹一致才 resume；否则删旧 thread 新跑。

        返回值是 (thread_id, resume)；持久化用量已触顶时直接返回与 `_budget_stop`
        同形态的零外呼响应，不再给 LLM 花一分钱。
        """

        if self._checkpointer is None:
            return None, False
        thread_id = f"{request.tenant_id}:{request.conversation_id}"
        checkpoint_tuple = await self._checkpointer.aget_tuple({"configurable": {"thread_id": thread_id}})
        values = dict(checkpoint_tuple.checkpoint.get("channel_values", {})) if checkpoint_tuple is not None else {}
        resumable = (
            bool(values)
            and not values.get("terminal", False)
            and values.get("question_fingerprint") == question_fingerprint(prepared.plan_context.question)
        )
        if values and not resumable:
            await self._checkpointer.adelete_thread(thread_id)
        if not resumable:
            return thread_id, False
        spent = int(values.get("token_in", 0)) + int(values.get("token_out", 0))
        if spent >= request.token_budget or int(values.get("round_number", 0)) >= request.max_rounds:
            log_event(
                LOGGER,
                logging.WARNING,
                "Checkpoint resume stopped by persisted budget",
                step="checkpoint",
                status="degraded",
                budgetStatus="BUDGET_EXHAUSTED",
            )
            try:
                budget_snapshot = await self._budget.snapshot(request.trace_id)
            except BudgetError as exc:
                # 与 _prepare 同一语义：预算 hash TTL 过期 ⇒ BUDGET_STATE_MISSING 503，不静默超花。
                collector.add(
                    "BUDGET_STATE_MISSING",
                    "agent_service",
                    severity="ERROR",
                    errorCode=AGT_UPSTREAM_001,
                )
                raise upstream_unavailable(audit_events=collector.events(), trace_id=request.trace_id) from exc
            await self._checkpointer.adelete_thread(thread_id)
            return self._build_response(
                request,
                GraphResult(
                    answer=BUDGET_FALLBACK_ANSWER,
                    citation_indexes=(),
                    token_in=0,
                    token_out=0,
                    budget_exhausted=True,
                    tool_executions=(),
                ),
                budget_snapshot,
                (),
                prepared.history_truncated,
                collector,
                started,
                model=prepared.resolved.model,
            )
        return thread_id, True

    async def ask_stream(
        self,
        request: AgentAskRequest,
        *,
        deadline: Deadline | None = None,
    ) -> AsyncIterator[AskStreamEvent]:
        """流式问答：与 `ask` 共享全部治理前段与异常映射，仅终答产出分叉。

        事件序列：delta* → done（载荷为完整 AgentAskResponse，auditEvents 与同步路径
        逐事件一致）。失败不产出事件——按与 `ask` 相同的映射抛出 AgentError（携带完整
        审计事件与错误码），由端点层统一转为 error 帧；客户端断开时取消图任务，
        上游 LLM 流随之取消，reserve 不退还。
        """
        started = time.monotonic()
        deadline = deadline or Deadline(request.deadline_ms)
        collector = AuditCollector(clock=self._clock)
        queue: asyncio.Queue[tuple[str, object]] = asyncio.Queue()
        graph_task: asyncio.Task[GraphResult] | None = None
        try:
            prepared = await self._prepare(request, deadline, collector, started)
            if isinstance(prepared, AgentAskResponse):
                yield AskStreamEvent(kind="done", response=prepared)
                return

            async with self._checkpoint_guard(request, deadline):
                decision = await self._checkpoint_decision(request, prepared, collector, started)
                if isinstance(decision, AgentAskResponse):
                    yield AskStreamEvent(kind="done", response=decision)
                    return
                thread_id, resume = decision
                if request.resume_approved is not None and not resume:
                    raise CheckpointBusyError("approval resume requires a resumable checkpoint")
                resume_value = (
                    {"approved": request.resume_approved} if resume and request.resume_approved is not None else None
                )

                def sink(text: str) -> None:
                    queue.put_nowait(("delta", text))

                graph = self._build_graph(request, deadline, prepared, answer_sink=sink)

                async def runner() -> GraphResult:
                    try:
                        with self._tracer.start_as_current_span("agent.graph") as span:
                            span.set_attribute("tenant.id", str(request.tenant_id))
                            span.set_attribute("agent.rounds_limit", request.max_rounds)
                            span.set_attribute("agent.tools_allowed", len(prepared.plan_context.allowed_tools))
                            span.set_attribute("agent.mcp_tools", prepared.mcp_tool_count)
                            result = await graph.run(
                                prepared.plan_context,
                                thread_id=thread_id,
                                resume=resume,
                                resume_value=resume_value,
                            )
                            span.set_attribute("agent.budget_exhausted", result.budget_exhausted)
                            return result
                    finally:
                        queue.put_nowait(("end", None))

                graph_task = asyncio.create_task(runner())
                while True:
                    kind, payload = await queue.get()
                    if kind == "end":
                        break
                    yield AskStreamEvent(kind="delta", text=str(payload))
                result = await graph_task
                if thread_id is not None and not result.pending_approval:
                    # 与同步路径同一语义：终态完成即删恢复态，不留历史；审批挂起保留。
                    await self._checkpointer.adelete_thread(thread_id)
            final_snapshot = await self._budget.snapshot(request.trace_id)
            response = self._build_response(
                request,
                result,
                final_snapshot,
                prepared.plan_context.context_blocks,
                prepared.history_truncated,
                collector,
                started,
                model=prepared.resolved.model,
            )
            yield AskStreamEvent(kind="done", response=response)
        except asyncio.CancelledError:
            if graph_task is not None:
                graph_task.cancel()
                # C-8：取消后必须 await 收口，否则取消点抛出的异常会变成
                # "exception was never retrieved" 噪音，且幽灵任务继续占用事件循环。
                with contextlib.suppress(asyncio.CancelledError, Exception):
                    await graph_task
            raise
        except GeneratorExit:
            if graph_task is not None:
                graph_task.cancel()
                with contextlib.suppress(asyncio.CancelledError, Exception):
                    await graph_task
            raise
        except AgentError:
            raise
        except Exception as exc:  # noqa: BLE001 - 与同步路径共用同一映射
            raise _map_agent_error(collector, request, exc, deadline) from exc

    def _validate_request_pii(self, request: AgentAskRequest, collector: AuditCollector) -> str:
        # 复检 Java 第一层结果必须按请求声明的契约版本：v1 客户端只承诺遮三类，
        # 用 v2 规则去复检会把未遮的银行卡误判成「Java 漏脱敏」而 422（DQ-P2-3）。
        declared = request.pii_masking_version
        original = mask_text(request.question, version=declared)
        masked_question = mask_text(request.pii_masked_question, version=declared)
        if original.count:
            collector.add(
                "PII_DIFFERENTIAL_CHECK",
                "agent_service",
                severity="INFO",
                piiCategories=list(original.categories),
                piiCount=original.count,
                maskingVersion=PII_VERSION,
            )
        if masked_question.count:
            self._audit_pii_block(collector, masked_question.categories, masked_question.count)
            raise pii_blocked(audit_events=collector.events(), trace_id=request.trace_id)
        for message in request.history:
            check = mask_text(message.pii_masked_content, version=declared)
            if message.role == "USER" and check.count:
                self._audit_pii_block(collector, check.categories, check.count)
                raise pii_blocked(audit_events=collector.events(), trace_id=request.trace_id)
            if message.role == "ASSISTANT" and check.count:
                collector.add(
                    "PII_MASKED_IN_HISTORY",
                    "agent_service",
                    severity="WARN",
                    piiCategories=list(check.categories),
                    piiCount=check.count,
                    maskingVersion=PII_VERSION,
                )
        return masked_question.masked

    def _truncate_history(self, request: AgentAskRequest) -> tuple[tuple[tuple[str, str], ...], bool]:
        remaining = self._settings.history_max_tokens
        kept: list[tuple[str, str]] = []
        truncated = False
        for message in reversed(request.history):
            content = mask_text(message.pii_masked_content).masked
            cost = estimate_tokens(content)
            if cost > remaining:
                truncated = True
                break
            kept.append((message.role, content))
            remaining -= cost
        return tuple(reversed(kept)), truncated

    async def _retrieve(
        self,
        question: str,
        request: AgentAskRequest,
        deadline: Deadline,
        collector: AuditCollector,
    ) -> RetrievalResult:
        retrieve_started = time.monotonic()
        try:
            result = await self._retrieval.retrieve(
                question,
                request.document_scopes,
                deadline=deadline,
                tenant_id=request.tenant_id,
            )
        except RetrievalDeadlineExceeded:
            raise
        except RetrievalUnavailable as exc:
            collector.add(
                "RETRIEVAL_FAILED",
                "agent_service",
                severity="ERROR",
                errorCode=AGT_UPSTREAM_001,
            )
            raise upstream_unavailable(audit_events=collector.events(), trace_id=request.trace_id) from exc
        duration_ms = int((time.monotonic() - retrieve_started) * 1000)
        collector.add(
            "RAG_RETRIEVED",
            "agent_service",
            severity="WARN" if (result.lexical_fallback or result.rerank_fallback) else "INFO",
            chunkCount=len(result.chunks),
            topScore=result.top_score,
            durationMs=duration_ms,
        )
        if result.masked_chunks:
            collector.add(
                "PII_MASKED_IN_RETRIEVAL",
                "agent_service",
                severity="WARN",
                piiCategories=list(result.pii_categories),
                piiCount=result.masked_chunks,
                maskingVersion=PII_VERSION,
            )
        return result

    def _context_blocks(
        self,
        retrieved: RetrievalResult,
        request: AgentAskRequest,
        history: tuple[tuple[str, str], ...],
        memory_notes: tuple[str, ...] = (),
    ) -> tuple[ContextBlock, ...]:
        scopes = {scope.document_id for scope in request.document_scopes}
        blocks = [
            ContextBlock(
                index=chunk.index,
                document_id=chunk.document_id,
                chunk_index=chunk.chunk_index,
                content=chunk.content,
                score=chunk.score,
            )
            for chunk in retrieved.chunks
            if chunk.document_id in scopes
        ]
        question_size = len(mask_text(request.pii_masked_question).masked)
        history_size = sum(len(content) for _, content in history)
        memory_size = sum(len(note) for note in memory_notes)
        while blocks:
            size = question_size + history_size + memory_size + sum(len(block.content) for block in blocks)
            if size <= self._settings.max_prompt_chars:
                break
            blocks.pop()
        return tuple(blocks)

    def _max_output_tokens(self, snapshot: BudgetSnapshot) -> int:
        remaining = max(0, snapshot.token_budget - snapshot.tokens_used)
        return max(16, min(self._settings.llm_max_output_tokens, remaining))

    def _build_response(
        self,
        request: AgentAskRequest,
        result: GraphResult,
        snapshot: BudgetSnapshot,
        blocks: tuple[ContextBlock, ...],
        history_truncated: bool,
        collector: AuditCollector,
        started: float,
        *,
        model: str,
    ) -> AgentAskResponse:
        scopes = {scope.document_id for scope in request.document_scopes}
        block_map = {block.index: block for block in blocks}
        citations: list[Citation] = []
        cited_scores: list[float] = []
        if not result.budget_exhausted:
            for index in result.citation_indexes:
                block = block_map.get(index)
                if block is None or block.document_id not in scopes:
                    continue
                if any(
                    citation.document_id == block.document_id and citation.chunk_index == block.chunk_index
                    for citation in citations
                ):
                    continue
                citations.append(
                    Citation(
                        document_id=block.document_id,
                        chunk_index=block.chunk_index,
                        score=block.score,
                        snippet=block.content[:200],
                    )
                )
                cited_scores.append(block.score)
        answer = result.answer.strip() or _INSUFFICIENT_ANSWER
        if result.pending_approval:
            answer = "等待人工审批：工具调用已挂起，请在会话页批准或拒绝后继续。"
        output_check = mask_text(answer)
        if output_check.count:
            collector.add(
                "PII_MASKED_IN_OUTPUT",
                "agent_service",
                severity="WARN",
                piiCategories=list(output_check.categories),
                piiCount=output_check.count,
                maskingVersion=PII_VERSION,
            )
            answer = output_check.masked.strip() or _INSUFFICIENT_ANSWER
        if mask_text(answer).count:
            self._audit_pii_block(collector, output_check.categories, output_check.count)
            raise pii_blocked(audit_events=collector.events(), trace_id=request.trace_id)
        answer = answer[: _MAX_ANSWER - 1].strip() or _INSUFFICIENT_ANSWER
        confidence = max(cited_scores) if cited_scores else 0.0
        confidence = max(0.0, min(1.0, confidence))
        clamped_tokens = min(max(0, snapshot.tokens_used), request.token_budget, snapshot.token_budget)
        clamped_tools = min(max(0, snapshot.tool_calls_made), request.max_tool_calls)
        clamped_rounds = min(max(0, snapshot.rounds_used), request.max_rounds)
        if result.pending_approval:
            budget_status = "PENDING_APPROVAL"
        elif result.budget_exhausted:
            budget_status = "BUDGET_EXHAUSTED"
        elif not citations or confidence < self._settings.low_confidence_threshold:
            budget_status = "LOW_CONFIDENCE"
        else:
            budget_status = "OK"
        for execution in result.tool_executions:
            collector.add(
                "TOOL_EXECUTED",
                "agent_graph",
                severity="INFO" if execution.success else "WARN",
                toolName=execution.tool_name,
                toolSource=execution.source,
            )
            if execution.masked_count:
                collector.add(
                    "PII_MASKED_IN_TOOL",
                    "agent_graph",
                    severity="WARN",
                    piiCategories=list(execution.pii_categories),
                    piiCount=execution.masked_count,
                    maskingVersion=PII_VERSION,
                )
        duration_ms = int((time.monotonic() - started) * 1000)
        completion_payload: dict[str, object] = {
            "durationMs": duration_ms,
            "tokenCount": clamped_tokens,
            "chunkCount": len(blocks),
            "confidence": confidence,
            "budgetStatus": budget_status,
            "model": model,
            "historyTruncated": history_truncated,
        }
        if clamped_rounds:
            completion_payload["round"] = min(3, max(1, clamped_rounds))
        if blocks:
            completion_payload["topScore"] = blocks[0].score
        collector.add("AGENT_COMPLETED", "agent_service", **completion_payload)
        ensure_at_least_one(collector, AGT_UPSTREAM_001)
        return AgentAskResponse(
            protocol_version=PROTOCOL_VERSION,
            trace_id=request.trace_id,
            answer=answer,
            citations=citations[: self._settings.max_citations],
            confidence=confidence,
            budget_status=budget_status,
            pending_approval=(PendingApproval(**result.pending_approval) if result.pending_approval else None),
            token_used=clamped_tokens,
            tool_calls_made=clamped_tools,
            rounds_used=clamped_rounds,
            audit_events=collector.events(),
            finished_at=self._clock(),
        )

    @staticmethod
    def _audit_pii_block(collector: AuditCollector, categories: tuple[str, ...], count: int) -> None:
        _audit_pii_block(collector, categories, count)
