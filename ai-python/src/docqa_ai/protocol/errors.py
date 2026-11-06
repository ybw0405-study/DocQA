from __future__ import annotations

from collections.abc import Sequence

from docqa_ai.governance.audit import AuditEvent
from docqa_ai.protocol.models import FieldError, ProblemDetail

PROBLEM_PROTOCOL = "https://docqa.example/problems/agent-protocol"
PROBLEM_VALIDATION = "https://docqa.example/problems/agent-validation"
PROBLEM_UPSTREAM = "https://docqa.example/problems/agent-upstream"
PROBLEM_INTERNAL = "https://docqa.example/problems/agent-internal"

AGT_UPSTREAM_001 = "AGT-UPSTREAM-001"
AGT_UPSTREAM_002 = "AGT-UPSTREAM-002"
AGT_UPSTREAM_003 = "AGT-UPSTREAM-003"
AGT_UPSTREAM_004 = "AGT-UPSTREAM-004"
AGT_VALIDATION_003 = "AGT-VALIDATION-003"
AGT_INTERNAL_001 = "AGT-INTERNAL-001"


class AgentError(Exception):
    def __init__(
        self,
        *,
        code: str,
        status: int,
        title: str,
        detail: str,
        problem_type: str,
        errors: Sequence[FieldError] = (),
        audit_events: Sequence[AuditEvent] = (),
        trace_id: str | None = None,
        instance: str = "/agent/ask",
    ) -> None:
        super().__init__(detail)
        self.code = code
        self.status = status
        self.title = title
        self.detail = detail
        self.problem_type = problem_type
        self.errors = tuple(errors)
        self.audit_events = tuple(audit_events)
        self.trace_id = trace_id
        self.instance = instance

    def to_problem(self, trace_id: str | None = None) -> ProblemDetail:
        return ProblemDetail(
            type=self.problem_type,
            title=self.title,
            status=self.status,
            detail=self.detail,
            instance=self.instance,
            code=self.code,
            traceId=trace_id or self.trace_id,
            errors=list(self.errors),
            auditEvents=list(self.audit_events),
        )


def protocol_violation(
    detail: str,
    *,
    errors: Sequence[FieldError] = (),
    audit_events: Sequence[AuditEvent] = (),
    trace_id: str | None = None,
    instance: str = "/agent/ask",
) -> AgentError:
    return AgentError(
        code=AGT_UPSTREAM_004,
        status=422,
        title="Agent protocol violation",
        detail=detail,
        problem_type=PROBLEM_PROTOCOL,
        errors=errors,
        audit_events=audit_events,
        trace_id=trace_id,
        instance=instance,
    )


def pii_blocked(*, audit_events: Sequence[AuditEvent] = (), trace_id: str | None = None) -> AgentError:
    return AgentError(
        code=AGT_VALIDATION_003,
        status=422,
        title="Sensitive data blocked",
        detail="Sensitive information was detected. Remove it and retry.",
        problem_type=PROBLEM_VALIDATION,
        audit_events=audit_events,
        trace_id=trace_id,
    )


def deadline_exceeded(
    *,
    audit_events: Sequence[AuditEvent] = (),
    trace_id: str | None = None,
    instance: str = "/agent/ask",
) -> AgentError:
    return AgentError(
        code=AGT_UPSTREAM_002,
        status=504,
        title="Agent deadline exceeded",
        detail="The agent did not finish before the deadline.",
        problem_type=PROBLEM_UPSTREAM,
        audit_events=audit_events,
        trace_id=trace_id,
        instance=instance,
    )


def upstream_unavailable(
    detail: str = "The AI service is temporarily unavailable.",
    *,
    audit_events: Sequence[AuditEvent] = (),
    trace_id: str | None = None,
) -> AgentError:
    return AgentError(
        code=AGT_UPSTREAM_001,
        status=503,
        title="Agent upstream unavailable",
        detail=detail,
        problem_type=PROBLEM_UPSTREAM,
        audit_events=audit_events,
        trace_id=trace_id,
    )


def kill_switch_open(*, audit_events: Sequence[AuditEvent] = (), trace_id: str | None = None) -> AgentError:
    return AgentError(
        code=AGT_UPSTREAM_003,
        status=503,
        title="AI kill switch is open",
        detail="AI features are temporarily disabled by the operator.",
        problem_type=PROBLEM_UPSTREAM,
        audit_events=audit_events,
        trace_id=trace_id,
    )


def internal_error(*, audit_events: Sequence[AuditEvent] = (), trace_id: str | None = None) -> AgentError:
    return AgentError(
        code=AGT_INTERNAL_001,
        status=500,
        title="Agent internal error",
        detail="The agent failed to produce a governed answer.",
        problem_type=PROBLEM_INTERNAL,
        audit_events=audit_events,
        trace_id=trace_id,
    )
