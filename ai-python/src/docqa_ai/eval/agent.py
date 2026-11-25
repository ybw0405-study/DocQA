"""agent 级确定性回归套件。

与检索套件互补：检索套件锁「召回/排序质量」，这里锁「治理不变量」——引用校验、
预算硬停、PII 硬拦、轮次/循环兜底、kill switch、审计事件。全部走生产的
AgentAskService / AgentGraph 构造路径，不复制任何治理逻辑；planner 用按调用顺序
回放固定 PlannerOutput 的脚本替身，Redis 用 fakeredis（预算 hash 与 kill switch
键形态与 Java 网关写入的一致），checkpointer 不启用，结果逐字节可复现。
"""

from __future__ import annotations

import json
import uuid
from collections.abc import AsyncIterator, Awaitable, Callable, Sequence
from typing import Any

from fakeredis.aioredis import FakeRedis

from docqa_ai.agent.graph import BUDGET_FALLBACK_ANSWER, TOOL_LOOP_FALLBACK_ANSWER
from docqa_ai.agent.service import AgentAskService
from docqa_ai.config import Settings
from docqa_ai.eval.corpus import load_corpus
from docqa_ai.eval.runner import _IndexedDoc, index_corpus
from docqa_ai.governance.budget import RedisBudgetStore
from docqa_ai.governance.kill_switch import RedisKillSwitch
from docqa_ai.protocol.errors import AgentError
from docqa_ai.protocol.models import AgentAskRequest
from docqa_ai.providers.embeddings import OfflineEmbeddingProvider
from docqa_ai.providers.planner import (
    PlanContext,
    PlannerOutput,
    PlannerOutputInvalid,
    PlannerStreamEvent,
)
from docqa_ai.rag.repository import InMemoryVectorRepository
from docqa_ai.rag.service import RetrievalService
from docqa_ai.tools.calculator import CalculatorTool
from docqa_ai.tools.registry import ToolRegistry

_TENANT_ID = uuid.UUID("44444444-4444-4444-8444-444444444444")
_USER_ID = uuid.UUID("11111111-1111-4111-8111-111111111111")
_CONVERSATION_ID = uuid.UUID("22222222-2222-4222-8222-222222222222")
_DIMENSIONS = 1024


class ScriptedPlanner:
    """按调用顺序回放固定 PlannerOutput 序列的 planner 替身（plan/astream 同构）。

    脚本耗尽即抛协议错误而不是循环回放：用例的前置条件被破坏时必须响亮失败，
    不能静默降级成另一条路径让断言假绿。
    """

    model = "scripted-eval"

    def __init__(self, outputs: Sequence[PlannerOutput]) -> None:
        if not outputs:
            raise ValueError("scripted planner needs at least one output")
        self._outputs = tuple(outputs)
        self._calls = 0

    def _next(self) -> PlannerOutput:
        if self._calls >= len(self._outputs):
            raise PlannerOutputInvalid("scripted planner exhausted")
        output = self._outputs[self._calls]
        self._calls += 1
        return output

    async def plan(self, context: PlanContext, timeout_s: float) -> PlannerOutput:
        return self._next()

    async def astream(self, context: PlanContext, timeout_s: float) -> AsyncIterator[PlannerStreamEvent]:
        output = self._next()
        # 与真实 provider 同一约定：tool 动作不发 delta，final 先增量后终态。
        if output.kind == "final":
            yield PlannerStreamEvent(kind="delta", text=output.answer)
        yield PlannerStreamEvent(kind="final", output=output)


def _settings() -> Settings:
    return Settings(
        _env_file=None,
        environment="test",
        embedding_provider="offline",
        llm_provider="offline",
        mq_enabled=False,
        minio_enabled=False,
        pg_dsn="",
        otel_enabled=False,
    )


def _trace_id(case_id: str) -> str:
    return uuid.uuid5(uuid.NAMESPACE_URL, f"docqa-eval-agent:{case_id}").hex


def _request(
    case_id: str,
    question: str,
    indexed: dict[str, _IndexedDoc],
    doc_ids: Sequence[str],
    *,
    token_budget: int = 8000,
    max_tool_calls: int = 1,
    max_rounds: int = 2,
    allowed_tools: Sequence[str] = (),
    pii_masked_question: str | None = None,
) -> AgentAskRequest:
    payload = {
        "protocolVersion": "1.0",
        "tenantId": str(_TENANT_ID),
        "userId": str(_USER_ID),
        "traceId": _trace_id(case_id),
        "conversationId": str(_CONVERSATION_ID),
        "question": question,
        "piiMaskedQuestion": pii_masked_question if pii_masked_question is not None else question,
        "piiMaskingVersion": "pii-v2",
        "tokenBudget": token_budget,
        "maxToolCalls": max_tool_calls,
        "maxRounds": max_rounds,
        "allowedTools": list(allowed_tools),
        "deadlineMs": 5000,
        "documentScopes": [
            {"documentId": str(indexed[doc_id].document_id), "indexVersion": "v1"} for doc_id in doc_ids
        ],
        "history": [],
    }
    # 协议模型是 strict 模式：与 conftest.make_request 同口径走 JSON 反序列化。
    return AgentAskRequest.model_validate_json(json.dumps(payload))


async def _service(
    retrieval: RetrievalService,
    planner: ScriptedPlanner,
    trace_id: str,
    *,
    token_budget: int = 8000,
    max_tool_calls: int = 1,
    max_rounds: int = 2,
) -> tuple[AgentAskService, FakeRedis]:
    redis = FakeRedis(decode_responses=True)
    # 与 Java 网关 initializeBudget 写入的 hash 字段逐一同名。
    await redis.hset(
        f"agent:budget:{trace_id}",
        mapping={
            "tokensUsed": "0",
            "toolCallsMade": "0",
            "roundsUsed": "0",
            "tokenBudget": str(token_budget),
            "maxToolCalls": str(max_tool_calls),
            "maxRounds": str(max_rounds),
            "deadlineAt": "2026-10-27T10:00:10Z",
        },
    )
    service = AgentAskService(
        _settings(),
        RedisBudgetStore(redis),
        RedisKillSwitch(redis),
        retrieval,
        planner,
        ToolRegistry([CalculatorTool()]),
    )
    return service, redis


def _event_types(response: Any) -> list[str]:
    return [event.eventType for event in response.audit_events]


def _final(answer: str, citations: tuple[int, ...] = ()) -> PlannerOutput:
    return PlannerOutput(kind="final", answer=answer, citation_indexes=citations, token_in=120, token_out=24)


def _tool(name: str, argument: str) -> PlannerOutput:
    return PlannerOutput(kind="tool", tool_name=name, tool_input=argument, token_in=120, token_out=8)


async def _case_citations_scope_and_cap(indexed: dict[str, _IndexedDoc], retrieval: RetrievalService) -> str:
    # 脚本故意给出越界序号 99 与 4 个引用：图必须过滤非法序号并截到 3 个。
    planner = ScriptedPlanner([_final("试用期与经济补偿要点如下。", (0, 1, 2, 3, 99))])
    request = _request("citations_scope_and_cap", "试用期和经济补偿怎么规定？", indexed, ["synthetic-labor"])
    service, redis = await _service(retrieval, planner, request.trace_id)
    try:
        response = await service.ask(request)
        scope_ids = {scope.document_id for scope in request.document_scopes}
        assert response.citations, "应产出引用"
        assert len(response.citations) <= 3, f"引用数超限: {len(response.citations)}"
        assert all(citation.document_id in scope_ids for citation in response.citations), "引用越出授权 scope"
        assert all(citation.chunk_index in {0, 1, 2, 3, 4, 5} for citation in response.citations)
        assert "AGENT_COMPLETED" in _event_types(response)
        return f"citations={len(response.citations)} status={response.budget_status}"
    finally:
        await redis.aclose()


async def _case_happy_path_audit(indexed: dict[str, _IndexedDoc], retrieval: RetrievalService) -> str:
    planner = ScriptedPlanner([_final("通货膨胀是物价总水平持续上涨的现象。", (0,))])
    request = _request("happy_path_audit", "通货膨胀是什么？", indexed, ["synthetic-economics"])
    service, redis = await _service(retrieval, planner, request.trace_id)
    try:
        response = await service.ask(request)
        events = _event_types(response)
        assert "RAG_RETRIEVED" in events
        assert "AGENT_COMPLETED" in events
        assert response.answer
        assert response.budget_status != "BUDGET_EXHAUSTED"
        scope_ids = {scope.document_id for scope in request.document_scopes}
        assert all(citation.document_id in scope_ids for citation in response.citations)
        return f"events={len(events)} status={response.budget_status}"
    finally:
        await redis.aclose()


async def _case_budget_exhausted(indexed: dict[str, _IndexedDoc], retrieval: RetrievalService) -> str:
    planner = ScriptedPlanner([_final("不应出现的回答", (0,))])
    request = _request("budget_exhausted", "通货膨胀是什么？", indexed, ["synthetic-economics"], token_budget=1)
    service, redis = await _service(retrieval, planner, request.trace_id, token_budget=1)
    try:
        response = await service.ask(request)
        assert response.budget_status == "BUDGET_EXHAUSTED"
        assert response.answer == BUDGET_FALLBACK_ANSWER
        assert response.citations == []
        assert response.token_used == 0
        return "BUDGET_EXHAUSTED + 兜底答 + 零引用"
    finally:
        await redis.aclose()


async def _case_pii_blocked(indexed: dict[str, _IndexedDoc], retrieval: RetrievalService) -> str:
    planner = ScriptedPlanner([_final("不应出现的回答")])
    request = _request("pii_blocked", "联系 a@b.com", indexed, ["synthetic-economics"])
    service, redis = await _service(retrieval, planner, request.trace_id)
    try:
        try:
            await service.ask(request)
        except AgentError as exc:
            assert exc.code == "AGT-VALIDATION-003", f"错误码漂移: {exc.code}"
            assert "PII_LEAK_BLOCKED" in [event.eventType for event in exc.audit_events]
            return "pii_blocked (AGT-VALIDATION-003)"
        raise AssertionError("PII 问题未被硬拦")
    finally:
        await redis.aclose()


async def _case_round_fallback(indexed: dict[str, _IndexedDoc], retrieval: RetrievalService) -> str:
    planner = ScriptedPlanner([_tool("calculator", "(2+3)*4")])
    request = _request(
        "round_fallback",
        "计算 (2+3)*4 等于多少？",
        indexed,
        ["synthetic-economics"],
        max_rounds=1,
        allowed_tools=["calculator"],
    )
    service, redis = await _service(retrieval, planner, request.trace_id, max_rounds=1)
    try:
        response = await service.ask(request)
        assert response.answer == BUDGET_FALLBACK_ANSWER
        assert response.budget_status == "BUDGET_EXHAUSTED"
        assert response.tool_calls_made == 0, "轮次兜底不应执行工具"
        return "max_rounds=1 → 轮次兜底，工具零执行"
    finally:
        await redis.aclose()


async def _case_loop_fallback(indexed: dict[str, _IndexedDoc], retrieval: RetrievalService) -> str:
    planner = ScriptedPlanner([_tool("calculator", "(2+3)*4"), _tool("calculator", "(2+3)*4")])
    request = _request(
        "loop_fallback",
        "计算 (2+3)*4 等于多少？",
        indexed,
        ["synthetic-economics"],
        max_tool_calls=2,
        max_rounds=3,
        allowed_tools=["calculator"],
    )
    service, redis = await _service(retrieval, planner, request.trace_id, max_tool_calls=2, max_rounds=3)
    try:
        response = await service.ask(request)
        assert response.answer == TOOL_LOOP_FALLBACK_ANSWER
        assert response.tool_calls_made == 1, "重复签名不应再消耗工具额度"
        assert "TOOL_EXECUTED" in _event_types(response)
        return "重复工具签名 → 循环兜底，工具只执行一次"
    finally:
        await redis.aclose()


async def _case_tool_then_final_audit(indexed: dict[str, _IndexedDoc], retrieval: RetrievalService) -> str:
    planner = ScriptedPlanner([_tool("calculator", "(2+3)*4"), _final("计算结果是 20。", (0,))])
    request = _request(
        "tool_then_final_audit",
        "计算 (2+3)*4 等于多少？",
        indexed,
        ["synthetic-economics"],
        max_rounds=2,
        allowed_tools=["calculator"],
    )
    service, redis = await _service(retrieval, planner, request.trace_id, max_rounds=2)
    try:
        response = await service.ask(request)
        events = _event_types(response)
        assert "TOOL_EXECUTED" in events
        assert "AGENT_COMPLETED" in events
        assert response.tool_calls_made == 1
        assert response.answer == "计算结果是 20。"
        return "TOOL_EXECUTED + AGENT_COMPLETED 齐全"
    finally:
        await redis.aclose()


async def _case_kill_switch(indexed: dict[str, _IndexedDoc], retrieval: RetrievalService) -> str:
    planner = ScriptedPlanner([_final("不应出现的回答")])
    request = _request("kill_switch", "通货膨胀是什么？", indexed, ["synthetic-economics"])
    service, redis = await _service(retrieval, planner, request.trace_id)
    try:
        await redis.set("agent:kill-switch", "1")
        try:
            await service.ask(request)
        except AgentError as exc:
            assert exc.code == "AGT-UPSTREAM-003", f"错误码漂移: {exc.code}"
            assert [event.eventType for event in exc.audit_events] == ["AGENT_KILL_SWITCH"]
            return "kill switch → AGT-UPSTREAM-003"
        raise AssertionError("kill switch 未拦截请求")
    finally:
        await redis.aclose()


_CASES: tuple[tuple[str, Callable[[dict[str, _IndexedDoc], RetrievalService], Awaitable[str]]], ...] = (
    ("citations_scope_and_cap", _case_citations_scope_and_cap),
    ("happy_path_audit", _case_happy_path_audit),
    ("budget_exhausted", _case_budget_exhausted),
    ("pii_blocked", _case_pii_blocked),
    ("round_fallback", _case_round_fallback),
    ("loop_fallback", _case_loop_fallback),
    ("tool_then_final_audit", _case_tool_then_final_audit),
    ("kill_switch", _case_kill_switch),
)


async def run_agent_eval() -> dict[str, Any]:
    """执行全部 agent 级用例，返回可序列化汇总；任何用例失败都如实记录，不吞异常。"""
    embedder = OfflineEmbeddingProvider(dimensions=_DIMENSIONS)
    repository = InMemoryVectorRepository()
    indexed = await index_corpus(embedder, repository, load_corpus(), tenant_id=str(_TENANT_ID))
    retrieval = RetrievalService(
        repository,
        embedder,
        top_k=6,
        min_score=0.0,
        lexical_enabled=False,
        embedding_dimensions=_DIMENSIONS,
    )
    cases: list[dict[str, Any]] = []
    try:
        for case_id, case in _CASES:
            try:
                detail = await case(indexed, retrieval)
                cases.append({"case_id": case_id, "passed": True, "detail": detail})
            except Exception as exc: # noqa: BLE001 - 评测框架要记录一切失败形态
                cases.append({"case_id": case_id, "passed": False, "detail": f"{type(exc).__name__}: {exc}"})
    finally:
        await retrieval.aclose()
    passed = sum(1 for case in cases if case["passed"])
    return {
        "suite": "agent",
        "case_count": len(cases),
        "passed_count": passed,
        "pass_rate": round(passed / len(cases), 4),
        "cases": cases,
    }
