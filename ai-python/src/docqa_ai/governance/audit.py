from __future__ import annotations

import re
import uuid
from collections.abc import Callable
from dataclasses import dataclass, field
from datetime import UTC, datetime

from pydantic import BaseModel, ConfigDict, Field

MAX_EVENTS = 50
_MAX_STRING = 256
_MAX_LIST = 10
TOOL_SOURCES = frozenset({"builtin", "mcp"})
BUDGET_STATUSES = frozenset({"OK", "BUDGET_EXHAUSTED", "LOW_CONFIDENCE"})
PII_CATEGORIES = frozenset({"PHONE", "ID_CARD", "EMAIL"})
_TOOL_NAME = re.compile(
    r"^(document_search|calculator|current_time|web_fetch|web_search|academic_search|wikipedia_lookup"
    r"|mcp__[a-z0-9_]{1,32}__[a-z0-9_]{1,32})$"
)
_ERROR_CODE = re.compile(r"^[A-Z]{2,4}-[A-Z_]+-[0-9]{3}$")
_USER_ID = re.compile(r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
_MASKING_VERSION = re.compile(r"^pii-v[0-9]{1,6}$")
_MODEL = re.compile(r"^(?=.*[A-Za-z])[A-Za-z0-9._:/-]{1,64}$")
_EVENT_TYPE = re.compile(r"^[A-Z][A-Z0-9_]{0,63}$")
_AGENT_NAME = re.compile(r"^[A-Za-z][A-Za-z0-9_-]{0,63}$")

ALLOWED_PAYLOAD_KEYS = frozenset(
    {
        "durationMs",
        "tokenCount",
        "toolName",
        "toolSource",
        "chunkCount",
        "topScore",
        "round",
        "confidence",
        "budgetStatus",
        "errorCode",
        "maskingVersion",
        "piiCategories",
        "piiCount",
        "model",
        "cacheHit",
        "historyTruncated",
        "targetUserId",
    }
)


class AuditEvent(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    eventId: uuid.UUID
    eventType: str = Field(pattern=r"^[A-Z][A-Z0-9_]{0,63}$")
    agentName: str = Field(pattern=r"^[A-Za-z][A-Za-z0-9_-]{0,63}$")
    severity: str = Field(pattern=r"^(INFO|WARN|ERROR)$")
    occurredAt: datetime
    payloadRedacted: dict[str, object] = Field(default_factory=dict)

    def normalized(self) -> AuditEvent:
        return self.model_copy(update={"payloadRedacted": sanitize_payload(self.payloadRedacted)[0]})


def _is_int(value: object) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)


def sanitize_payload(payload: dict[str, object] | None) -> tuple[dict[str, object], tuple[str, ...]]:
    """Return the whitelisted payload and the names of dropped fields. Values are never logged."""

    if not payload:
        return {}, ()
    clean: dict[str, object] = {}
    dropped: list[str] = []
    for key, value in payload.items():
        if key not in ALLOWED_PAYLOAD_KEYS:
            dropped.append("unknown")
            continue
        sanitized = _sanitize_value(key, value)
        if sanitized is None:
            dropped.append(key)
        else:
            clean[key] = sanitized
    return clean, tuple(dict.fromkeys(dropped))


def _sanitize_value(key: str, value: object) -> object | None:
    if key in {"durationMs", "tokenCount", "chunkCount", "piiCount"}:
        return value if _is_int(value) and 0 <= value <= 2_147_483_647 else None
    if key == "round":
        return value if _is_int(value) and 1 <= value <= 3 else None
    if key in {"topScore", "confidence"}:
        if isinstance(value, bool) or not isinstance(value, (int, float)):
            return None
        as_float = float(value)
        return as_float if 0.0 <= as_float <= 1.0 else None
    if key == "toolName":
        return value if isinstance(value, str) and _TOOL_NAME.fullmatch(value) else None
    if key == "toolSource":
        return value if isinstance(value, str) and value in TOOL_SOURCES else None
    if key == "targetUserId":
        return value if isinstance(value, str) and _USER_ID.fullmatch(value) else None
    if key == "budgetStatus":
        return value if isinstance(value, str) and value in BUDGET_STATUSES else None
    if key == "errorCode":
        return value if isinstance(value, str) and _ERROR_CODE.fullmatch(value) else None
    if key == "maskingVersion":
        return value if isinstance(value, str) and _MASKING_VERSION.fullmatch(value) else None
    if key == "model":
        return value if isinstance(value, str) and _MODEL.fullmatch(value) else None
    if key == "piiCategories":
        if not isinstance(value, list) or len(value) > _MAX_LIST:
            return None
        categories: list[str] = []
        for item in value:
            if not isinstance(item, str) or item not in PII_CATEGORIES:
                return None
            if item not in categories:
                categories.append(item)
        return categories
    if key in {"cacheHit", "historyTruncated"}:
        return value if isinstance(value, bool) else None
    return None


def new_event(
    event_type: str,
    agent_name: str,
    severity: str = "INFO",
    occurred_at: datetime | None = None,
    **payload: object,
) -> AuditEvent:
    if not _EVENT_TYPE.fullmatch(event_type):
        raise ValueError("invalid event type")
    if not _AGENT_NAME.fullmatch(agent_name):
        raise ValueError("invalid agent name")
    clean, _ = sanitize_payload(payload)
    return AuditEvent(
        eventId=uuid.uuid4(),
        eventType=event_type,
        agentName=agent_name,
        severity=severity,
        occurredAt=occurred_at or datetime.now(UTC),
        payloadRedacted=clean,
    )


@dataclass
class AuditCollector:
    """Collects at most 50 whitelisted audit events for the response."""

    clock: Callable[[], datetime] = lambda: datetime.now(UTC)
    _events: list[AuditEvent] = field(default_factory=list)

    def add(
        self,
        event_type: str,
        agent_name: str,
        severity: str = "INFO",
        **payload: object,
    ) -> AuditEvent:
        if len(self._events) >= MAX_EVENTS:
            self._events[-1] = new_event(
                "AUDIT_TRUNCATED",
                "audit_collector",
                severity="WARN",
                occurred_at=self.clock(),
                piiCount=0,
            )
            return self._events[-1]
        event = new_event(event_type, agent_name, severity, self.clock(), **payload)
        self._events.append(event)
        return event

    def extend(self, events: list[AuditEvent]) -> None:
        for event in events:
            if len(self._events) >= MAX_EVENTS:
                return
            self._events.append(event.normalized())

    def events(self) -> list[AuditEvent]:
        return list(self._events)

    def __len__(self) -> int:
        return len(self._events)


def ensure_at_least_one(collector: AuditCollector, error_code: str) -> None:
    if not collector.events():
        collector.add("AGENT_FAILED", "agent_service", severity="ERROR", errorCode=error_code)
