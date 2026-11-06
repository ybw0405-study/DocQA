from __future__ import annotations

import pytest

from docqa_ai.providers.planner import (
    OpenAIPlanner,
    PlanContext,
    PlannerOutputInvalid,
)


def context() -> PlanContext:
    return PlanContext(
        question="question",
        history=(),
        context_blocks=(),
        observations=(),
        allowed_tools=("calculator",),
        round_number=1,
        max_rounds=2,
        max_output_tokens=64,
        model="test-model",
    )


def test_tool_input_limit_is_two_thousand_chars() -> None:
    planner = OpenAIPlanner("http://127.0.0.1:1/v1", "", "test-model")
    accepted = planner._to_output(
        {"action": "tool", "tool": "calculator", "input": "1" * 2000},
        context(),
        1,
        1,
    )
    assert len(accepted.tool_input) == 2000
    with pytest.raises(PlannerOutputInvalid):
        planner._to_output(
            {"action": "tool", "tool": "calculator", "input": "1" * 2001},
            context(),
            1,
            1,
        )


def test_final_answer_citations_are_filtered_to_known_blocks() -> None:
    planner = OpenAIPlanner("http://127.0.0.1:1/v1", "", "test-model")
    output = planner._to_output(
        {"action": "final", "answer": "answer", "citations": [0, 1, "x"]},
        context(),
        1,
        1,
    )
    assert output.citation_indexes == ()
    assert output.warnings == ("citations_filtered",)
