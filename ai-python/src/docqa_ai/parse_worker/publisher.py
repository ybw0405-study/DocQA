from __future__ import annotations

import asyncio
from typing import Any, Protocol

from docqa_ai.parse_worker.errors import PublishError
from docqa_ai.parse_worker.models import (
    ParseCompletedEvent,
    ParseFailedEvent,
    VectorChangeCompletedEvent,
)
from docqa_ai.tracing import current_traceparent


class ParseEventPublisher(Protocol):
    async def publish_completed(self, event: ParseCompletedEvent) -> None: ...

    async def publish_failed(self, event: ParseFailedEvent) -> None: ...


class VectorChangePublisher(Protocol):
    async def publish_vector_change_completed(self, event: VectorChangeCompletedEvent) -> None: ...


class InMemoryParsePublisher:
    """Test double that records published events in order."""

    def __init__(self) -> None:
        self.completed: list[ParseCompletedEvent] = []
        self.failed: list[ParseFailedEvent] = []

    async def publish_completed(self, event: ParseCompletedEvent) -> None:
        self.completed.append(event)

    async def publish_failed(self, event: ParseFailedEvent) -> None:
        self.failed.append(event)


class InMemoryVectorChangePublisher:
    """Test double for the vector-change ack stream."""

    def __init__(self) -> None:
        self.completed: list[VectorChangeCompletedEvent] = []

    async def publish_vector_change_completed(self, event: VectorChangeCompletedEvent) -> None:
        self.completed.append(event)


class RocketMqEventProducer:
    """One producer, one send convention: documentId as key plus trace properties."""

    def __init__(self, namesrv: str, producer_group: str, *, timeout_ms: int = 3000) -> None:
        from rocketmq.client import Producer # optional extra; imported lazily

        self._producer = Producer(producer_group)
        self._producer.set_name_server_address(namesrv)
        self._producer.set_timeout(timeout_ms)
        self._producer.start()

    async def publish(self, topic: str, body: str, key: str, trace_id: str) -> None:
        try:
            await asyncio.to_thread(self._send, topic, body, key, trace_id)
        except PublishError:
            raise
        except Exception as exc: # noqa: BLE001 - client/C++ errors are mapped to retry semantics
            raise PublishError("event publish failed") from exc

    def _send(self, topic: str, body: str, key: str, trace_id: str) -> None:
        from rocketmq.client import Message

        message = Message(topic)
        message.set_keys(key)
        message.set_body(body)
        message.set_property("traceId", trace_id)
        traceparent = current_traceparent()
        if traceparent:
            message.set_property("traceparent", traceparent)
        result: Any = self._producer.send_sync(message)
        status = getattr(result, "status", None)
        if status is not None and str(status) not in {"SEND_OK", "0", "SendStatus.SEND_OK"}:
            raise PublishError(f"unexpected send status: {status}")

    def shutdown(self) -> None:
        self._producer.shutdown()


class RocketMqParsePublisher:
    """Publishes parse results with the same keys/properties as the Java producer."""

    def __init__(
        self,
        namesrv: str,
        producer_group: str,
        *,
        completed_topic: str = "parse-completed",
        failed_topic: str = "parse-failed",
        timeout_ms: int = 3000,
    ) -> None:
        self._completed_topic = completed_topic
        self._failed_topic = failed_topic
        self._producer = RocketMqEventProducer(namesrv, producer_group, timeout_ms=timeout_ms)

    async def publish_completed(self, event: ParseCompletedEvent) -> None:
        await self._producer.publish(
            self._completed_topic,
            event.model_dump_json(by_alias=True),
            str(event.document_id),
            event.trace_id,
        )

    async def publish_failed(self, event: ParseFailedEvent) -> None:
        await self._producer.publish(
            self._failed_topic,
            event.model_dump_json(by_alias=True),
            str(event.document_id),
            event.trace_id,
        )

    def shutdown(self) -> None:
        self._producer.shutdown()


class RocketMqVectorChangePublisher:
    """Publishes vector-change confirmations back to ``document-service``."""

    def __init__(
        self,
        namesrv: str,
        producer_group: str,
        *,
        completed_topic: str = "vector-change-completed",
        timeout_ms: int = 3000,
    ) -> None:
        self._completed_topic = completed_topic
        self._producer = RocketMqEventProducer(namesrv, producer_group, timeout_ms=timeout_ms)

    async def publish_vector_change_completed(self, event: VectorChangeCompletedEvent) -> None:
        await self._producer.publish(
            self._completed_topic,
            event.model_dump_json(by_alias=True),
            str(event.document_id),
            event.trace_id,
        )

    def shutdown(self) -> None:
        self._producer.shutdown()
