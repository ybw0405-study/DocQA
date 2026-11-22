from __future__ import annotations

import uuid
from datetime import datetime
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from docqa_ai.governance.audit import AuditEvent

# D-123 / D-139：内置七名 + `mcp__<server>__<tool>`；协议版本仍是 1.0——新值只由新配置产生。
TOOL_NAME_PATTERN = (
    r"^(document_search|calculator|current_time|web_fetch|web_search|academic_search|wikipedia_lookup"
    r"|mcp__[a-z0-9_]{1,32}__[a-z0-9_]{1,32})$"
)
ToolName = Annotated[str, Field(pattern=TOOL_NAME_PATTERN)]
BudgetStatus = Literal["OK", "BUDGET_EXHAUSTED", "LOW_CONFIDENCE"]
HistoryRole = Literal["USER", "ASSISTANT"]

PROTOCOL_VERSION = "1.0"
# S8-3 / D-177：流式端点 `POST /agent/ask/stream` 的请求侧准入版本（纯新增操作）。
# 响应信封的 protocolVersion 仍回写 PROTOCOL_VERSION——网关 PythonResponseValidator 对同步与
# 流式两条路径都硬校验响应为 "1.0"，且「旧字段不动」要求旧端点行为逐字节不变。
STREAM_PROTOCOL_VERSION = "1.1"
SUPPORTED_MASKING_VERSIONS = frozenset({"pii-v1", "pii-v2"})


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True, populate_by_name=True)


class DocumentScope(StrictModel):
    document_id: uuid.UUID = Field(alias="documentId")
    index_version: str = Field(alias="indexVersion", min_length=1, max_length=32)


class HistoryMessage(StrictModel):
    role: HistoryRole
    pii_masked_content: str = Field(alias="piiMaskedContent", min_length=1, max_length=4000)


class AgentAskRequest(StrictModel):
    protocol_version: str = Field(alias="protocolVersion", pattern=r"^[0-9]+\.[0-9]+$")
    tenant_id: uuid.UUID = Field(alias="tenantId")
    user_id: uuid.UUID = Field(alias="userId")
    trace_id: str = Field(alias="traceId", pattern=r"^[0-9a-f]{32}$")
    conversation_id: uuid.UUID = Field(alias="conversationId")
    question: str = Field(min_length=1, max_length=4000)
    pii_masked_question: str = Field(alias="piiMaskedQuestion", min_length=1, max_length=4000)
    pii_masking_version: str = Field(alias="piiMaskingVersion", pattern=r"^pii-v[0-9]+$")
    token_budget: int = Field(alias="tokenBudget", ge=1, le=8000)
    max_tool_calls: int = Field(alias="maxToolCalls", ge=0, le=3)
    max_rounds: int = Field(alias="maxRounds", ge=1, le=3)
    allowed_tools: list[ToolName] = Field(alias="allowedTools", max_length=10)
    deadline_ms: int = Field(alias="deadlineMs", ge=100, le=15000)
    document_scopes: list[DocumentScope] = Field(alias="documentScopes", min_length=1, max_length=20)
    history: list[HistoryMessage] = Field(default_factory=list, max_length=6)

    @field_validator("protocol_version")
    @classmethod
    def validate_protocol_version(cls, value: str) -> str:
        if value.split(".", 1)[0] != "1":
            raise ValueError("unsupported protocol major version")
        return value

    @field_validator("pii_masking_version")
    @classmethod
    def validate_masking_version(cls, value: str) -> str:
        if value not in SUPPORTED_MASKING_VERSIONS:
            raise ValueError("unsupported pii masking version")
        return value

    @field_validator("question", "pii_masked_question")
    @classmethod
    def validate_text_not_blank(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("must not be blank")
        return value

    @model_validator(mode="after")
    def validate_unique_scopes(self) -> AgentAskRequest:
        ids = [scope.document_id for scope in self.document_scopes]
        if len(set(ids)) != len(ids):
            raise ValueError("documentScopes must not contain duplicate documentId")
        return self

    def deduplicated_tools(self) -> tuple[ToolName, ...]:
        return tuple(dict.fromkeys(self.allowed_tools))


class Citation(StrictModel):
    document_id: uuid.UUID = Field(alias="documentId")
    chunk_index: int = Field(alias="chunkIndex", ge=0)
    score: float = Field(ge=0.0, le=1.0)
    snippet: str = Field(max_length=200)


class AgentAskResponse(StrictModel):
    protocol_version: str = Field(alias="protocolVersion", pattern=r"^[0-9]+\.[0-9]+$")
    trace_id: str = Field(alias="traceId", pattern=r"^[0-9a-f]{32}$")
    answer: str = Field(min_length=1, max_length=8000)
    citations: list[Citation] = Field(default_factory=list, max_length=20)
    confidence: float = Field(ge=0.0, le=1.0)
    budget_status: BudgetStatus = Field(alias="budgetStatus")
    token_used: int = Field(alias="tokenUsed", ge=0, le=8000)
    tool_calls_made: int = Field(alias="toolCallsMade", ge=0, le=3)
    rounds_used: int = Field(alias="roundsUsed", ge=0, le=3)
    audit_events: list[AuditEvent] = Field(alias="auditEvents", min_length=1, max_length=50)
    finished_at: datetime = Field(alias="finishedAt")


class FieldError(BaseModel):
    model_config = ConfigDict(extra="forbid")

    field: str = Field(min_length=1, max_length=200)
    message: str = Field(min_length=1, max_length=200)


class ProblemDetail(BaseModel):
    model_config = ConfigDict(extra="forbid")

    type: str
    title: str
    status: int
    detail: str
    instance: str = "/agent/ask"
    code: str
    traceId: str | None = None
    errors: list[FieldError] = Field(default_factory=list)
    auditEvents: list[AuditEvent] = Field(default_factory=list)

    def as_dict(self) -> dict[str, object]:
        return self.model_dump(mode="json", by_alias=True)
