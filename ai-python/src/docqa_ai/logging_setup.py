from __future__ import annotations

import json
import logging
import sys
from datetime import UTC, datetime
from typing import Any

from opentelemetry import trace

from docqa_ai.pii import mask_text

_ALLOWED_FIELDS = frozenset(
    {
        "traceId",
        "spanId",
        "userId",
        "conversationId",
        "agentName",
        "step",
        "latencyMs",
        "tokenIn",
        "tokenOut",
        "cacheHit",
        "budgetStatus",
        "errorCode",
        "eventId",
        "documentId",
        "chunkCount",
        "operation",
        "status",
        "remainingRows",
        "httpStatus",
        "provider",
        "model",
        "toolName",
        "source",
        "traceContinuation",
    }
)

_BLOCKED_FIELDS = frozenset(
    {
        "question",
        "answer",
        "prompt",
        "content",
        "snippet",
        "payload",
        "raw",
        "apiKey",
        "authorization",
        "password",
        "secret",
    }
)


def _otel_ids() -> tuple[str | None, str | None]:
    context = trace.get_current_span().get_span_context()
    if not context.is_valid:
        return None, None
    return f"{context.trace_id:032x}", f"{context.span_id:016x}"


class JsonFormatter(logging.Formatter):
    """Structured logs with an allow-list; message text is PII-masked as a safety net."""

    def __init__(self, service_name: str) -> None:
        super().__init__()
        self._service_name = service_name

    def format(self, record: logging.LogRecord) -> str:
        otel_trace_id, otel_span_id = _otel_ids()
        payload: dict[str, Any] = {
            "ts": datetime.now(UTC).isoformat().replace("+00:00", "Z"),
            "level": record.levelname,
            "service": self._service_name,
            "logger": record.name,
            "message": mask_text(record.getMessage()).masked,
            "traceId": getattr(record, "traceId", None) or otel_trace_id,
            "spanId": getattr(record, "spanId", None) or otel_span_id,
        }
        for key in _ALLOWED_FIELDS:
            if key in ("traceId", "spanId"):
                continue
            value = getattr(record, key, None)
            if value is not None:
                payload[key] = self._safe_value(value)
        if record.exc_info and record.exc_info[0] is not None:
            payload["errorType"] = record.exc_info[0].__name__
        return json.dumps({key: value for key, value in payload.items() if value is not None}, ensure_ascii=False)

    @staticmethod
    def _safe_value(value: Any) -> Any:
        if isinstance(value, str):
            return mask_text(value).masked
        if isinstance(value, (int, float, bool)):
            return value
        return mask_text(str(value)).masked


def configure_logging(level: str, service_name: str) -> None:
    handler = logging.StreamHandler(sys.stdout)
    handler.setFormatter(JsonFormatter(service_name))
    root = logging.getLogger()
    root.handlers.clear()
    root.addHandler(handler)
    root.setLevel(level.upper())

    for noisy in ("uvicorn.access", "httpx", "httpcore", "opentelemetry.exporter", "minio", "urllib3"):
        logging.getLogger(noisy).setLevel(logging.WARNING)


def log_event(logger: logging.Logger, level: int, message: str, **fields: Any) -> None:
    clean: dict[str, Any] = {}
    for key, value in fields.items():
        if key in _BLOCKED_FIELDS:
            continue
        if key in _ALLOWED_FIELDS and value is not None:
            clean[key] = value
    logger.log(level, message, extra=clean)
