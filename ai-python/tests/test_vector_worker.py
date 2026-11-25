from __future__ import annotations

import json
import uuid
from datetime import UTC, datetime

import pytest
from pydantic import ValidationError

from docqa_ai.config import Settings
from docqa_ai.parse_worker.errors import PublishError
from docqa_ai.parse_worker.idempotency import (
    VECTOR_IDEMPOTENCY_PREFIX,
    BeginResult,
    InMemoryIdempotencyStore,
    RedisIdempotencyStore,
)
from docqa_ai.parse_worker.models import VectorChangeCompletedEvent, VectorChangeRequestedEvent
from docqa_ai.parse_worker.publisher import InMemoryVectorChangePublisher
from docqa_ai.parse_worker.vector_worker import VectorChangeWorker
from docqa_ai.parse_worker.vector_writer import VectorWriteError
from docqa_ai.parse_worker.worker import HandleOutcome
from tests.conftest import DOCUMENT_ID, TENANT_ID, TRACE_ID

USER_ID = uuid.UUID("11111111-1111-4111-8111-111111111111")
EVENT_ID = uuid.UUID("44444444-4444-4444-8444-444444444444")


def requested(**overrides: object) -> dict[str, object]:
    payload: dict[str, object] = {
        "eventId": str(EVENT_ID),
        "traceId": TRACE_ID,
        "documentId": str(DOCUMENT_ID),
        "userId": str(USER_ID),
        "tenantId": str(TENANT_ID),
        "operation": "DELETE_DOCUMENT",
        "indexVersion": None,
        "occurredAt": datetime.now(UTC).isoformat(),
    }
    payload.update(overrides)
    return payload


def body(**overrides: object) -> bytes:
    return json.dumps(requested(**overrides)).encode()


class RecordingWriter:
    def __init__(self, remaining: int = 0) -> None:
        self.scopes: list[tuple[uuid.UUID, str | None]] = []
        self.remaining = remaining

    async def delete(
        self,
        document_id: uuid.UUID,
        index_version: str | None,
        tenant_id: object | None = None,
    ) -> int:
        self.scopes.append((document_id, index_version))
        return self.remaining


class FailingWriter(RecordingWriter):
    async def delete(
        self,
        document_id: uuid.UUID,
        index_version: str | None,
        tenant_id: object | None = None,
    ) -> int:
        self.scopes.append((document_id, index_version))
        raise VectorWriteError("psycopg connection to secret-host failed: password=hunter2")


def make_worker(
    *,
    writer: object | None = None,
    publisher: InMemoryVectorChangePublisher | None = None,
    idempotency: object | None = None,
) -> VectorChangeWorker:
    return VectorChangeWorker(
        writer=writer or RecordingWriter(), # type: ignore[arg-type]
        publisher=publisher or InMemoryVectorChangePublisher(),
        idempotency=idempotency or InMemoryIdempotencyStore(), # type: ignore[arg-type]
    )


async def test_delete_document_removes_every_version_and_acks_zero() -> None:
    writer = RecordingWriter()
    publisher = InMemoryVectorChangePublisher()
    worker = make_worker(writer=writer, publisher=publisher)

    outcome = await worker.handle("msg-1", body())

    assert outcome is HandleOutcome.PROCESSED
    assert writer.scopes == [(DOCUMENT_ID, None)], "None scope is the whole-document delete"
    ack = publisher.completed[0]
    assert ack.status == "COMPLETED"
    assert ack.request_event_id == EVENT_ID
    assert ack.document_id == DOCUMENT_ID
    assert ack.operation == "DELETE_DOCUMENT"
    assert ack.index_version is None
    assert ack.remaining_rows == 0
    assert ack.trace_id == TRACE_ID

    payload = json.loads(ack.model_dump_json(by_alias=True))
    assert payload["requestEventId"] == str(EVENT_ID)
    assert payload["remainingRows"] == 0
    assert payload["errorCode"] is None
    assert "userId" not in payload, "the confirmation never carries the user identity"


async def test_delete_version_is_scoped_to_that_version_and_echoed_back() -> None:
    writer = RecordingWriter(remaining=3)
    publisher = InMemoryVectorChangePublisher()
    worker = make_worker(writer=writer, publisher=publisher)

    await worker.handle("msg-2", body(operation="DELETE_VERSION", indexVersion="v1"))

    assert writer.scopes == [(DOCUMENT_ID, "v1")]
    ack = publisher.completed[0]
    assert ack.index_version == "v1"
    assert ack.remaining_rows == 3, "leftover rows are reported as-is so Java can read DIVERGED"


@pytest.mark.parametrize("index_version", [None, "", " "], ids=["null", "empty", "blank"])
async def test_a_version_delete_without_scope_never_widens(index_version: str | None) -> None:
    writer = RecordingWriter()
    publisher = InMemoryVectorChangePublisher()
    worker = make_worker(writer=writer, publisher=publisher)

    outcome = await worker.handle(
        "msg-3",
        json.dumps(requested(operation="DELETE_VERSION", indexVersion=index_version)).encode(),
    )

    assert outcome is HandleOutcome.PROCESSED
    assert writer.scopes == [], "a missing scope must not fall back to deleting the whole document"
    ack = publisher.completed[0]
    assert ack.status == "FAILED"
    assert ack.error_code == "COM-VALIDATION-001"
    assert ack.remaining_rows is None


async def test_a_version_delete_without_the_key_is_rejected() -> None:
    writer = RecordingWriter()
    publisher = InMemoryVectorChangePublisher()
    worker = make_worker(writer=writer, publisher=publisher)
    payload = requested(operation="DELETE_VERSION")
    del payload["indexVersion"]

    outcome = await worker.handle("msg-3b", json.dumps(payload).encode())

    assert outcome is HandleOutcome.PROCESSED
    assert writer.scopes == []
    assert publisher.completed[0].status == "FAILED"


async def test_delete_document_with_a_version_is_rejected() -> None:
    writer = RecordingWriter()
    publisher = InMemoryVectorChangePublisher()
    worker = make_worker(writer=writer, publisher=publisher)

    outcome = await worker.handle("msg-4", body(indexVersion="v1"))

    assert outcome is HandleOutcome.PROCESSED
    assert writer.scopes == []
    ack = publisher.completed[0]
    assert ack.status == "FAILED"
    assert ack.error_code == "COM-VALIDATION-001"
    assert ack.index_version == "v1", "the requested scope is echoed back unchanged"


async def test_unknown_operation_is_rejected_without_touching_pg() -> None:
    writer = RecordingWriter()
    publisher = InMemoryVectorChangePublisher()
    worker = make_worker(writer=writer, publisher=publisher)

    await worker.handle("msg-5", body(operation="DROP_TABLE"))

    assert writer.scopes == []
    ack = publisher.completed[0]
    assert ack.status == "FAILED"
    assert ack.operation == "DROP_TABLE"


async def test_database_failure_acks_internal_error_without_leaking_details() -> None:
    writer = FailingWriter()
    publisher = InMemoryVectorChangePublisher()
    worker = make_worker(writer=writer, publisher=publisher)

    outcome = await worker.handle("msg-6", body())

    assert outcome is HandleOutcome.PROCESSED
    assert writer.scopes == [(DOCUMENT_ID, None)]
    payload = json.loads(publisher.completed[0].model_dump_json(by_alias=True))
    assert payload["status"] == "FAILED"
    assert payload["errorCode"] == "COM-INTERNAL-001"
    assert payload["errorMessage"] == "向量删除失败，请稍后重试。"
    assert "hunter2" not in payload["errorMessage"]
    assert "secret-host" not in json.dumps(payload, ensure_ascii=False)


async def test_publish_failure_redelivers_and_deletes_again_idempotently() -> None:
    class FlakyPublisher(InMemoryVectorChangePublisher):
        def __init__(self) -> None:
            super().__init__()
            self.failures = 1

        async def publish_vector_change_completed(self, event: VectorChangeCompletedEvent) -> None:
            if self.failures:
                self.failures -= 1
                raise PublishError("broker down")
            self.completed.append(event)

    writer = RecordingWriter()
    publisher = FlakyPublisher()
    worker = make_worker(writer=writer, publisher=publisher)

    assert await worker.handle("msg-7", body()) is HandleOutcome.RETRY
    assert publisher.completed == []
    assert await worker.handle("msg-7", body()) is HandleOutcome.PROCESSED

    assert len(publisher.completed) == 1
    assert writer.scopes == [(DOCUMENT_ID, None), (DOCUMENT_ID, None)], "re-deleting the same scope is harmless"


async def test_duplicate_message_does_not_delete_twice() -> None:
    writer = RecordingWriter()
    publisher = InMemoryVectorChangePublisher()
    worker = make_worker(writer=writer, publisher=publisher)

    assert await worker.handle("msg-8", body()) is HandleOutcome.PROCESSED
    assert await worker.handle("msg-8", body()) is HandleOutcome.DUPLICATE

    assert writer.scopes == [(DOCUMENT_ID, None)]
    assert len(publisher.completed) == 1


async def test_busy_message_returns_retry_without_deleting() -> None:
    writer = RecordingWriter()
    publisher = InMemoryVectorChangePublisher()
    idempotency = InMemoryIdempotencyStore()
    assert await idempotency.begin("msg-9") is BeginResult.ACQUIRED
    worker = make_worker(writer=writer, publisher=publisher, idempotency=idempotency)

    assert await worker.handle("msg-9", body()) is HandleOutcome.RETRY
    assert writer.scopes == []
    assert publisher.completed == []


async def test_malformed_event_is_acknowledged_without_deleting() -> None:
    writer = RecordingWriter()
    publisher = InMemoryVectorChangePublisher()
    idempotency = InMemoryIdempotencyStore()
    worker = make_worker(writer=writer, publisher=publisher, idempotency=idempotency)

    assert await worker.handle("msg-10", b"{not json") is HandleOutcome.INVALID
    assert writer.scopes == []
    assert publisher.completed == []
    assert await worker.handle("msg-10", body()) is HandleOutcome.DUPLICATE


async def test_unknown_field_is_rejected_by_the_forbid_policy() -> None:
    writer = RecordingWriter()
    worker = make_worker(writer=writer)
    payload = requested(**{"retryable": True})

    assert await worker.handle("msg-11", json.dumps(payload).encode()) is HandleOutcome.INVALID
    assert writer.scopes == []


async def test_the_vector_worker_uses_its_own_idempotency_namespace() -> None:
    from fakeredis.aioredis import FakeRedis

    redis = FakeRedis(decode_responses=True)
    store = RedisIdempotencyStore(redis, processing_ttl_s=120, done_ttl_s=604800, prefix=VECTOR_IDEMPOTENCY_PREFIX)
    worker = make_worker(idempotency=store)

    await worker.handle("msg-12", body())

    key = f"{VECTOR_IDEMPOTENCY_PREFIX}msg-12"
    assert key == "mq:idem:vector-change-requested:msg-12"
    assert await redis.get(key) == "done"
    assert await redis.get("mq:idem:parse-requested:msg-12") is None
    await redis.aclose()


def test_requested_model_rejects_a_missing_document_id() -> None:
    payload = requested()
    del payload["documentId"]
    with pytest.raises(ValidationError):
        VectorChangeRequestedEvent.model_validate(payload)


def test_confirmation_invariants_follow_the_protocol() -> None:
    base: dict[str, object] = {
        "eventId": uuid.uuid4(),
        "requestEventId": EVENT_ID,
        "traceId": TRACE_ID,
        "documentId": DOCUMENT_ID,
        "operation": "DELETE_DOCUMENT",
        "indexVersion": None,
        "occurredAt": datetime.now(UTC),
    }
    with pytest.raises(ValidationError):
        VectorChangeCompletedEvent.model_validate({**base, "status": "COMPLETED"})
    with pytest.raises(ValidationError):
        VectorChangeCompletedEvent.model_validate(
            {**base, "status": "FAILED", "errorCode": "COM-INTERNAL-001", "errorMessage": "x", "remainingRows": 0}
        )
    with pytest.raises(ValidationError):
        VectorChangeCompletedEvent.model_validate({**base, "status": "FAILED", "errorCode": "COM-INTERNAL-001"})
    completed = VectorChangeCompletedEvent.model_validate({**base, "status": "COMPLETED", "remainingRows": 0})
    assert completed.error_code is None


def test_vector_worker_names_match_the_protocol_registration() -> None:
    settings = Settings(_env_file=None)
    assert settings.vector_requested_topic == "vector-change-requested"
    assert settings.vector_completed_topic == "vector-change-completed"
    assert settings.vector_consumer_group == "ai-python-vector-worker"
    assert settings.vector_producer_group == "ai-python-vector-producer"
    for name in (settings.vector_requested_topic, settings.vector_completed_topic):
        assert "." not in name, "RocketMQ topic names reject the dotted logical names"
