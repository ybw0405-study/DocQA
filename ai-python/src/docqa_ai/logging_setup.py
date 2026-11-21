from __future__ import annotations

import json
import logging
import sys
from collections.abc import Mapping
from datetime import UTC, datetime
from typing import Any

from opentelemetry import trace

from docqa_ai.pii import mask_text

_ALLOWED_FIELDS = frozenset(
    {
        "traceId",
        "spanId",
        "tenantId",
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


def _normalize_key(key: str) -> str:
    """把 apiKey / api_key / API-KEY 收敛成同一判据，避免命名变体绕过黑名单（方案 4.3）。"""
    return "".join(ch for ch in key.lower() if ch.isalnum())


_NORMALIZED_BLOCKED = frozenset(_normalize_key(key) for key in _BLOCKED_FIELDS)


def is_sensitive_field(key: str) -> bool:
    return _normalize_key(key) in _NORMALIZED_BLOCKED


def redact_structure(value: Any, *, _depth: int = 0) -> Any:
    """方案 4.3：结构化日志统一脱敏入口，供审计事件 / 异常上下文 / MQ 载荷复用。

    字符串一律过 mask_text；映射里命中敏感键的整条丢弃；其余结构递归，深度上限
    防止循环引用或超深结构把日志线程拖住。返回的是新对象，绝不改写入参。
    """
    if _depth > 8:
        return "[redacted]"
    if isinstance(value, str):
        return mask_text(value).masked
    if isinstance(value, Mapping):
        return {
            key: redact_structure(item, _depth=_depth + 1)
            for key, item in value.items()
            if not is_sensitive_field(str(key))
        }
    if isinstance(value, (list, tuple)):
        return [redact_structure(item, _depth=_depth + 1) for item in value]
    if value is None or isinstance(value, (int, float, bool)):
        return value
    return mask_text(str(value)).masked


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
        # 统一走 redact_structure：字符串、嵌套映射、列表里的 PII 与敏感键一并收敛，
        # 行为对原有标量字段逐字节不变（str / 数字 / bool / None 的分支相同）。
        return redact_structure(value)


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
        if is_sensitive_field(key):
            continue
        if key in _ALLOWED_FIELDS and value is not None:
            clean[key] = value
    logger.log(level, message, extra=clean)
