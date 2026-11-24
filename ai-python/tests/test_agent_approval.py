"""人在回路：高风险工具 interrupt 挂起 / 批准续跑 / 拒绝续跑 / 关闭时行为不变。"""

from __future__ import annotations

import pytest
from langgraph.checkpoint.memory import MemorySaver

from docqa_ai.agent.graph import AgentGraph
from docqa_ai.governance.budget import Deadline
from docqa_ai.providers.planner import PlannerOutput
from docqa_ai.tools.calculator import CalculatorTool
from docqa_ai.tools.registry import ToolRegistry
from tests.conftest import TRACE_ID
from tests.test_agent_graph import SCOPE, ScriptedPlanner, budget_store, context, retrieval


def _graph(planner: ScriptedPlanner, *, approval: bool, saver: MemorySaver) -> AgentGraph:
    return AgentGraph(
        planner,
        ToolRegistry([CalculatorTool()]),
        budget_store(max_tools=2),
        Deadline(5000),
        TRACE_ID,
        max_output_tokens=64,
        planner_name="offline",
        scopes=(SCOPE,),
        retrieval=retrieval(),
        checkpointer=saver,
        approval_check=(lambda name: True) if approval else None,
    )


def _tool_then_final() -> ScriptedPlanner:
    return ScriptedPlanner(
        PlannerOutput(kind="tool", tool_name="calculator", tool_input="1+1"),
        PlannerOutput(kind="final", answer="42", citation_indexes=()),
    )


@pytest.mark.asyncio
async def test_high_risk_tool_interrupts_and_keeps_thread() -> None:
    saver = MemorySaver()
    graph = _graph(_tool_then_final(), approval=True, saver=saver)
    result = await graph.run(context(allowed_tools=("calculator",)), thread_id="t-approval")
    assert result.pending_approval == {"tool": "calculator", "input": "1+1"}
    assert result.tool_executions == ()
    assert saver.get_tuple({"configurable": {"thread_id": "t-approval"}}) is not None


@pytest.mark.asyncio
async def test_resume_approved_executes_tool_then_final() -> None:
    saver = MemorySaver()
    graph = _graph(_tool_then_final(), approval=True, saver=saver)
    await graph.run(context(allowed_tools=("calculator",)), thread_id="t-ok")
    result = await graph.run(
        context(allowed_tools=("calculator",)),
        thread_id="t-ok",
        resume=True,
        resume_value={"approved": True},
    )
    assert result.pending_approval is None
    assert result.answer == "42"
    assert len(result.tool_executions) == 1
    assert result.tool_executions[0].success


@pytest.mark.asyncio
async def test_resume_denied_skips_tool_and_plans_final() -> None:
    saver = MemorySaver()
    graph = _graph(_tool_then_final(), approval=True, saver=saver)
    await graph.run(context(allowed_tools=("calculator",)), thread_id="t-no")
    result = await graph.run(
        context(allowed_tools=("calculator",)),
        thread_id="t-no",
        resume=True,
        resume_value={"approved": False},
    )
    assert result.pending_approval is None
    assert result.answer == "42"
    assert result.tool_executions == ()


@pytest.mark.asyncio
async def test_approval_disabled_executes_immediately() -> None:
    saver = MemorySaver()
    graph = _graph(_tool_then_final(), approval=False, saver=saver)
    result = await graph.run(context(allowed_tools=("calculator",)), thread_id="t-off")
    assert result.pending_approval is None
    assert len(result.tool_executions) == 1
    assert result.answer == "42"
