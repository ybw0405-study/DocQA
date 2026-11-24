"""native tool_calls 通道回归：默认 json 行为不变，native 走 function tools。"""

from __future__ import annotations

import json

import httpx
import pytest

from docqa_ai.providers.planner import (
    ContextBlock,
    OpenAIPlanner,
    PlanContext,
    PlannerOutputInvalid,
)
from docqa_ai.tools.base import ToolSpec


def context(tools: tuple[str, ...] = ("calculator",)) -> PlanContext:
    return PlanContext(
        question="17 + 25 等于多少？",
        history=(),
        context_blocks=(
            ContextBlock(index=0, document_id=__import__("uuid").uuid4(), chunk_index=0, content="块", score=1.0),
        ),
        observations=(),
        allowed_tools=tools,
        round_number=1,
        max_rounds=2,
        max_output_tokens=64,
        model="test-model",
        tool_specs=(
            ToolSpec(name="calculator", source="builtin", description="算式求值", input_hint="算式字符串"),
            ToolSpec(name="mcp__srv__echo", source="mcp", description="回显", input_hint="JSON 对象"),
        ),
    )


def planner_with(handler, protocol: str = "native") -> tuple[OpenAIPlanner, list[dict]]:
    seen: list[dict] = []

    def transport(request: httpx.Request) -> httpx.Response:
        seen.append(json.loads(request.content))
        return handler(request)

    client = httpx.AsyncClient(transport=httpx.MockTransport(transport))
    return OpenAIPlanner("http://127.0.0.1:1/v1", "", "test-model", client=client, tool_protocol=protocol), seen


def message_response(message: dict) -> httpx.Response:
    return httpx.Response(200, json={"choices": [{"message": message}], "usage": {}})


@pytest.mark.asyncio
async def test_native_plan_tool_calls_become_tool_action() -> None:
    planner, seen = planner_with(
        lambda req: message_response(
            {
                "content": None,
                "tool_calls": [{"function": {"name": "calculator", "arguments": json.dumps({"input": "17+25"})}}],
            }
        )
    )
    output = await planner.plan(context(), timeout_s=5)
    assert output.kind == "tool"
    assert output.tool_name == "calculator"
    assert output.tool_input == "17+25"
    assert seen[0]["tools"][0]["function"]["name"] == "calculator"
    assert "function tools" in seen[0]["messages"][0]["content"]


@pytest.mark.asyncio
async def test_native_mcp_arguments_serialize_to_json_string() -> None:
    planner, _ = planner_with(
        lambda req: message_response(
            {
                "content": None,
                "tool_calls": [{"function": {"name": "mcp__srv__echo", "arguments": json.dumps({"text": "你好"})}}],
            }
        )
    )
    output = await planner.plan(context(tools=("mcp__srv__echo",)), timeout_s=5)
    assert output.kind == "tool"
    assert json.loads(output.tool_input) == {"text": "你好"}


@pytest.mark.asyncio
async def test_native_final_content_keeps_citation_governance() -> None:
    planner, _ = planner_with(
        lambda req: message_response({"content": json.dumps({"action": "final", "answer": "42", "citations": [0, 9]})})
    )
    output = await planner.plan(context(), timeout_s=5)
    assert output.kind == "final"
    assert output.answer == "42"
    assert output.citation_indexes == (0,)


@pytest.mark.asyncio
async def test_native_invalid_arguments_raise_output_invalid() -> None:
    planner, _ = planner_with(
        lambda req: message_response(
            {"content": None, "tool_calls": [{"function": {"name": "calculator", "arguments": "{bad"}}]}
        )
    )
    with pytest.raises(PlannerOutputInvalid):
        await planner.plan(context(), timeout_s=5)


@pytest.mark.asyncio
async def test_json_default_sends_no_tools_and_keeps_prompt() -> None:
    planner, seen = planner_with(
        lambda req: message_response({"content": json.dumps({"action": "final", "answer": "42", "citations": []})}),
        protocol="json",
    )
    output = await planner.plan(context(), timeout_s=5)
    assert output.kind == "final"
    assert "tools" not in seen[0]
    assert "只输出一个 JSON 对象" in seen[0]["messages"][0]["content"]


@pytest.mark.asyncio
async def test_native_without_allowed_tools_sends_no_tools() -> None:
    planner, seen = planner_with(
        lambda req: message_response({"content": json.dumps({"action": "final", "answer": "42", "citations": []})})
    )
    await planner.plan(context(tools=()), timeout_s=5)
    assert "tools" not in seen[0]


def sse(frames: list[dict]) -> httpx.Response:
    body = "".join(f"data: {json.dumps(f)}\n\n" for f in frames) + "data: [DONE]\n\n"
    return httpx.Response(200, text=body, headers={"content-type": "text/event-stream"})


@pytest.mark.asyncio
async def test_native_stream_accumulates_tool_call_fragments() -> None:
    frames = [
        {
            "choices": [
                {"delta": {"tool_calls": [{"index": 0, "function": {"name": "calculator", "arguments": '{"inp'}}]}}
            ]
        },
        {"choices": [{"delta": {"tool_calls": [{"index": 0, "function": {"arguments": 'ut": "17+25"}'}}]}}]},
        {"choices": [], "usage": {"prompt_tokens": 10, "completion_tokens": 5}},
    ]
    planner, seen = planner_with(lambda req: sse(frames))
    events = [event async for event in planner.astream(context(), timeout_s=5)]
    assert seen[0]["stream"] is True
    assert seen[0]["tools"][0]["function"]["name"] == "calculator"
    assert [e.kind for e in events] == ["final"]
    assert events[0].output.kind == "tool"
    assert events[0].output.tool_input == "17+25"


@pytest.mark.asyncio
async def test_native_stream_final_content_still_emits_deltas() -> None:
    answer_json = json.dumps({"action": "final", "answer": "42", "citations": [0]}, ensure_ascii=False)
    frames = [
        {"choices": [{"delta": {"content": answer_json[:12]}}]},
        {"choices": [{"delta": {"content": answer_json[12:]}}]},
    ]
    planner, _ = planner_with(lambda req: sse(frames))
    events = [event async for event in planner.astream(context(), timeout_s=5)]
    deltas = [e.text for e in events if e.kind == "delta"]
    assert "".join(deltas) == "42"
    assert events[-1].kind == "final"
    assert events[-1].output.citation_indexes == (0,)
