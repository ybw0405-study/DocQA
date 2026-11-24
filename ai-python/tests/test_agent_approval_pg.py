"""人在回路真库回归：AsyncPostgresSaver 上 interrupt 挂起 → 批准/拒绝续跑。

无 ``DOCQA_TEST_PG_DSN`` 时跳过（CI 由 pgvector service 提供）。
"""

from __future__ import annotations

import os
import uuid

import pytest
from langgraph.checkpoint.postgres.aio import AsyncPostgresSaver
from psycopg.rows import dict_row
from psycopg_pool import AsyncConnectionPool

from tests.test_agent_approval import _graph, _tool_then_final
from tests.test_agent_graph import context

PG_DSN = os.getenv("DOCQA_TEST_PG_DSN")

pytestmark = pytest.mark.skipif(not PG_DSN, reason="DOCQA_TEST_PG_DSN not set")


@pytest.fixture
async def saver():
    pool = AsyncConnectionPool(
        conninfo=PG_DSN,
        min_size=1,
        max_size=4,
        open=False,
        kwargs={"autocommit": True, "prepare_threshold": 0, "row_factory": dict_row},
    )
    await pool.open()
    checkpointer = AsyncPostgresSaver(conn=pool)
    await checkpointer.setup()
    yield checkpointer
    await pool.close()


@pytest.mark.asyncio
async def test_interrupt_and_resume_on_real_postgres(saver) -> None:
    thread = f"approval-{uuid.uuid4()}"
    graph = _graph(_tool_then_final(), approval=True, saver=saver)
    first = await graph.run(context(allowed_tools=("calculator",)), thread_id=thread)
    assert first.pending_approval == {"tool": "calculator", "input": "1+1"}

    resumed = await graph.run(
        context(allowed_tools=("calculator",)),
        thread_id=thread,
        resume=True,
        resume_value={"approved": True},
    )
    assert resumed.pending_approval is None
    assert resumed.answer == "42"
    assert len(resumed.tool_executions) == 1


@pytest.mark.asyncio
async def test_denied_resume_on_real_postgres_skips_tool(saver) -> None:
    thread = f"approval-{uuid.uuid4()}"
    graph = _graph(_tool_then_final(), approval=True, saver=saver)
    await graph.run(context(allowed_tools=("calculator",)), thread_id=thread)
    resumed = await graph.run(
        context(allowed_tools=("calculator",)),
        thread_id=thread,
        resume=True,
        resume_value={"approved": False},
    )
    assert resumed.answer == "42"
    assert resumed.tool_executions == ()
