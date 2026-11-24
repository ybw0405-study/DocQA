from __future__ import annotations

import uuid
from datetime import UTC, datetime

import pytest

from docqa_ai.governance.audit import (
    ALLOWED_PAYLOAD_KEYS,
    AuditCollector,
    AuditEvent,
    ensure_at_least_one,
    new_event,
    sanitize_payload,
)


def test_whitelist_keeps_only_known_fields() -> None:
    clean, dropped = sanitize_payload(
        {
            "tokenCount": 12,
            "toolName": "calculator",
            "budgetStatus": "OK",
            "question": "secret question",
            "content": "secret content",
            "unknown": "x",
        }
    )
    assert clean == {"tokenCount": 12, "toolName": "calculator", "budgetStatus": "OK"}
    assert "unknown" in dropped
    assert set(clean) <= ALLOWED_PAYLOAD_KEYS


def test_whitelist_rejects_out_of_range_or_wrong_types() -> None:
    clean, dropped = sanitize_payload(
        {
            "tokenCount": -1,
            "round": 4,
            "topScore": 2.0,
            "confidence": True,
            "toolName": "rm_rf",
            "budgetStatus": "MAYBE",
            "errorCode": "not-a-code",
            "maskingVersion": "v1",
            "piiCategories": ["EMAIL", "EMAIL", "PHONE"],
            "cacheHit": "yes",
            "model": "offline-extractive",
        }
    )
    assert clean == {
        "piiCategories": ["EMAIL", "PHONE"],
        "model": "offline-extractive",
    }
    assert dropped


def test_unknown_pii_category_drops_the_list() -> None:
    clean, _ = sanitize_payload({"piiCategories": ["EMAIL", "ADDRESS"]})
    assert clean == {}


def test_mcp_tool_names_and_source_survive_the_whitelist() -> None:
    """MCP 名与来源必须留在审计里，否则治理信号在最关键的路径上无声消失。"""

    clean, dropped = sanitize_payload({"toolName": "mcp__dict__lookup", "toolSource": "mcp"})
    assert clean == {"toolName": "mcp__dict__lookup", "toolSource": "mcp"}
    assert dropped == ()

    for invalid in ("mcp__Dict__lookup", "mcp__dict", "mcp__dict__look up", f"mcp__{'d' * 33}__x", "dict__lookup"):
        clean, _ = sanitize_payload({"toolName": invalid})
        assert clean == {}, invalid

    clean, dropped = sanitize_payload({"toolSource": "plugin"})
    assert clean == {}
    assert dropped == ("toolSource",)


def test_target_user_id_survives_only_as_a_uuid() -> None:
    """S9：管理动作的 targetUserId 进白名单，但只接受 UUID 形态。"""

    clean, dropped = sanitize_payload({"targetUserId": "00000000-0000-0000-0000-000000000001"})
    assert clean == {"targetUserId": "00000000-0000-0000-0000-000000000001"}
    assert dropped == ()

    clean, dropped = sanitize_payload({"targetUserId": "not-a-uuid"})
    assert clean == {}
    assert dropped == ("targetUserId",)


def test_new_event_enforces_type_and_agent_name_patterns() -> None:
    event = new_event("AGENT_COMPLETED", "agent_service")
    assert event.eventType == "AGENT_COMPLETED"
    with pytest.raises(ValueError):
        new_event("agent_completed", "agent_service")
    with pytest.raises(ValueError):
        new_event("AGENT_COMPLETED", "agent service")


def test_event_payload_is_sanitized_on_normalize() -> None:
    event = AuditEvent(
        eventId=uuid.uuid4(),
        eventType="AGENT_COMPLETED",
        agentName="agent_service",
        severity="INFO",
        occurredAt=datetime.now(UTC),
        payloadRedacted={"tokenCount": 1, "question": "leak"},
    )
    assert event.normalized().payloadRedacted == {"tokenCount": 1}


def test_collector_caps_events_and_marks_truncation() -> None:
    collector = AuditCollector()
    for index in range(60):
        collector.add("AGENT_STEP", "agent_service", round=1, tokenCount=index)
    events = collector.events()
    assert len(events) == 50
    assert events[-1].eventType == "AUDIT_TRUNCATED"
    assert events[-1].severity == "WARN"


def test_collector_extend_sanitizes_events() -> None:
    collector = AuditCollector()
    collector.extend(
        [
            AuditEvent(
                eventId=uuid.uuid4(),
                eventType="AGENT_COMPLETED",
                agentName="agent_service",
                severity="INFO",
                occurredAt=datetime.now(UTC),
                payloadRedacted={"tokenCount": 1, "prompt": "leak"},
            )
        ]
    )
    assert collector.events()[0].payloadRedacted == {"tokenCount": 1}


def test_ensure_at_least_one_adds_failure_event() -> None:
    collector = AuditCollector()
    ensure_at_least_one(collector, "AGT-UPSTREAM-001")
    events = collector.events()
    assert len(events) == 1
    assert events[0].eventType == "AGENT_FAILED"
    assert events[0].severity == "ERROR"
    assert events[0].payloadRedacted == {"errorCode": "AGT-UPSTREAM-001"}

    collector.add("AGENT_COMPLETED", "agent_service")
    ensure_at_least_one(collector, "AGT-UPSTREAM-001")
    assert len(collector.events()) == 2
