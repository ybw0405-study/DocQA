from __future__ import annotations

import uuid
from datetime import datetime
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

ERROR_CODE_PATTERN = r"^[A-Z]{2,4}-[A-Z_]+-[0-9]{3}$"
SHA256_PATTERN = r"^[a-f0-9]{64}$"
MD5_PATTERN = r"^[a-f0-9]{32}$"


class _EventModel(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True, populate_by_name=True)


def require_tenant_id(value: object | None) -> str:
    """J1/J2：跨进程信封缺 tenantId 立即拒收，绝不静默降级为空串。

    兼容期允许字段缺省（滚动发布时在途的旧消息仍能被解析并明确报错、进入死信/重试），
    但任何写路径都必须先过这里——写侧恒写空串正是 004 迁移导致全量召回失效的根因。
    """
    if value is None:
        raise ValueError("tenantId is required in the cross-service event envelope")
    tenant = str(value)
    if not tenant.strip():
        raise ValueError("tenantId must not be empty")
    return tenant


class ParseRequestedEvent(_EventModel):
    event_id: uuid.UUID = Field(alias="eventId")
    trace_id: str = Field(alias="traceId", pattern=r"^[0-9a-f]{32}$")
    document_id: uuid.UUID = Field(alias="documentId")
    user_id: uuid.UUID = Field(alias="userId")
    # 租户信封：Java 控制面随每条 parse.requested 下发；缺省仅用于兼容在途旧消息，
    # 写入边界由 require_tenant_id() 兜底拒绝，不允许落任何无租户的行。
    tenant_id: str | None = Field(default=None, alias="tenantId", min_length=1, max_length=64)
    index_version: str = Field(alias="indexVersion", min_length=1, max_length=32)
    object_key: str = Field(alias="objectKey", min_length=1, max_length=512)
    file_name: str = Field(alias="fileName", min_length=1, max_length=255)
    content_type: str = Field(alias="contentType", min_length=1, max_length=128)
    size_bytes: int = Field(alias="sizeBytes", ge=0)
    md5: str = Field(pattern=MD5_PATTERN)
    retry_count: int = Field(alias="retryCount", ge=0, le=1000)
    occurred_at: datetime = Field(alias="occurredAt")

    @field_validator("object_key")
    @classmethod
    def validate_object_key(cls, value: str) -> str:
        if value.startswith("/") or "\\" in value:
            raise ValueError("objectKey must be a relative object key")
        if any(part in {"", ".", ".."} for part in value.split("/")):
            raise ValueError("objectKey must not contain empty or traversal segments")
        return value

    @field_validator("index_version")
    @classmethod
    def validate_index_version(cls, value: str) -> str:
        if not value.replace("-", "").replace("_", "").isalnum():
            raise ValueError("indexVersion contains unsupported characters")
        return value


class ParsedChunk(_EventModel):
    chunk_index: int = Field(alias="chunkIndex", ge=0)
    vector_id: uuid.UUID = Field(alias="vectorId")
    content_hash: str = Field(alias="contentHash", pattern=SHA256_PATTERN)
    token_count: int = Field(alias="tokenCount", ge=0)


class ParseCompletedEvent(_EventModel):
    event_id: uuid.UUID = Field(alias="eventId")
    trace_id: str = Field(alias="traceId", pattern=r"^[0-9a-f]{32}$")
    document_id: uuid.UUID = Field(alias="documentId")
    index_version: str = Field(alias="indexVersion", min_length=1, max_length=32)
    status: Literal["COMPLETED"] = "COMPLETED"
    chunks: list[ParsedChunk] = Field(min_length=1)
    vector_rows: int | None = Field(default=None, alias="vectorRows", ge=0)
    occurred_at: datetime = Field(alias="occurredAt")

    @model_validator(mode="after")
    def validate_chunks(self) -> ParseCompletedEvent:
        indexes = [chunk.chunk_index for chunk in self.chunks]
        if len(set(indexes)) != len(indexes):
            raise ValueError("chunk indexes must be unique")
        vector_ids = [chunk.vector_id for chunk in self.chunks]
        if len(set(vector_ids)) != len(vector_ids):
            raise ValueError("vector ids must be unique")
        return self


class ParseFailedEvent(_EventModel):
    event_id: uuid.UUID = Field(alias="eventId")
    trace_id: str = Field(alias="traceId", pattern=r"^[0-9a-f]{32}$")
    document_id: uuid.UUID = Field(alias="documentId")
    index_version: str = Field(alias="indexVersion", min_length=1, max_length=32)
    error_code: str = Field(alias="errorCode", pattern=ERROR_CODE_PATTERN)
    error_message: str = Field(alias="errorMessage", min_length=1, max_length=200)
    retryable: bool
    occurred_at: datetime = Field(alias="occurredAt")


class VectorChangeRequestedEvent(_EventModel):
    """``vector-change-requested``.

    ``operation`` stays a plain string and ``indexVersion`` has no lower bound on purpose: an
    out-of-scope request must still be answered with a FAILED confirmation, which requires the
    envelope to parse. The worker decides the scope and never widens a delete.
    """

    event_id: uuid.UUID = Field(alias="eventId")
    trace_id: str = Field(alias="traceId", pattern=r"^[0-9a-f]{32}$")
    document_id: uuid.UUID = Field(alias="documentId")
    user_id: uuid.UUID = Field(alias="userId")
    # 删除同样必须绑定到租户范围：没有 tenantId 时宁可拒收，也不能跨租户清空向量。
    tenant_id: str | None = Field(default=None, alias="tenantId", min_length=1, max_length=64)
    operation: str = Field(min_length=1, max_length=32)
    index_version: str | None = Field(default=None, alias="indexVersion", max_length=32)
    occurred_at: datetime = Field(alias="occurredAt")


class VectorChangeCompletedEvent(_EventModel):
    """``vector-change-completed``: the vector side reports what is left after one scoped delete."""

    event_id: uuid.UUID = Field(alias="eventId")
    request_event_id: uuid.UUID = Field(alias="requestEventId")
    trace_id: str = Field(alias="traceId", pattern=r"^[0-9a-f]{32}$")
    document_id: uuid.UUID = Field(alias="documentId")
    operation: str = Field(min_length=1, max_length=32)
    index_version: str | None = Field(default=None, alias="indexVersion")
    status: Literal["COMPLETED", "FAILED"]
    remaining_rows: int | None = Field(default=None, alias="remainingRows", ge=0)
    error_code: str | None = Field(default=None, alias="errorCode", pattern=ERROR_CODE_PATTERN)
    error_message: str | None = Field(default=None, alias="errorMessage", min_length=1, max_length=200)
    occurred_at: datetime = Field(alias="occurredAt")

    @model_validator(mode="after")
    def validate_status_payload(self) -> VectorChangeCompletedEvent:
        if self.status == "COMPLETED":
            if self.remaining_rows is None:
                raise ValueError("COMPLETED requires remainingRows")
            if self.error_code is not None or self.error_message is not None:
                raise ValueError("COMPLETED must not carry error fields")
            return self
        if self.error_code is None or self.error_message is None:
            raise ValueError("FAILED requires errorCode and errorMessage")
        if self.remaining_rows is not None:
            raise ValueError("FAILED must not report remainingRows")
        return self
