from __future__ import annotations

import json
import uuid
from collections.abc import AsyncIterator
from datetime import UTC, datetime

import httpx
import pytest

from docqa_ai.agent.graph import BUDGET_FALLBACK_ANSWER
from docqa_ai.agent.service import AgentAskService, AskStreamEvent
from docqa_ai.api.main import create_app
from docqa_ai.governance.audit import new_event
from docqa_ai.governance.budget import BudgetSnapshot, Deadline, InMemoryBudgetStore
from docqa_ai.governance.experiment_budget import InMemoryExperimentBudgetStore
from docqa_ai.governance.kill_switch import StaticKillSwitch
from docqa_ai.protocol.errors import AgentError, kill_switch_open
from docqa_ai.protocol.models import (
    PROTOCOL_VERSION,
    STREAM_PROTOCOL_VERSION,
    AgentAskResponse,
    Citation,
)
from docqa_ai.providers.embeddings import OfflineEmbeddingProvider
from docqa_ai.providers.planner import (
    OfflinePlanner,
    OpenAIPlanner,
    PlanContext,
    PlannerOutput,
    PlannerOutputInvalid,
    PlannerStreamEvent,
    PlannerUnavailable,
)
from docqa_ai.rag.repository import ChunkRecord, InMemoryVectorRepository
from docqa_ai.rag.service import RetrievalService
from docqa_ai.tools.calculator import CalculatorTool
from docqa_ai.tools.registry import ToolRegistry
from tests.conftest import DOCUMENT_ID, TRACE_ID, make_request, make_settings, request_payload

CONTENT = "Python 的 GIL 是全局解释器锁，同一时刻只允许一个线程执行字节码。"


def budget_store() -> InMemoryBudgetStore:
    return InMemoryBudgetStore(
        BudgetSnapshot(
            tokens_used=0,
            tool_calls_made=0,
            rounds_used=0,
            token_budget=8000,
            max_tool_calls=1,
            max_rounds=2,
            deadline_at="2026-10-27T10:00:10Z",
        )
    )


async def retrieval_with(content: str) -> RetrievalService:
    embedder = OfflineEmbeddingProvider()
    vector = (await embedder.embed([content], 1.0))[0]
    repository = InMemoryVectorRepository(
        [
            ChunkRecord(
                vector_id=uuid.uuid4(),
                document_id=DOCUMENT_ID,
                chunk_index=0,
                content=content,
                embedding=tuple(vector),
                metadata={"index_version": "v1"},
            )
        ]
    )
    return RetrievalService(repository, embedder, min_score=0.0)


def build_service(
    planner: object,
    *,
    retrieval: RetrievalService | None = None,
    kill: bool = False,
    experiment: InMemoryExperimentBudgetStore | None = None,
    tools: ToolRegistry | None = None,
    deadline_clock: object | None = None,
) -> AgentAskService:
    return AgentAskService(
        make_settings(),
        budget_store(),
        StaticKillSwitch(kill),
        retrieval or RetrievalService(InMemoryVectorRepository(), OfflineEmbeddingProvider()),
        planner, # type: ignore[arg-type]
        tools or ToolRegistry([]),
        experiment=experiment,
    )


async def collect(service: AgentAskService, request: object, deadline: Deadline | None = None) -> list[AskStreamEvent]:
    return [event async for event in service.ask_stream(request, deadline=deadline)] # type: ignore[arg-type]


# ---------- provider 层（astream） ----------


async def test_offline_planner_astream_emits_single_delta_with_full_answer() -> None:
    planner = OfflinePlanner()
    context = PlanContext(
        question="GIL 是什么？",
        history=(),
        context_blocks=(),
        observations=(),
        allowed_tools=(),
        round_number=1,
        max_rounds=2,
        max_output_tokens=64,
        model="offline-extractive",
    )
    sync_output = await planner.plan(context, 1.0)
    events = [event async for event in planner.astream(context, 1.0)]
    assert [event.kind for event in events] == ["delta", "final"]
    assert events[0].text == sync_output.answer
    assert events[1].output == sync_output


async def test_offline_planner_astream_tool_action_emits_no_delta() -> None:
    planner = OfflinePlanner()
    context = PlanContext(
        question="计算 1+2 等于多少？",
        history=(),
        context_blocks=(),
        observations=(),
        allowed_tools=("calculator",),
        round_number=1,
        max_rounds=2,
        max_output_tokens=64,
        model="offline-extractive",
    )
    events = [event async for event in planner.astream(context, 1.0)]
    assert [event.kind for event in events] == ["final"]
    assert events[0].output is not None
    assert events[0].output.kind == "tool"


def _sse_body(*frames: str) -> bytes:
    return "".join(f"data: {frame}\n\n" for frame in frames).encode()


def _openai_stream_planner(body: bytes, status: int = 200) -> OpenAIPlanner:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(status, content=body)

    client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    return OpenAIPlanner("http://llm.test/v1", "key", "test-model", client=client)


def _stream_context(allowed_tools: tuple[str, ...] = ()) -> PlanContext:
    return PlanContext(
        question="问题",
        history=(),
        context_blocks=(),
        observations=(),
        allowed_tools=allowed_tools,
        round_number=1,
        max_rounds=2,
        max_output_tokens=64,
        model="test-model",
    )


async def test_openai_planner_astream_parses_sse_frames_into_single_delta() -> None:
    final_json = json.dumps({"action": "final", "answer": "流式答案", "citations": []}, ensure_ascii=False)
    planner = _openai_stream_planner(
        _sse_body(
            json.dumps({"choices": [{"delta": {"content": '{"action":"final",'}}]}),
            json.dumps({"choices": [{"delta": {"content": '"answer":"流式答案","citations":[]}'}}]}),
            "[DONE]",
            final_json,
        )
    )
    events = [event async for event in planner.astream(_stream_context(), 5.0)]
    assert [event.kind for event in events] == ["delta", "final"]
    assert events[0].text == "流式答案"
    assert events[1].output is not None
    assert events[1].output.kind == "final"
    assert events[1].output.answer == "流式答案"
    await planner._client.aclose() # type: ignore[union-attr]


async def test_openai_planner_astream_tool_action_emits_no_delta() -> None:
    tool_json = json.dumps({"action": "tool", "tool": "calculator", "input": "1+2"})
    body = _sse_body(
        json.dumps({"choices": [{"delta": {"content": tool_json}}]}),
        "[DONE]",
    )
    planner = _openai_stream_planner(body)
    events = [event async for event in planner.astream(_stream_context(("calculator",)), 5.0)]
    assert [event.kind for event in events] == ["final"]
    assert events[0].output is not None
    assert events[0].output.kind == "tool"


async def test_openai_planner_astream_emits_incremental_deltas_as_answer_streams() -> None:
    """的逐 token 形态：答案要边收边发，不能等全文收齐再吐一次。"""

    pieces = ['{"action":"final",', '"answer":"', "流式", "答案", '",', '"citations":[]}']
    body = _sse_body(*[json.dumps({"choices": [{"delta": {"content": piece}}]}) for piece in pieces], "[DONE]")
    planner = _openai_stream_planner(body)
    events = [event async for event in planner.astream(_stream_context(), 5.0)]
    deltas = [event.text for event in events if event.kind == "delta"]
    assert "".join(deltas) == "流式答案"
    assert len(deltas) >= 2
    assert events[-1].kind == "final"
    await planner._client.aclose() # type: ignore[union-attr]


async def test_openai_planner_astream_decodes_escape_split_across_chunks() -> None:
    """`\\uXXXX` 被切成两片时不能吐出半个转义，也不能丢字。"""

    pieces = ['{"action":"final","answer":"', "\\u6d", "41", "\\u5f0f", '"}']
    body = _sse_body(*[json.dumps({"choices": [{"delta": {"content": piece}}]}) for piece in pieces], "[DONE]")
    planner = _openai_stream_planner(body)
    events = [event async for event in planner.astream(_stream_context(), 5.0)]
    deltas = [event.text for event in events if event.kind == "delta"]
    assert "".join(deltas) == "流式"
    await planner._client.aclose() # type: ignore[union-attr]


async def test_openai_planner_astream_recovers_when_action_follows_answer() -> None:
    """prompt 要求 action 在前，但不能假设模型听话：键序颠倒时仍要吐出完整答案。"""

    pieces = ['{"answer":"', "先答案", '","action":"final"}']
    body = _sse_body(*[json.dumps({"choices": [{"delta": {"content": piece}}]}) for piece in pieces], "[DONE]")
    planner = _openai_stream_planner(body)
    events = [event async for event in planner.astream(_stream_context(), 5.0)]
    deltas = [event.text for event in events if event.kind == "delta"]
    assert "".join(deltas) == "先答案"
    await planner._client.aclose() # type: ignore[union-attr]


async def test_openai_planner_astream_reads_usage_frame_after_empty_choices() -> None:
    """usage 帧的 choices 为空数组；若先按 choices 过滤就会把它丢掉，token 退回估算。"""

    answer = json.dumps({"action": "final", "answer": "真值答案", "citations": []}, ensure_ascii=False)
    planner = _openai_stream_planner(
        _sse_body(
            json.dumps({"choices": [{"delta": {"content": answer}}], "usage": None}),
            json.dumps({"choices": [], "usage": {"prompt_tokens": 777, "completion_tokens": 88}}),
            "[DONE]",
        )
    )
    events = [event async for event in planner.astream(_stream_context(), 5.0)]
    output = events[-1].output
    assert output is not None
    assert (output.token_in, output.token_out) == (777, 88)
    await planner._client.aclose() # type: ignore[union-attr]


async def test_openai_planner_astream_falls_back_to_estimate_without_usage() -> None:
    answer = json.dumps({"action": "final", "answer": "估算答案", "citations": []}, ensure_ascii=False)
    planner = _openai_stream_planner(
        _sse_body(
            json.dumps({"choices": [{"delta": {"content": answer}}]}),
            "[DONE]",
        )
    )
    events = [event async for event in planner.astream(_stream_context(), 5.0)]
    output = events[-1].output
    assert output is not None
    assert output.token_in > 0
    assert output.token_out > 0
    assert (output.token_in, output.token_out) != (777, 88)
    await planner._client.aclose() # type: ignore[union-attr]


async def test_openai_planner_astream_drops_stream_options_when_provider_rejects_it() -> None:
    """不认识 stream_options 的实现会 400：去掉它重试一次，功能不能被计量增强打挂。"""

    answer = json.dumps({"action": "final", "answer": "重试答案", "citations": []}, ensure_ascii=False)
    seen: list[dict] = []

    def handler(request: httpx.Request) -> httpx.Response:
        payload = json.loads(request.content)
        seen.append(payload)
        if "stream_options" in payload:
            return httpx.Response(400, json={"error": {"message": "unknown field: stream_options"}})
        frame = json.dumps({"choices": [{"delta": {"content": answer}}]})
        return httpx.Response(200, content=_sse_body(frame, "[DONE]"))

    client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    planner = OpenAIPlanner("http://llm.test/v1", "key", "test-model", client=client)
    events = [event async for event in planner.astream(_stream_context(), 5.0)]
    assert events[-1].output is not None
    assert events[-1].output.answer == "重试答案"
    assert len(seen) == 2
    assert "stream_options" in seen[0]
    assert "stream_options" not in seen[1]
    await client.aclose()
    await planner._client.aclose() # type: ignore[union-attr]


async def test_openai_planner_astream_malformed_frame_is_unavailable() -> None:
    planner = _openai_stream_planner(b"data: {not json}\n\n")
    with pytest.raises(PlannerUnavailable):
        async for _ in planner.astream(_stream_context(), 5.0):
            pass
    await planner._client.aclose() # type: ignore[union-attr]


async def test_openai_planner_astream_non_2xx_is_unavailable() -> None:
    planner = _openai_stream_planner(b"error", status=500)
    with pytest.raises(PlannerUnavailable):
        async for _ in planner.astream(_stream_context(), 5.0):
            pass
    await planner._client.aclose() # type: ignore[union-attr]


async def test_openai_planner_astream_empty_content_is_output_invalid() -> None:
    planner = _openai_stream_planner(_sse_body("[DONE]"))
    with pytest.raises(PlannerOutputInvalid):
        async for _ in planner.astream(_stream_context(), 5.0):
            pass
    await planner._client.aclose() # type: ignore[union-attr]


# ---------- 服务层（ask_stream） ----------


async def test_ask_stream_emits_single_delta_then_done() -> None:
    service = build_service(OfflinePlanner(), retrieval=await retrieval_with(CONTENT))
    events = await collect(service, make_request())
    assert [event.kind for event in events] == ["delta", "done"]
    delta, done = events
    assert delta.text == done.response.answer # type: ignore[union-attr]
    assert done.response is not None
    assert done.response.protocol_version == PROTOCOL_VERSION
    assert done.response.trace_id == TRACE_ID
    assert done.response.budget_status in {"OK", "LOW_CONFIDENCE"}
    assert len(done.response.audit_events) >= 1
    assert done.response.audit_events[-1].eventType == "AGENT_COMPLETED"


async def test_ask_stream_audit_event_types_match_sync_path() -> None:
    # 谓词：两条路径各自完整跑一遍，审计事件的 eventType 序列逐事件相等。
    # durationMs / occurredAt 天然逐次不同，不断言；键集合由同一 collector 白名单保证。
    sync_service = build_service(OfflinePlanner(), retrieval=await retrieval_with(CONTENT))
    stream_service = build_service(OfflinePlanner(), retrieval=await retrieval_with(CONTENT))
    sync_response = await sync_service.ask(make_request())
    stream_events = await collect(stream_service, make_request())
    done = stream_events[-1]
    assert done.kind == "done"
    assert [event.eventType for event in done.response.audit_events] == [ # type: ignore[union-attr]
        event.eventType for event in sync_response.audit_events
    ]
    sync_completed = sync_response.audit_events[-1]
    stream_completed = done.response.audit_events[-1] # type: ignore[union-attr]
    assert stream_completed.payloadRedacted["chunkCount"] == sync_completed.payloadRedacted["chunkCount"]


async def test_ask_stream_kill_switch_raises_before_any_event() -> None:
    service = build_service(OfflinePlanner(), kill=True)
    with pytest.raises(AgentError) as raised:
        await collect(service, make_request())
    assert raised.value.code == "AGT-UPSTREAM-003"


async def test_ask_stream_pii_leak_raises_validation_error() -> None:
    service = build_service(OfflinePlanner())
    with pytest.raises(AgentError) as raised:
        await collect(service, make_request(piiMaskedQuestion="我的手机号是 13812345678"))
    assert raised.value.code == "AGT-VALIDATION-003"


async def test_ask_stream_experiment_exhausted_returns_done_without_deltas() -> None:
    experiment = InMemoryExperimentBudgetStore(0, 0)
    service = build_service(OfflinePlanner(), experiment=experiment)
    events = await collect(service, make_request())
    assert [event.kind for event in events] == ["done"]
    done = events[0].response
    assert done is not None
    assert done.answer == BUDGET_FALLBACK_ANSWER
    assert done.budget_status == "BUDGET_EXHAUSTED"


class _DeadlineThenToolPlanner:
    """第一轮：发一个 delta 再宣告 tool 动作；推动时钟使第二轮撞 deadline。"""

    def __init__(self, advance: object) -> None:
        self._advance = advance
        self.rounds = 0

    async def plan(self, context: PlanContext, timeout_s: float) -> PlannerOutput:
        raise AssertionError("streaming graph must not call plan()")

    async def astream(self, context: PlanContext, timeout_s: float) -> AsyncIterator[PlannerStreamEvent]:
        self.rounds += 1
        if self.rounds == 1:
            yield PlannerStreamEvent(kind="delta", text="部分回答")
            self._advance() # type: ignore[operator]
            yield PlannerStreamEvent(
                kind="final",
                output=PlannerOutput(kind="tool", tool_name="calculator", tool_input="1+2"),
            )
            return
        raise AssertionError("round 2 must be stopped by the deadline check before planning")


async def test_ask_stream_deadline_exceeded_after_deltas_raises_complete_error() -> None:
    now = [1_000.0]

    def clock() -> float:
        return now[0]

    def advance() -> None:
        now[0] += 10.0

    planner = _DeadlineThenToolPlanner(advance)
    tools = ToolRegistry([CalculatorTool()])
    service = build_service(planner, tools=tools)
    request = make_request(
        allowedTools=["calculator"],
        maxToolCalls=1,
        maxRounds=2,
        question="计算 1+2，然后继续讲 GIL",
        piiMaskedQuestion="计算 1+2，然后继续讲 GIL",
    )
    deadline = Deadline(5000, clock=clock) # 到期于 6.0，第一轮后被推到 11.0
    events: list[AskStreamEvent] = []
    with pytest.raises(AgentError) as raised:
        async for event in service.ask_stream(request, deadline=deadline):
            events.append(event)
    assert [event.kind for event in events] == ["delta"] # 软停止前已发出的 delta 不回收
    assert raised.value.code == "AGT-UPSTREAM-002"
    assert raised.value.audit_events
    assert any(event.eventType == "AGENT_DEADLINE_EXCEEDED" for event in raised.value.audit_events)


class _SyncOnlyPlanner:
    async def plan(self, context: PlanContext, timeout_s: float) -> PlannerOutput:
        return PlannerOutput(kind="final", answer="同步答案")


async def test_ask_stream_does_not_consume_sync_only_planner_without_astream() -> None:
    # Protocol 是结构化鸭子类型：graph 只在 sink 存在时调 astream，同步 planner 不受影响。
    service = build_service(_SyncOnlyPlanner(), retrieval=await retrieval_with(CONTENT))
    response = await service.ask(make_request())
    assert response.answer == "同步答案"


# ---------- 端点层（POST /agent/ask/stream） ----------


def _stream_response() -> AgentAskResponse:
    return AgentAskResponse(
        protocol_version=PROTOCOL_VERSION,
        trace_id=TRACE_ID,
        answer="流式答案",
        citations=[Citation(document_id=DOCUMENT_ID, chunk_index=0, score=0.9, snippet="snippet")],
        confidence=0.9,
        budget_status="OK",
        token_used=42,
        tool_calls_made=0,
        rounds_used=1,
        audit_events=[new_event("AGENT_COMPLETED", "agent_service", tokenCount=42)],
        finished_at=datetime.now(UTC),
    )


class StreamFakeService:
    def __init__(self, error: Exception | None = None) -> None:
        self.error = error
        self.requests: list[object] = []

    async def ask(self, request: object, *, deadline: object = None) -> AgentAskResponse:
        raise AssertionError("stream endpoint must not call ask()")

    async def ask_stream(self, request: object, *, deadline: object = None) -> AsyncIterator[AskStreamEvent]:
        self.requests.append(request)
        if self.error is not None:
            raise self.error
        yield AskStreamEvent(kind="delta", text="流式")
        yield AskStreamEvent(kind="delta", text="答案")
        yield AskStreamEvent(kind="done", response=_stream_response())


def _client(app: object) -> httpx.AsyncClient:
    transport = httpx.ASGITransport(app=app, raise_app_exceptions=False)
    return httpx.AsyncClient(transport=transport, base_url="http://test")


async def test_stream_endpoint_rejects_legacy_protocol_with_422_json() -> None:
    fake = StreamFakeService()
    app = create_app(make_settings(), agent_service=fake) # type: ignore[arg-type]
    async with _client(app) as client:
        response = await client.post("/agent/ask/stream", json=request_payload())
    assert response.status_code == 422
    assert response.headers["content-type"].startswith("application/json")
    problem = response.json()
    assert problem["code"] == "AGT-UPSTREAM-004"
    assert problem["instance"] == "/agent/ask/stream"
    assert any(error["field"] == "protocolVersion" for error in problem["errors"])
    assert fake.requests == []


async def test_stream_endpoint_streams_delta_and_done_frames() -> None:
    fake = StreamFakeService()
    app = create_app(make_settings(), agent_service=fake) # type: ignore[arg-type]
    async with _client(app) as client:
        response = await client.post(
            "/agent/ask/stream",
            json=request_payload(protocolVersion=STREAM_PROTOCOL_VERSION),
        )
    assert response.status_code == 200
    assert response.headers["content-type"].startswith("text/event-stream")
    body = response.text
    assert 'event: delta\ndata: {"text": "流式"}' in body
    assert 'event: delta\ndata: {"text": "答案"}' in body
    assert "event: done" in body
    done_line = next(line for line in body.splitlines() if line.startswith("data: ") and "protocolVersion" in line)
    done_payload = json.loads(done_line[len("data: ") :])
    assert done_payload["protocolVersion"] == PROTOCOL_VERSION
    assert done_payload["answer"] == "流式答案"
    assert done_payload["auditEvents"][0]["eventType"] == "AGENT_COMPLETED"
    assert len(fake.requests) == 1


async def test_stream_endpoint_converts_service_error_into_error_frame() -> None:
    fake = StreamFakeService(error=kill_switch_open(trace_id=TRACE_ID))
    app = create_app(make_settings(), agent_service=fake) # type: ignore[arg-type]
    async with _client(app) as client:
        response = await client.post(
            "/agent/ask/stream",
            json=request_payload(protocolVersion=STREAM_PROTOCOL_VERSION),
        )
    assert response.status_code == 200
    assert "event: error" in response.text
    error_line = next(
        line for line in response.text.splitlines() if line.startswith("data: ") and "AGT-UPSTREAM-003" in line
    )
    error_payload = json.loads(error_line[len("data: ") :])
    assert error_payload["code"] == "AGT-UPSTREAM-003"


async def test_stream_endpoint_rejects_non_json_content_type_before_sse() -> None:
    fake = StreamFakeService()
    app = create_app(make_settings(), agent_service=fake) # type: ignore[arg-type]
    async with _client(app) as client:
        response = await client.post(
            "/agent/ask/stream",
            content=b"{}",
            headers={"Content-Type": "text/plain"},
        )
    assert response.status_code == 422
    assert response.json()["code"] == "AGT-UPSTREAM-004"
    assert fake.requests == []


async def test_sync_endpoint_still_accepts_protocol_10() -> None:
    class SyncFake:
        async def ask(self, request: object, *, deadline: object = None) -> AgentAskResponse:
            return _stream_response()

    app = create_app(make_settings(), agent_service=SyncFake()) # type: ignore[arg-type]
    async with _client(app) as client:
        response = await client.post("/agent/ask", json=request_payload())
    assert response.status_code == 200
    assert response.json()["protocolVersion"] == PROTOCOL_VERSION
