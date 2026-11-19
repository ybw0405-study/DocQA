from __future__ import annotations

import json
import logging

from docqa_ai.logging_setup import JsonFormatter, log_event


def _format(**fields: object) -> dict[str, object]:
    record = logging.LogRecord(
        name="docqa_ai.parse_worker",
        level=logging.INFO,
        pathname=__file__,
        lineno=1,
        msg="Vector change confirmed to document-service",
        args=(),
        exc_info=None,
    )
    for key, value in fields.items():
        setattr(record, key, value)
    return json.loads(JsonFormatter("docqa-ai").format(record))


def test_vector_confirmation_outcome_reaches_the_log() -> None:
    """确认日志的价值全在 status/remainingRows：这三个字段被白名单漏掉时会静默消失。"""
    payload = _format(
        traceId="0" * 32,
        step="vector-change",
        documentId="deadbeef-0000-4000-8000-deadbeef0000",
        operation="DELETE_VERSION",
        status="COMPLETED",
        remainingRows=0,
    )
    assert payload["status"] == "COMPLETED"
    assert payload["operation"] == "DELETE_VERSION"
    assert payload["remainingRows"] == 0


def test_failed_confirmation_keeps_error_code_without_message() -> None:
    payload = _format(
        traceId="0" * 32,
        status="FAILED",
        errorCode="COM-VALIDATION-001",
        errorMessage="驱动抛出的连接串 jdbc:postgresql://secret-host/db?password=hunter2",
    )
    assert payload["errorCode"] == "COM-VALIDATION-001"
    assert "errorMessage" not in payload


def test_blocked_fields_are_dropped_before_formatting() -> None:
    payload = _format(
        traceId="0" * 32,
        question="我的手机号是 13800138000",
        content="整段文档正文",
        password="hunter2",
    )
    assert set(payload) == {"ts", "level", "service", "logger", "message", "traceId"}


def test_tenant_id_reaches_the_log() -> None:
    """P1 可观测：租户标签必须穿过日志白名单，否则跨租户问题无法定位。"""
    tenant = "44444444-4444-4444-8444-444444444444"
    payload = _format(traceId="0" * 32, tenantId=tenant)
    assert payload["tenantId"] == tenant


def test_allowed_string_fields_are_pii_masked() -> None:
    payload = _format(operation="DELETE_VERSION 13800138000 a@b.com")
    masked = str(payload["operation"])
    assert "13800138000" not in masked
    assert "a@b.com" not in masked


def test_log_event_only_forwards_allowlisted_keys(caplog) -> None:
    with caplog.at_level(logging.INFO, logger="docqa_ai.test"):
        log_event(
            logging.getLogger("docqa_ai.test"),
            logging.INFO,
            "Vector change confirmed to document-service",
            status="COMPLETED",
            question="不该出现",
        )
    record = caplog.records[-1]
    assert record.status == "COMPLETED"
    assert not hasattr(record, "question")
