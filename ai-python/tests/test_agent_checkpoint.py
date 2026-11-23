from __future__ import annotations

import json
from collections.abc import AsyncIterator

import pytest
from fakeredis.aioredis import FakeRedis
from langgraph.checkpoint.memory import InMemorySaver
from langgraph.checkpoint.serde.jsonplus import JsonPlusSerializer
from pydantic import ValidationError

from docqa_ai.agent.graph import (
    BUDGET_FALLBACK_ANSWER,
    AgentGraph,
    AgentState,
    ToolExecution,
    question_fingerprint,
)
from docqa_ai.agent.service import AgentAskService
from docqa_ai.governance.budget import BudgetSnapshot, Deadline, InMemoryBudgetStore
from docqa_ai.governance.kill_switch import StaticKillSwitch
from docqa_ai.protocol.errors import AgentError
from docqa_ai.protocol.models import DocumentScope
from docqa_ai.providers.embeddings import OfflineEmbeddingProvider
from docqa_ai.providers.planner import PlanContext, PlannerOutput, PlannerStreamEvent
from docqa_ai.rag.repository import InMemoryVectorRepository
from docqa_ai.rag.service import RetrievalService
from docqa_ai.tools.base import ToolContext, ToolResult
from docqa_ai.tools.registry import ToolRegistry
from tests.conftest import CONVERSATION_ID, DOCUMENT_ID, TENANT_ID, TRACE_ID, make_request, make_settings

SCOPE = DocumentScope(document_id=DOCUMENT_ID, index_version="v1")
THREAD_ID = f"{TENANT_ID}:{CONVERSATION_ID}"
LOCK_KEY = f"agent:ckpt:{TENANT_ID}:{CONVERSATION_ID}"
CHECKPOINT_DSN = "postgresql://checkpoint:test@127.0.0.1:5432/docqa_vec"


def budget_store(*, token_budget: int = 8000, max_rounds: int = 2, max_tools: int = 1) -> InMemoryBudgetStore:
    return InMemoryBudgetStore(
        BudgetSnapshot(
            tokens_used=0,
            tool_calls_made=0,
            rounds_used=0,
            token_budget=token_budget,
            max_tool_calls=max_tools,
            max_rounds=max_rounds,
            deadline_at="2026-10-27T10:00:10Z",
        )
    )


def context(**overrides: object) -> PlanContext:
    data: dict[str, object] = {
        "question": "Python 的 GIL 是什么？",
        "history": (),
        "context_blocks": (),
        "observations": (),
        "allowed_tools": ("calculator",),
        "round_number": 1,
        "max_rounds": 2,
        "max_output_tokens": 64,
        "model": "offline-extractive",
        "tool_specs": (),
    }
    data.update(overrides)
    return PlanContext(**data)  # type: ignore[arg-type]


def retrieval() -> RetrievalService:
    return RetrievalService(InMemoryVectorRepository(), OfflineEmbeddingProvider())


class ScriptedPlanner:
    def __init__(self, *outputs: PlannerOutput) -> None:
        self.outputs = list(outputs)
        self.calls: list[PlanContext] = []

    async def plan(self, plan_context: PlanContext, timeout_s: float) -> PlannerOutput:
        self.calls.append(plan_context)
        return self.outputs.pop(0)


class StreamPlanner:
    """每轮按脚本发 delta/final 事件；流式图只走 astream，plan 被调用即失败。"""

    def __init__(self, *rounds: list[PlannerStreamEvent]) -> None:
        self.rounds = list(rounds)
        self.calls: list[PlanContext] = []

    async def plan(self, plan_context: PlanContext, timeout_s: float) -> PlannerOutput:
        raise AssertionError("streaming graph must not call plan()")

    async def astream(self, plan_context: PlanContext, timeout_s: float) -> AsyncIterator[PlannerStreamEvent]:
        self.calls.append(plan_context)
        for event in self.rounds.pop(0):
            yield event


class FlakyTool:
    name = "calculator"
    source = "builtin"
    description = "计算器"
    input_hint = "算术表达式"

    def __init__(self, fail_times: int) -> None:
        self.fail_times = fail_times
        self.calls = 0

    async def run(self, argument: str, context: ToolContext) -> ToolResult:
        self.calls += 1
        if self.calls <= self.fail_times:
            raise RuntimeError("simulated process crash")
        return ToolResult(observation="计算结果是 5")


def tool_output(token_in: int = 10, token_out: int = 5) -> PlannerOutput:
    return PlannerOutput(kind="tool", tool_name="calculator", tool_input="2+3", token_in=token_in, token_out=token_out)


def final_output(answer: str = "结果是 5") -> PlannerOutput:
    return PlannerOutput(kind="final", answer=answer, citation_indexes=(), token_in=10, token_out=5)


def make_graph(
    planner: object,
    tool: FlakyTool,
    saver: InMemorySaver,
    sink: list[str] | None = None,
) -> AgentGraph:
    return AgentGraph(
        planner,  # type: ignore[arg-type]
        ToolRegistry([tool]),
        budget_store(),
        Deadline(5000),
        TRACE_ID,
        max_output_tokens=64,
        planner_name="offline",
        scopes=(SCOPE,),
        retrieval=retrieval(),
        answer_sink=None if sink is None else sink.append,
        checkpointer=saver,
    )


def make_service(
    planner: object,
    *,
    saver: InMemorySaver | None,
    redis: FakeRedis,
    tools: ToolRegistry | None = None,
) -> AgentAskService:
    return AgentAskService(
        make_settings(checkpoint_enabled=True, checkpoint_dsn=CHECKPOINT_DSN),
        budget_store(),
        StaticKillSwitch(False),
        retrieval(),
        planner,  # type: ignore[arg-type]
        tools or ToolRegistry([]),
        checkpointer=saver,
        redis=redis,
    )


def thread_config(thread_id: str = THREAD_ID) -> dict:
    return {"configurable": {"thread_id": thread_id}}


async def thread_values(saver: InMemorySaver, thread_id: str = THREAD_ID) -> dict:
    checkpoint_tuple = await saver.aget_tuple(thread_config(thread_id))
    if checkpoint_tuple is None:
        return {}
    return dict(checkpoint_tuple.checkpoint.get("channel_values", {}))


# ---------- 序列化 ----------


def test_jsonplus_serializer_round_trips_full_agent_state() -> None:
    """state 必须全是 JSON 原语：往返后与 json 归一形态逐字段相等，无 dataclass/pickle 回退。"""

    state: AgentState = {
        "round_number": 2,
        "observations": ("计算结果是 5",),
        "answer": "结果是 5",
        "citation_indexes": (1, 2),
        "token_in": 20,
        "token_out": 10,
        "budget_exhausted": False,
        "terminal": False,
        "pending_tool": ("calculator", "2+3"),
        "tool_refused": False,
        "tool_executions": (
            {
                "tool_name": "calculator",
                "success": True,
                "masked_count": 1,
                "pii_categories": ("PHONE",),
                "source": "builtin",
            },
        ),
        "tool_signatures": ("calculator\x002+3",),
        "question_fingerprint": question_fingerprint("Python 的 GIL 是什么？"),
    }
    serde = JsonPlusSerializer()
    restored = serde.loads_typed(serde.dumps_typed(state))
    assert restored == json.loads(json.dumps(state))
    assert isinstance(restored["tool_executions"][0], dict)


# ---------- 图级续跑 ----------


async def test_mid_tool_crash_resume_executes_tool_once_with_rebound_sink() -> None:
    saver = InMemorySaver()
    tool = FlakyTool(fail_times=1)
    sink1: list[str] = []
    graph1 = make_graph(StreamPlanner([PlannerStreamEvent(kind="final", output=tool_output())]), tool, saver, sink1)
    with pytest.raises(RuntimeError):
        await graph1.run(context(), thread_id="t1")
    values = await thread_values(saver, "t1")
    assert tuple(values["pending_tool"]) == ("calculator", "2+3")
    assert values["terminal"] is False
    assert values["question_fingerprint"] == question_fingerprint("Python 的 GIL 是什么？")

    sink2: list[str] = []
    planner2 = StreamPlanner(
        [PlannerStreamEvent(kind="delta", text="结果是 5"), PlannerStreamEvent(kind="final", output=final_output())]
    )
    graph2 = make_graph(planner2, tool, saver, sink2)
    result = await graph2.run(context(), thread_id="t1", resume=True)
    # 第一次调用崩在工具里，续跑只补执行这一次；sink 是新请求的闭包，旧 sink 不收任何字节。
    assert tool.calls == 2
    assert result.answer == "结果是 5"
    assert len(result.tool_executions) == 1
    assert isinstance(result.tool_executions[0], ToolExecution)
    assert result.tool_executions[0].pii_categories == ()
    assert sink2 == ["结果是 5"]
    assert sink1 == []
    assert planner2.calls[0].round_number == 2
    assert planner2.calls[0].observations == ("计算结果是 5",)


async def test_resume_requires_a_thread_id() -> None:
    graph = make_graph(ScriptedPlanner(final_output()), FlakyTool(0), InMemorySaver())
    with pytest.raises(ValueError):
        await graph.run(context(), resume=True)


# ---------- 服务级续跑 ----------


async def test_service_resume_continues_rounds_and_deletes_thread_on_completion() -> None:
    saver = InMemorySaver()
    redis = FakeRedis(decode_responses=True)
    tool = FlakyTool(fail_times=1)
    tools = ToolRegistry([tool])
    request = make_request(allowedTools=["calculator"], maxToolCalls=1, maxRounds=2)
    service1 = make_service(ScriptedPlanner(tool_output()), saver=saver, redis=redis, tools=tools)
    with pytest.raises(AgentError) as raised:
        await service1.ask(request)
    assert raised.value.code == "AGT-INTERNAL-001"
    assert await redis.get(LOCK_KEY) is None  # 失败也释放单飞锁

    planner2 = ScriptedPlanner(final_output())
    service2 = make_service(planner2, saver=saver, redis=redis, tools=tools)
    response = await service2.ask(request)
    assert response.answer == "结果是 5"
    assert tool.calls == 2
    assert planner2.calls[0].round_number == 2
    assert planner2.calls[0].observations == ("计算结果是 5",)
    assert response.audit_events[-1].eventType == "AGENT_COMPLETED"
    # 终态完成即删恢复态：下一问从头开始，锁也已释放。
    assert await thread_values(saver) == {}
    assert await redis.get(LOCK_KEY) is None


async def test_fingerprint_mismatch_deletes_thread_and_runs_fresh() -> None:
    saver = InMemorySaver()
    redis = FakeRedis(decode_responses=True)
    tool = FlakyTool(fail_times=1)
    tools = ToolRegistry([tool])
    service1 = make_service(ScriptedPlanner(tool_output()), saver=saver, redis=redis, tools=tools)
    with pytest.raises(AgentError):
        await service1.ask(make_request(allowedTools=["calculator"], maxToolCalls=1, maxRounds=2))

    planner2 = ScriptedPlanner(final_output("新问题的答案"))
    service2 = make_service(planner2, saver=saver, redis=redis, tools=tools)
    response = await service2.ask(make_request(question="另一个问题", piiMaskedQuestion="另一个问题"))
    assert response.answer == "新问题的答案"
    assert planner2.calls[0].round_number == 1
    assert planner2.calls[0].observations == ()
    assert tool.calls == 1  # 旧 thread 被删，崩溃的工具调用不再续跑


async def test_persisted_token_cap_stops_resume_without_llm_calls() -> None:
    saver = InMemorySaver()
    redis = FakeRedis(decode_responses=True)
    tool = FlakyTool(fail_times=1)
    tools = ToolRegistry([tool])
    request = make_request(allowedTools=["calculator"], maxToolCalls=1, maxRounds=2)
    service1 = make_service(
        ScriptedPlanner(tool_output(token_in=4000, token_out=4000)), saver=saver, redis=redis, tools=tools
    )
    with pytest.raises(AgentError):
        await service1.ask(request)

    planner2 = ScriptedPlanner(final_output())
    service2 = make_service(planner2, saver=saver, redis=redis, tools=tools)
    response = await service2.ask(request)
    assert response.answer == BUDGET_FALLBACK_ANSWER
    assert response.budget_status == "BUDGET_EXHAUSTED"
    assert response.citations == []
    assert planner2.calls == []
    assert tool.calls == 1
    assert response.audit_events[-1].eventType == "AGENT_COMPLETED"
    assert await thread_values(saver) == {}


async def test_persisted_round_cap_stops_resume_without_llm_calls() -> None:
    saver = InMemorySaver()
    redis = FakeRedis(decode_responses=True)
    tool = FlakyTool(fail_times=1)
    tools = ToolRegistry([tool])
    service1 = make_service(ScriptedPlanner(tool_output()), saver=saver, redis=redis, tools=tools)
    with pytest.raises(AgentError):
        await service1.ask(make_request(allowedTools=["calculator"], maxToolCalls=1, maxRounds=2))

    planner2 = ScriptedPlanner(final_output())
    service2 = make_service(planner2, saver=saver, redis=redis, tools=tools)
    response = await service2.ask(make_request(allowedTools=["calculator"], maxToolCalls=1, maxRounds=1))
    assert response.answer == BUDGET_FALLBACK_ANSWER
    assert response.budget_status == "BUDGET_EXHAUSTED"
    assert planner2.calls == []


async def test_held_lock_fails_through_existing_upstream_mapping() -> None:
    redis = FakeRedis(decode_responses=True)
    await redis.set(LOCK_KEY, "another-trace")
    planner = ScriptedPlanner(final_output())
    service = make_service(planner, saver=InMemorySaver(), redis=redis)
    with pytest.raises(AgentError) as raised:
        await service.ask(make_request())
    assert raised.value.code == "AGT-UPSTREAM-001"
    assert any(event.eventType == "AGENT_FAILED" for event in raised.value.audit_events)
    assert planner.calls == []
    assert await redis.get(LOCK_KEY) == "another-trace"  # 不碰他人持有的锁


async def test_ask_stream_resume_rebinds_sink_and_keeps_delta_done_contract() -> None:
    saver = InMemorySaver()
    redis = FakeRedis(decode_responses=True)
    tool = FlakyTool(fail_times=1)
    tools = ToolRegistry([tool])
    request = make_request(allowedTools=["calculator"], maxToolCalls=1, maxRounds=2)
    service1 = make_service(
        StreamPlanner([PlannerStreamEvent(kind="final", output=tool_output())]), saver=saver, redis=redis, tools=tools
    )
    with pytest.raises(AgentError):
        async for _ in service1.ask_stream(request):
            pass

    service2 = make_service(
        StreamPlanner(
            [PlannerStreamEvent(kind="delta", text="结果是 5"), PlannerStreamEvent(kind="final", output=final_output())]
        ),
        saver=saver,
        redis=redis,
        tools=tools,
    )
    events = [event async for event in service2.ask_stream(request)]
    assert [event.kind for event in events] == ["delta", "done"]
    assert events[0].text == "结果是 5"
    assert events[1].response is not None
    assert events[1].response.answer == "结果是 5"
    assert tool.calls == 2
    assert await thread_values(saver) == {}


# ---------- 开关关闭与配置守卫 ----------


async def test_flag_off_path_is_unchanged_and_touches_no_checkpoint_keys() -> None:
    redis = FakeRedis(decode_responses=True)
    planner = ScriptedPlanner(final_output("离线答案"))
    service = make_service(planner, saver=None, redis=redis)
    response = await service.ask(make_request())
    assert response.answer == "离线答案"
    assert planner.calls[0].round_number == 1
    assert await redis.keys("agent:ckpt:*") == []


def test_checkpoint_enabled_without_any_dsn_is_rejected() -> None:
    with pytest.raises(ValidationError):
        make_settings(checkpoint_enabled=True)


def test_checkpoint_dsn_falls_back_to_pg_dsn_and_summary_holds_no_dsn() -> None:
    settings = make_settings(checkpoint_enabled=True, pg_dsn=CHECKPOINT_DSN)
    assert settings.checkpoint_dsn == ""
    settings = make_settings(checkpoint_enabled=True, checkpoint_dsn=CHECKPOINT_DSN)
    summary = settings.redacted_summary()
    assert summary["checkpoint_enabled"] is True
    assert summary["checkpoint_dsn_configured"] is True
    assert "postgresql://" not in str(summary)
    assert make_settings().redacted_summary()["checkpoint_enabled"] is False
