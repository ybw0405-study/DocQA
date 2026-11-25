from __future__ import annotations

import hashlib
import logging
import time
from collections.abc import Callable, Sequence
from dataclasses import dataclass, replace
from typing import TypedDict

from langgraph.checkpoint.base import BaseCheckpointSaver
from langgraph.graph import END, START, StateGraph
from langgraph.types import Command, interrupt
from opentelemetry import trace
from opentelemetry.trace import Tracer

from docqa_ai.agent.errors import DeadlineExceeded, PiiLeakError, PlannerProtocolError
from docqa_ai.business_metrics import BusinessMetrics
from docqa_ai.governance.budget import BudgetStore, Deadline
from docqa_ai.governance.experiment_budget import ExperimentBudgetStore
from docqa_ai.governance.tool_rate_limit import ToolRateLimiter, ToolRateLimitUnavailable
from docqa_ai.metrics import ToolMetrics
from docqa_ai.pii.masker import mask_text
from docqa_ai.protocol.models import DocumentScope
from docqa_ai.providers.planner import (
    SYSTEM_PROMPT,
    PlanContext,
    Planner,
    PlannerOutput,
    PlannerOutputInvalid,
    PlannerTimeout,
    build_user_prompt,
)
from docqa_ai.providers.tokens import estimate_messages_tokens
from docqa_ai.rag.service import RetrievalService
from docqa_ai.tools.base import ToolContext, ToolError
from docqa_ai.tools.registry import ToolRegistry

LOGGER = logging.getLogger(__name__)

BUDGET_FALLBACK_ANSWER = "本次请求已达到预算或轮次上限，无法继续生成完整回答。请缩小问题范围后重试。"
TOOL_LOOP_FALLBACK_ANSWER = "检测到重复的工具调用，已停止继续执行。请调整问题后重试。"
RATE_LIMITED_OBSERVATION = "工具调用已被限流，本次未执行；请稍后重试或降低调用频率。"
_OBSERVATIONS_OMITTED = "[较早的工具结果已省略]"


@dataclass(frozen=True)
class ToolExecution:
    tool_name: str
    success: bool
    masked_count: int
    pii_categories: tuple[str, ...]
    source: str = "builtin"


@dataclass(frozen=True)
class GraphResult:
    answer: str
    citation_indexes: tuple[int, ...]
    token_in: int
    token_out: int
    budget_exhausted: bool
    tool_executions: tuple[ToolExecution, ...]
    pending_approval: dict | None = None


class AgentState(TypedDict, total=False):
    round_number: int
    observations: tuple[str, ...]
    answer: str
    citation_indexes: tuple[int, ...]
    token_in: int
    token_out: int
    budget_exhausted: bool
    terminal: bool
    pending_tool: tuple[str, str] | None
    tool_refused: bool
    # checkpoint 序列化只认 JSON 原语：存 dict 而不是 frozen dataclass，run() 出口再重建。
    tool_executions: tuple[dict, ...]
    tool_signatures: tuple[str, ...]
    question_fingerprint: str
    # deadline 刻意不入 state：墙钟不该跨重启存活，续跑用新请求自己的 deadline。


class AgentGraph:
    """LangGraph plan/tool loop. One instance is cheap and bound to a single request."""

    def __init__(
        self,
        planner: Planner,
        tools: ToolRegistry,
        budget: BudgetStore,
        deadline: Deadline,
        trace_id: str,
        *,
        max_output_tokens: int,
        planner_name: str,
        scopes: tuple[DocumentScope, ...],
        retrieval: RetrievalService,
        tracer: Tracer | None = None,
        experiment: ExperimentBudgetStore | None = None,
        max_observation_chars: int = 6000,
        user_id: str = "",
        rate_limiter: ToolRateLimiter | None = None,
        metrics: ToolMetrics | None = None,
        business: BusinessMetrics | None = None,
        answer_sink: Callable[[str], None] | None = None,
        tenant_id: object | None = None,
        checkpointer: BaseCheckpointSaver | None = None,
        approval_check: Callable[[str], bool] | None = None,
        max_citations: int = 3,
    ) -> None:
        self._planner = planner
        self._tools = tools
        self._budget = budget
        self._deadline = deadline
        self._trace_id = trace_id
        self._max_output_tokens = max_output_tokens
        self._planner_name = planner_name
        self._scopes = scopes
        self._retrieval = retrieval
        self._tracer = tracer or trace.get_tracer("docqa_ai")
        self._experiment = experiment
        self._max_observation_chars = max(0, max_observation_chars)
        self._max_citations = max(1, max_citations)
        self._user_id = user_id
        self._rate_limiter = rate_limiter
        self._metrics = metrics
        self._business = business
        self._answer_sink = answer_sink
        self._tenant_id = tenant_id
        self._checkpointer = checkpointer
        self._approval_check = approval_check
        self._rounds_used = 0
        self._context: PlanContext | None = None
        self._graph = self._build()

    async def run(
        self,
        context: PlanContext,
        *,
        thread_id: str | None = None,
        resume: bool = False,
        resume_value: dict | None = None,
    ) -> GraphResult:
        self._context = context
        if resume and thread_id is None:
            raise ValueError("resume requires a thread_id")
        config = {"configurable": {"thread_id": thread_id}} if thread_id is not None else None
        status = "FAILED"
        try:
            if resume:
                # 续跑不喂初始 state：LangGraph 从最后完成的 super-step 重入，
                # planner / answer_sink / deadline 等每请求闭包由本实例重新绑定，sink 永不持久化。
                # resume_value 非 None 时为审批续跑：Command(resume=...) 喂给 interrupt。
                payload = Command(resume=resume_value) if resume_value is not None else None
                final = await self._graph.ainvoke(payload, config)
            else:
                state: AgentState = {
                    "round_number": 0,
                    "observations": (),
                    "token_in": 0,
                    "token_out": 0,
                    "budget_exhausted": False,
                    "terminal": False,
                    "pending_tool": None,
                    "tool_refused": False,
                    "tool_executions": (),
                    "tool_signatures": (),
                    "question_fingerprint": question_fingerprint(context.question),
                }
                final = await self._graph.ainvoke(state, config)
            status = "BUDGET_EXHAUSTED" if final.get("budget_exhausted") else "SUCCESS"
            return GraphResult(
                answer=str(final.get("answer", "")),
                citation_indexes=tuple(final.get("citation_indexes", ())),
                token_in=int(final.get("token_in", 0)),
                token_out=int(final.get("token_out", 0)),
                budget_exhausted=bool(final.get("budget_exhausted", False)),
                # 序列化会把 tuple 降成 list；GraphResult 对外形态不变，在出口归一回 tuple。
                tool_executions=tuple(
                    ToolExecution(**{**item, "pii_categories": tuple(item["pii_categories"])})
                    for item in final.get("tool_executions", ())
                ),
                pending_approval=await self._pending_interrupt(config),
            )
        finally:
            # 轮次是「每次问答」的量，只能在这里聚一次；放在 _plan 里会退化成每轮记 1，
            # 那样 sum/count 恒等于 1，失去「循环检测」的用途（§4.2 补充说明）。
            if self._business is not None:
                self._business.agent_round(max(1, self._rounds_used), status)

    def _build(self):
        builder = StateGraph(AgentState)
        builder.add_node("plan", self._plan)
        builder.add_node("tool", self._tool)
        builder.add_edge(START, "plan")
        builder.add_conditional_edges("plan", self._route, {"tool": "tool", "end": END})
        builder.add_edge("tool", "plan")
        return builder.compile(checkpointer=self._checkpointer)

    async def _pending_interrupt(self, config: dict | None) -> dict | None:
        if config is None or self._checkpointer is None:
            return None
        snap = await self._graph.aget_state(config)
        if snap is None or not snap.next:
            return None
        for task in snap.tasks:
            interrupts = getattr(task, "interrupts", ())
            if interrupts:
                return dict(interrupts[0].value)
        return None

    @staticmethod
    def _route(state: AgentState) -> str:
        return "end" if state.get("terminal", False) else "tool"

    async def _plan(self, state: AgentState) -> AgentState:
        if self._deadline.expired():
            raise DeadlineExceeded("deadline expired before planning")
        if self._context is None:
            raise PlannerProtocolError("graph was invoked without a plan context")
        round_number = int(state.get("round_number", 0)) + 1
        if not await self._budget.consume_round(self._trace_id):
            return self._budget_stop(state, round_number)
        context = replace(
            self._context,
            round_number=round_number,
            observations=_bounded_observations(
                tuple(state.get("observations", ())),
                self._max_observation_chars,
            ),
        )
        prompt_text = build_user_prompt(context)
        gate = mask_text(prompt_text)
        if gate.count:
            if self._business is not None:
                for category in gate.categories:
                    self._business.pii_blocked("input", category)
            raise PiiLeakError(gate.categories, gate.count)
        reserved = self._estimate_prompt(context) + self._max_output_tokens
        if self._experiment is not None and not await self._experiment.authorize_call(reserved):
            # 全局额度先于每轮预算判定，拒绝即零字节离开本机；反过来要先为没发生的调用回补账本。
            return self._budget_stop(state, round_number)
        if not await self._budget.reserve_tokens(self._trace_id, reserved):
            if self._experiment is not None:
                # 这次外呼没有发生，调用数与预订一起退回；只退 token 会白吃一份额度。
                await self._experiment.release_call(reserved)
            return self._budget_stop(state, round_number)
        self._rounds_used = max(self._rounds_used, round_number)
        llm_started_at = time.perf_counter()
        try:
            with self._tracer.start_as_current_span("llm.call") as span:
                span.set_attribute("llm.model", context.model)
                span.set_attribute("llm.provider", self._planner_name)
                span.set_attribute("llm.stream", self._answer_sink is not None)
                span.set_attribute("round", round_number)
                try:
                    if self._answer_sink is not None:
                        output = await self._stream_plan(context)
                    else:
                        output = await self._planner.plan(context, self._deadline.remaining_seconds())
                except PlannerOutputInvalid:
                    # 协议违规要计入失败率：§4.2 明确「不能只统计 HTTP 5xx」。
                    self._record_llm(context.model, "error", "AGT-PROTOCOL-001", llm_started_at)
                    raise
                span.set_attribute("token.in", output.token_in)
                span.set_attribute("token.out", output.token_out)
            self._record_llm(context.model, "success", None, llm_started_at, output)
        except PlannerOutputInvalid as exc:
            await self._budget.settle_tokens(self._trace_id, reserved, 0)
            raise PlannerProtocolError("planner output violated the schema") from exc
        except PlannerTimeout:
            self._record_llm(context.model, "timeout", None, llm_started_at)
            await self._budget.settle_tokens(self._trace_id, reserved, 0)
            raise
        except Exception:
            self._record_llm(context.model, "error", None, llm_started_at)
            await self._budget.settle_tokens(self._trace_id, reserved, 0)
            raise
        actual = max(0, output.token_in) + max(0, output.token_out)
        settlement = await self._budget.settle_tokens(self._trace_id, reserved, actual)
        if self._experiment is not None:
            await self._experiment.settle_call(reserved, actual)
        updates: AgentState = {
            "round_number": round_number,
            "token_in": int(state.get("token_in", 0)) + max(0, output.token_in),
            "token_out": int(state.get("token_out", 0)) + max(0, output.token_out),
        }
        if settlement.overrun:
            updates.update(
                answer=BUDGET_FALLBACK_ANSWER,
                citation_indexes=(),
                budget_exhausted=True,
                terminal=True,
            )
            return updates
        if output.kind == "tool":
            tool_name = output.tool_name or ""
            if tool_name not in context.allowed_tools or self._tools.get(tool_name) is None:
                raise PlannerProtocolError("planner requested a tool outside allowedTools")
            if _tool_signature(tool_name, output.tool_input) in tuple(state.get("tool_signatures", ())):
                # 同一工具同一参数第二次出现：不再消耗工具额度，也不再空转一轮。
                LOGGER.warning("Repeated tool call stopped: tool=%s round=%s", tool_name, round_number)
                updates.update(
                    answer=TOOL_LOOP_FALLBACK_ANSWER,
                    citation_indexes=(),
                    budget_exhausted=False,
                    terminal=True,
                    pending_tool=None,
                )
                return updates
            if round_number >= context.max_rounds:
                if self._business is not None:
                    self._business.agent_budget_exhausted("round")
                updates.update(
                    answer=BUDGET_FALLBACK_ANSWER,
                    citation_indexes=(),
                    budget_exhausted=True,
                    terminal=True,
                )
                return updates
            if not await self._allow_tool(tool_name):
                # 每用户限流先于每请求额度判定，拒绝的调用不吃工具预算，但仍记录观测与签名。
                updates.update(
                    pending_tool=(tool_name, output.tool_input),
                    tool_refused=True,
                    terminal=False,
                )
                return updates
            if not await self._budget.consume_tool(self._trace_id):
                updates.update(
                    answer=BUDGET_FALLBACK_ANSWER,
                    citation_indexes=(),
                    budget_exhausted=True,
                    terminal=True,
                )
                return updates
            updates.update(
                pending_tool=(tool_name, output.tool_input),
                tool_refused=False,
                terminal=False,
            )
            return updates
        indexes = tuple(
            index
            for index in dict.fromkeys(output.citation_indexes)
            if any(block.index == index for block in context.context_blocks)
        )[: self._max_citations]
        updates.update(
            answer=output.answer,
            citation_indexes=indexes,
            terminal=True,
            pending_tool=None,
            tool_refused=False,
        )
        return updates

    async def _stream_plan(self, context: PlanContext) -> PlannerOutput:
        """流式终答：delta 经 sink 实时外发，final 与 `plan` 产出同构。

        工具轮次与预算停止的内部形态完全不变——provider 保证 tool 动作不发 delta，
        这里只负责搬运与终态解析。
        """
        output: PlannerOutput | None = None
        async for event in self._planner.astream(context, self._deadline.remaining_seconds()):
            if event.kind == "delta":
                if self._answer_sink is not None:
                    self._answer_sink(event.text)
            elif event.output is not None:
                output = event.output
        if output is None:
            raise PlannerOutputInvalid("planner stream ended without a final event")
        return output

    async def _tool(self, state: AgentState) -> AgentState:
        if self._deadline.expired():
            raise DeadlineExceeded("deadline expired before tool execution")
        pending = state.get("pending_tool")
        if not pending:
            return {"terminal": True}
        tool_name, argument = pending
        tool = self._tools.get(tool_name)
        if tool is None:
            raise PlannerProtocolError("tool is not registered")
        source = self._tools.source(tool_name) or "builtin"
        if self._approval_check is not None and self._approval_check(tool_name):
            decision = interrupt({"tool": tool_name, "input": argument})
            if not isinstance(decision, dict) or not decision.get("approved"):
                return {
                    "observations": tuple(state.get("observations", ()))
                    + (f"[tool {tool_name} 被审批人拒绝，未执行]",),
                    "pending_tool": None,
                }
        context = ToolContext(
            scopes=self._scopes,
            retrieval=self._retrieval,
            deadline=self._deadline,
            tenant_id=self._tenant_id,
        )
        started = time.perf_counter()
        success = True
        outcome = "success"
        masked_count = 0
        categories: tuple[str, ...] = ()
        with self._tracer.start_as_current_span("tool.call") as span:
            span.set_attribute("toolName", tool_name)
            span.set_attribute("toolSource", source)
            if bool(state.get("tool_refused", False)):
                success = False
                outcome = "rate_limited"
                observation = RATE_LIMITED_OBSERVATION
            else:
                try:
                    result = await tool.run(argument, context)
                except ToolError:
                    success = False
                    outcome = "failure"
                    observation = "工具执行失败：输入无效或服务不可用。"
                else:
                    observation = result.observation
                    masked_count = result.masked_count
                    categories = result.pii_categories
            span.set_attribute("outcome", outcome)
            span.set_attribute("tool.success", success)
        elapsed = time.perf_counter() - started
        if self._metrics is not None:
            self._metrics.record_tool_call(tool_name, source, outcome)
            self._metrics.record_tool_latency(tool_name, source, elapsed)
        if self._business is not None:
            # outcome 的取值域是 success / failure / rate_limited（有界），可直接当标签。
            self._business.agent_tool_call(tool_name, outcome)
        executions = tuple(state.get("tool_executions", ())) + (
            {
                "tool_name": tool_name,
                "success": success,
                "masked_count": masked_count,
                "pii_categories": categories,
                "source": source,
            },
        )
        observations = tuple(state.get("observations", ())) + (observation,)
        signatures = tuple(state.get("tool_signatures", ()))
        if outcome != "rate_limited":
            # 限流拒绝不计入重复签名：RATE_LIMITED_OBSERVATION 告知"稍后重试"，
            # 若原参重试反而触发重复调用终止，语义自相矛盾。其余失败仍记录，防真死循环。
            signatures = signatures + (_tool_signature(tool_name, argument),)
        return {
            "observations": observations,
            "pending_tool": None,
            "tool_refused": False,
            "tool_executions": executions,
            "tool_signatures": signatures,
        }

    async def _allow_tool(self, tool_name: str) -> bool:
        if self._rate_limiter is None:
            return True
        try:
            allowed = await self._rate_limiter.allow(self._user_id)
        except ToolRateLimitUnavailable:
            # 限流器不可用即 fail-closed；宁可这一次不调用工具，也不放开无限调用。
            LOGGER.warning("Tool rate limiter unavailable, failing closed: tool=%s", tool_name)
            return False
        if not allowed:
            LOGGER.warning("Tool call rate limited: tool=%s", tool_name)
        return allowed

    def _budget_stop(self, state: AgentState, round_number: int) -> AgentState:
        # 三个调用点（全局实验额度、每请求 token 预留、结算超额）都归 token 维度。
        if self._business is not None:
            self._business.agent_budget_exhausted("token")
        return {
            "round_number": round_number,
            "answer": BUDGET_FALLBACK_ANSWER,
            "citation_indexes": (),
            "budget_exhausted": True,
            "terminal": True,
            "pending_tool": None,
            "tool_refused": False,
        }

    def _record_llm(
        self,
        model: str,
        status: str,
        error_code: str | None,
        started_at: float,
        output: PlannerOutput | None = None,
    ) -> None:
        """一次 LLM 外呼的终态。成功/失败/超时三条路径都必须走到，失败率分母才成立。"""

        if self._business is None:
            return
        self._business.llm_call(
            status=status,
            model=model,
            error_code=error_code,
            seconds=time.perf_counter() - started_at,
        )
        if output is not None:
            self._business.agent_tokens(
                token_in=max(0, output.token_in),
                token_out=max(0, output.token_out),
                model=model,
            )

    @staticmethod
    def _estimate_prompt(context: PlanContext) -> int:
        return estimate_messages_tokens(
            [
                {"role": "system", "content": SYSTEM_PROMPT},
                {"role": "user", "content": build_user_prompt(context)},
            ]
        )


def question_fingerprint(masked_question: str) -> str:
    """续跑判等键：只对掩码后的问题取 sha256，原文不进 state 也不进日志。"""

    return hashlib.sha256(masked_question.encode("utf-8")).hexdigest()


def _tool_signature(name: str, argument: str) -> str:
    return f"{name}\x00{argument}"


def _bounded_observations(observations: Sequence[str], limit: int) -> tuple[str, ...]:
    """Keep the newest tool results within a hard character budget; the marker counts toward it."""

    if not observations:
        return ()
    if limit <= 0:
        return (_OBSERVATIONS_OMITTED,)
    total_length = sum(len(item) for item in observations)
    truncated = total_length > limit
    content_limit = max(0, limit - len(_OBSERVATIONS_OMITTED)) if truncated else limit
    if content_limit == 0:
        return (_OBSERVATIONS_OMITTED,)
    kept: list[str] = []
    used = 0
    for item in reversed(observations):
        piece = item if len(item) <= content_limit else item[:content_limit]
        if used + len(piece) > content_limit:
            if not kept:
                kept.append(piece)
            break
        kept.append(piece)
        used += len(piece)
    kept.reverse()
    if truncated:
        kept.insert(0, _OBSERVATIONS_OMITTED)
    return tuple(kept)
