from __future__ import annotations

import json
from datetime import UTC, datetime

import pytest
from pydantic import ValidationError

from docqa_ai.governance.audit import new_event
from docqa_ai.protocol.models import (
    PROTOCOL_VERSION,
    AgentAskRequest,
    AgentAskResponse,
    Citation,
    DocumentScope,
    ProblemDetail,
)
from tests.conftest import CONVERSATION_ID, DOCUMENT_ID, TENANT_ID, TRACE_ID, USER_ID, request_payload


def parse(payload: dict[str, object]) -> AgentAskRequest:
    return AgentAskRequest.model_validate_json(json.dumps(payload))


def test_request_has_exactly_fifteen_contract_fields() -> None:
    request = parse(request_payload())
    dumped = request.model_dump(by_alias=True)
    assert set(dumped) == {
        "protocolVersion",
        "tenantId",
        "userId",
        "traceId",
        "conversationId",
        "question",
        "piiMaskedQuestion",
        "piiMaskingVersion",
        "tokenBudget",
        "maxToolCalls",
        "maxRounds",
        "allowedTools",
        "deadlineMs",
        "documentScopes",
        "history",
    }
    assert request.tenant_id == TENANT_ID
    assert request.user_id == USER_ID
    assert request.conversation_id == CONVERSATION_ID
    assert request.document_scopes == [DocumentScope(document_id=DOCUMENT_ID, index_version="v1")]


def test_duplicate_tools_are_deduplicated_in_order() -> None:
    request = parse(request_payload(allowedTools=["calculator", "current_time", "calculator"]))
    assert request.deduplicated_tools() == ("calculator", "current_time")


def test_mcp_tool_names_follow_the_protocol_pattern() -> None:
    """D-123：允许值集合扩写，但仍是结构约束而不是任意字符串。"""

    request = parse(
        request_payload(
            allowedTools=[
                "mcp__dict__lookup",
                "mcp__a_b__c9",
                "mcp__dict__lookup",
            ]
        )
    )
    assert request.deduplicated_tools() == ("mcp__dict__lookup", "mcp__a_b__c9")

    for invalid in ("mcp__Dict__lookup", "mcp__dict", "mcp__dict__look up", f"mcp__{'d' * 33}__x", "dict__lookup"):
        with pytest.raises(ValidationError):
            parse(request_payload(allowedTools=[invalid]))


@pytest.mark.parametrize(
    "overrides",
    [
        {"protocolVersion": "2.0"},
        {"protocolVersion": "1"},
        {"tenantId": "not-a-uuid"},
        {"tenantId": None},
        {"piiMaskingVersion": "pii-v2"},
        {"traceId": "A" * 32},
        {"traceId": "abc"},
        {"tokenBudget": 0},
        {"tokenBudget": 8001},
        {"maxRounds": 0},
        {"maxRounds": 4},
        {"maxToolCalls": 4},
        {"deadlineMs": 50},
        {"deadlineMs": 15001},
        {"question": "   "},
        {"piiMaskedQuestion": ""},
        {"documentScopes": []},
        {"documentScopes": [{"documentId": str(DOCUMENT_ID), "indexVersion": "v1"}] * 2},
        {"allowedTools": ["unknown_tool"]},
        {"history": [{"role": "SYSTEM", "piiMaskedContent": "x"}]},
        {"history": [{"role": "USER", "piiMaskedContent": "x"}] * 7},
    ],
)
def test_invalid_requests_are_rejected(overrides: dict[str, object]) -> None:
    with pytest.raises(ValidationError):
        parse(request_payload(**overrides))


def test_unknown_request_field_is_rejected() -> None:
    with pytest.raises(ValidationError):
        parse(request_payload(extraField="nope"))


def test_tenant_id_is_required() -> None:
    payload = request_payload()
    payload.pop("tenantId")
    with pytest.raises(ValidationError):
        parse(payload)


def test_strict_types_reject_numeric_strings() -> None:
    with pytest.raises(ValidationError):
        parse(request_payload(tokenBudget="1000"))


def test_user_id_must_be_uuid() -> None:
    with pytest.raises(ValidationError):
        parse(request_payload(userId="not-a-uuid"))


def test_scope_index_version_is_bounded() -> None:
    with pytest.raises(ValidationError):
        parse(request_payload(documentScopes=[{"documentId": str(DOCUMENT_ID), "indexVersion": "v" * 33}]))


def test_response_serializes_with_camel_case_and_audit_events() -> None:
    event = new_event("AGENT_COMPLETED", "agent_service", tokenCount=12)
    response = AgentAskResponse(
        protocol_version=PROTOCOL_VERSION,
        trace_id=TRACE_ID,
        answer="answer",
        citations=[Citation(document_id=DOCUMENT_ID, chunk_index=0, score=0.9, snippet="snippet")],
        confidence=0.9,
        budget_status="OK",
        token_used=12,
        tool_calls_made=0,
        rounds_used=1,
        audit_events=[event],
        finished_at=datetime.now(UTC),
    )
    dumped = response.model_dump(mode="json", by_alias=True)
    assert set(dumped) == {
        "protocolVersion",
        "traceId",
        "answer",
        "citations",
        "confidence",
        "budgetStatus",
        "tokenUsed",
        "toolCallsMade",
        "roundsUsed",
        "auditEvents",
        "finishedAt",
    }
    assert dumped["auditEvents"][0]["eventType"] == "AGENT_COMPLETED"
    assert dumped["citations"][0]["documentId"] == str(DOCUMENT_ID)


def test_response_requires_at_least_one_audit_event() -> None:
    with pytest.raises(ValidationError):
        AgentAskResponse(
            protocol_version=PROTOCOL_VERSION,
            trace_id=TRACE_ID,
            answer="answer",
            confidence=0.0,
            budget_status="LOW_CONFIDENCE",
            token_used=0,
            tool_calls_made=0,
            rounds_used=0,
            audit_events=[],
            finished_at=datetime.now(UTC),
        )


def test_problem_detail_uses_stable_shape() -> None:
    problem = ProblemDetail(
        type="https://docqa.example/problems/agent-protocol",
        title="Agent protocol violation",
        status=422,
        detail="bad",
        code="AGT-UPSTREAM-004",
        traceId=TRACE_ID,
        errors=[],
        auditEvents=[],
    )
    dumped = problem.as_dict()
    assert dumped["instance"] == "/agent/ask"
    assert dumped["traceId"] == TRACE_ID
    assert dumped["status"] == 422
