from __future__ import annotations

import logging
import uuid
from collections.abc import Callable
from datetime import UTC, datetime

from opentelemetry import trace
from opentelemetry.propagate import extract
from opentelemetry.trace import Status, StatusCode, Tracer

from docqa_ai.logging_setup import log_event
from docqa_ai.parse_worker.errors import INTERNAL, PublishError, VectorChangeError
from docqa_ai.parse_worker.idempotency import (
    BeginResult,
    IdempotencyStore,
    IdempotencyUnavailable,
)
from docqa_ai.parse_worker.models import (
    VectorChangeCompletedEvent,
    VectorChangeRequestedEvent,
)
from docqa_ai.parse_worker.publisher import VectorChangePublisher
from docqa_ai.parse_worker.vector_writer import VectorWriteError, VectorWriter
from docqa_ai.parse_worker.worker import HandleOutcome
from docqa_ai.tracing import parse_traceparent

LOGGER = logging.getLogger(__name__)

DELETE_DOCUMENT = "DELETE_DOCUMENT"
DELETE_VERSION = "DELETE_VERSION"
_VALID_OPERATIONS = frozenset({DELETE_DOCUMENT, DELETE_VERSION})
_VALIDATION_FAILED = "COM-VALIDATION-001"
_DELETE_FAILED = "向量删除失败，请稍后重试。"
_MISSING_SCOPE = "向量删除请求缺少索引版本作用域，已拒绝执行。"
_UNKNOWN_OPERATION = "向量删除请求的操作类型不受支持，已拒绝执行。"
_MISMATCHED_SCOPE = "DELETE_DOCUMENT 不允许携带索引版本，已拒绝执行。"


class VectorChangeWorker:
    """Deletes one PGVector scope per message and confirms the remaining row count to Java.

    The delete predicate is ``documentId`` plus, for ``DELETE_VERSION``, ``indexVersion``. ``userId``
    is never a predicate (protocol section 5.7), and a missing scope is rejected instead of being
    widened to the whole document.
    """

    def __init__(
        self,
        *,
        writer: VectorWriter,
        publisher: VectorChangePublisher,
        idempotency: IdempotencyStore,
        tracer: Tracer | None = None,
        clock: Callable[[], datetime] | None = None,
    ) -> None:
        self._writer = writer
        self._publisher = publisher
        self._idempotency = idempotency
        self._tracer = tracer or trace.get_tracer("docqa_ai")
        self._clock = clock or (lambda: datetime.now(UTC))

    async def handle(
        self,
        message_id: str,
        body: bytes,
        *,
        traceparent: str | None = None,
    ) -> HandleOutcome:
        parent = parse_traceparent(traceparent)
        parent_context = extract({"traceparent": traceparent}) if parent else None
        with self._tracer.start_as_current_span("document.vector_change", context=parent_context) as span:
            span.set_attribute("trace_continuation", parent is not None)
            try:
                begin = await self._idempotency.begin(message_id)
            except IdempotencyUnavailable:
                log_event(
                    LOGGER,
                    logging.WARNING,
                    "Vector worker cannot acquire idempotency key",
                    step="vector-change",
                )
                return HandleOutcome.RETRY
            if begin is BeginResult.DONE:
                return HandleOutcome.DUPLICATE
            if begin is BeginResult.BUSY:
                return HandleOutcome.RETRY

            try:
                event = VectorChangeRequestedEvent.model_validate_json(body)
            except ValueError:
                span.set_attribute("vector_change.status", "INVALID")
                log_event(
                    LOGGER,
                    logging.WARNING,
                    "Ignoring invalid vector-change-requested event",
                    step="vector-change",
                )
                await self._finish(message_id)
                return HandleOutcome.INVALID

            span.set_attribute("document.id", str(event.document_id))
            span.set_attribute("vector.operation", event.operation)
            span.set_attribute("document.index_version", event.index_version or "")
            try:
                confirmation = await self._run(event)
            except VectorChangeError as exc:
                span.set_attribute("vector_change.status", "FAILED")
                span.set_attribute("error.code", exc.code)
                log_event(
                    LOGGER,
                    logging.WARNING,
                    "Vector change did not complete",
                    step="vector-change",
                    traceId=event.trace_id,
                    documentId=str(event.document_id),
                    operation=event.operation,
                    errorCode=exc.code,
                )
                confirmation = self._failed(event, exc)
            try:
                await self._publisher.publish_vector_change_completed(confirmation)
            except PublishError:
                span.set_attribute("vector_change.status", "RETRY")
                span.set_status(Status(StatusCode.ERROR, "publish-failed"))
                await self._release(message_id)
                return HandleOutcome.RETRY
            except Exception:  # noqa: BLE001 - return the message to the broker without leaking details
                span.set_attribute("vector_change.status", "RETRY")
                span.set_status(Status(StatusCode.ERROR, "unexpected"))
                log_event(
                    LOGGER,
                    logging.ERROR,
                    "Vector worker failed unexpectedly",
                    step="vector-change",
                    traceId=event.trace_id,
                    documentId=str(event.document_id),
                    errorCode=INTERNAL,
                )
                await self._release(message_id)
                return HandleOutcome.RETRY

            span.set_attribute("vector_change.remaining_rows", confirmation.remaining_rows)
            log_event(
                LOGGER,
                logging.INFO,
                "Vector change confirmed to document-service",
                step="vector-change",
                traceId=event.trace_id,
                documentId=str(event.document_id),
                operation=event.operation,
                status=confirmation.status,
                remainingRows=confirmation.remaining_rows,
            )
            await self._finish(message_id)
            return HandleOutcome.PROCESSED

    async def _run(self, event: VectorChangeRequestedEvent) -> VectorChangeCompletedEvent:
        scope = self._scope(event)
        try:
            remaining_rows = await self._writer.delete(event.document_id, scope)
        except VectorWriteError as exc:
            raise VectorChangeError(INTERNAL, _DELETE_FAILED) from exc
        return self._completed(event, remaining_rows)

    def _scope(self, event: VectorChangeRequestedEvent) -> str | None:
        if event.operation == DELETE_VERSION:
            if not event.index_version or not event.index_version.strip():
                raise VectorChangeError(_VALIDATION_FAILED, _MISSING_SCOPE)
            return event.index_version
        if event.operation == DELETE_DOCUMENT:
            if event.index_version is not None:
                raise VectorChangeError(_VALIDATION_FAILED, _MISMATCHED_SCOPE)
            return None
        raise VectorChangeError(_VALIDATION_FAILED, _UNKNOWN_OPERATION)

    def _completed(self, event: VectorChangeRequestedEvent, remaining_rows: int) -> VectorChangeCompletedEvent:
        return VectorChangeCompletedEvent(
            event_id=uuid.uuid4(),
            request_event_id=event.event_id,
            trace_id=event.trace_id,
            document_id=event.document_id,
            operation=event.operation,
            index_version=event.index_version,
            status="COMPLETED",
            remaining_rows=remaining_rows,
            occurred_at=self._clock(),
        )

    def _failed(self, event: VectorChangeRequestedEvent, exc: VectorChangeError) -> VectorChangeCompletedEvent:
        return VectorChangeCompletedEvent(
            event_id=uuid.uuid4(),
            request_event_id=event.event_id,
            trace_id=event.trace_id,
            document_id=event.document_id,
            operation=event.operation,
            index_version=event.index_version,
            status="FAILED",
            error_code=exc.code,
            error_message=exc.message,
            occurred_at=self._clock(),
        )

    async def _finish(self, message_id: str) -> None:
        try:
            await self._idempotency.complete(message_id)
        except IdempotencyUnavailable:
            log_event(LOGGER, logging.WARNING, "Unable to mark vector change as done", step="vector-change")

    async def _release(self, message_id: str) -> None:
        await self._idempotency.release(message_id)


def main() -> int:
    import asyncio
    import signal
    import sys

    from redis.asyncio import Redis

    from docqa_ai.config import Settings
    from docqa_ai.logging_setup import configure_logging
    from docqa_ai.parse_worker.idempotency import (
        VECTOR_IDEMPOTENCY_PREFIX,
        RedisIdempotencyStore,
    )
    from docqa_ai.parse_worker.mq import VectorChangeRequestedConsumer
    from docqa_ai.parse_worker.publisher import RocketMqVectorChangePublisher
    from docqa_ai.parse_worker.vector_writer import PgVectorWriter
    from docqa_ai.tracing import build_tracing

    settings = Settings()
    configure_logging(settings.log_level, settings.service_name)
    if not settings.mq_enabled:
        print("vector worker requires DOCQA_AI_MQ_ENABLED=true", file=sys.stderr)
        return 2
    if not settings.pg_dsn:
        print("vector worker requires DOCQA_AI_PG_DSN", file=sys.stderr)
        return 2

    tracer, provider = build_tracing(settings)
    LOGGER.info("Vector worker starting: %s", settings.redacted_summary())
    redis_client = Redis.from_url(
        settings.redis_url,
        socket_timeout=settings.redis_socket_timeout_ms / 1000.0,
        socket_connect_timeout=settings.redis_socket_timeout_ms / 1000.0,
        decode_responses=True,
    )
    publisher = RocketMqVectorChangePublisher(
        settings.rocketmq_namesrv,
        settings.vector_producer_group,
        completed_topic=settings.vector_completed_topic,
        timeout_ms=settings.mq_send_timeout_ms,
    )
    worker = VectorChangeWorker(
        writer=PgVectorWriter(
            settings.pg_dsn,
            settings.pg_table,
            dimensions=settings.embedding_dimensions,
            connect_timeout_s=settings.pg_connect_timeout_s,
        ),
        publisher=publisher,
        idempotency=RedisIdempotencyStore(
            redis_client,
            processing_ttl_s=settings.vector_processing_ttl_seconds,
            done_ttl_s=settings.mq_idempotency_ttl_seconds,
            prefix=VECTOR_IDEMPOTENCY_PREFIX,
        ),
        tracer=tracer,
    )

    loop = asyncio.new_event_loop()
    asyncio.set_event_loop(loop)
    consumer = VectorChangeRequestedConsumer(
        worker,
        namesrv=settings.rocketmq_namesrv,
        consumer_group=settings.vector_consumer_group,
        topic=settings.vector_requested_topic,
        loop=loop,
    )

    def stop(*_: object) -> None:
        loop.call_soon_threadsafe(loop.stop)

    for sig in (signal.SIGINT, signal.SIGTERM):
        try:
            signal.signal(sig, stop)
        except ValueError:
            pass

    try:
        consumer.start()
        loop.run_forever()
    except KeyboardInterrupt:
        pass
    finally:
        consumer.shutdown()
        publisher.shutdown()
        loop.run_until_complete(redis_client.aclose())
        loop.close()
        provider.shutdown()
    return 0
