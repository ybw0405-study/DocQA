"""方案 4.3：OTel 跨进程传播骨架 + 结构化日志统一脱敏的回归用例。"""
from __future__ import annotations

import json
import logging
from io import StringIO

from opentelemetry import trace

from docqa_ai.logging_setup import (
    JsonFormatter,
    is_sensitive_field,
    log_event,
    redact_structure,
)
from docqa_ai.tracing import build_tracing, context_from_headers, propagation_headers


def test_propagation_headers_roundtrip_carries_the_trace_id() -> None:
    tracer, _ = build_tracing(object())
    with tracer.start_as_current_span("mq-publish") as span:
        headers = propagation_headers()
    assert "traceparent" in headers
    extracted = trace.get_current_span(context_from_headers(headers)).get_span_context()
    assert extracted.trace_id == span.get_span_context().trace_id


def test_context_from_headers_without_traceparent_is_not_a_valid_span() -> None:
    assert not trace.get_current_span(context_from_headers({})).get_span_context().is_valid


def test_sensitive_key_variants_are_normalized() -> None:
    for key in ("apiKey", "api_key", "API-KEY", "Authorization", "pass word", "secret"):
        assert is_sensitive_field(key)


def test_log_event_drops_sensitive_key_variants() -> None:
    stream = StringIO()
    handler = logging.StreamHandler(stream)
    handler.setFormatter(JsonFormatter("docqa-ai"))
    logger = logging.getLogger("docqa_ai.observability_hardening_test")
    logger.handlers = [handler]
    logger.setLevel(logging.INFO)
    logger.propagate = False
    log_event(
        logger,
        logging.INFO,
        "publish mq envelope",
        step="mq",
        api_key="sk-secret",
        API_KEY="sk-secret",
        authorization="Bearer x",
    )
    payload = json.loads(stream.getvalue().strip())
    assert payload["step"] == "mq"
    assert "sk-secret" not in stream.getvalue()
    assert "api_key" not in payload and "API_KEY" not in payload


def test_redact_structure_masks_nested_pii_and_drops_sensitive_keys() -> None:
    value = {
        "question": "联系 13800138000 或 a@b.com",
        "apiKey": "sk-secret",
        "nested": {"password": "hunter2", "ok": 7},
        "items": ["手机号 13800138000"],
    }
    result = redact_structure(value)
    dumped = json.dumps(result, ensure_ascii=False)
    assert "13800138000" not in dumped
    assert "a@b.com" not in dumped
    assert "sk-secret" not in dumped
    assert "hunter2" not in dumped
    assert result["nested"]["ok"] == 7


def test_redact_structure_does_not_mutate_the_input() -> None:
    original = {"apiKey": "keep", "items": ["13800138000"]}
    redact_structure(original)
    assert original["apiKey"] == "keep"
    assert original["items"] == ["13800138000"]
