from __future__ import annotations

import asyncio
import hashlib
import logging
from typing import Any, Protocol

from docqa_ai.logging_setup import log_event
from docqa_ai.parse_worker.worker import HandleOutcome

LOGGER = logging.getLogger(__name__)


class MessageHandler(Protocol):
    """Anything the broker adapter can drive: both workers share this shape."""

    async def handle(
        self,
        message_id: str,
        body: bytes,
        *,
        traceparent: str | None = None,
    ) -> HandleOutcome: ...


def _message_id(message: Any, body: bytes) -> str:
    for attribute in ("msg_id", "id"):
        value = getattr(message, attribute, None)
        if value:
            return str(value)
    # ``keys`` is the documentId in the Java producer, not a per-message identifier.
    return hashlib.sha256(body).hexdigest()


def _property(message: Any, name: str) -> str | None:
    getter = getattr(message, "get_property", None)
    if callable(getter):
        try:
            value = getter(name)
        except Exception: # noqa: BLE001 - optional client binding
            value = None
        if value is not None:
            return str(value)
    properties = getattr(message, "properties", None)
    if isinstance(properties, dict):
        value = properties.get(name)
        if value is not None:
            return str(value)
    return None


class WorkerPushConsumer:
    """Bridges the RocketMQ client callbacks into the worker's asyncio loop."""

    def __init__(
        self,
        worker: MessageHandler,
        *,
        namesrv: str,
        consumer_group: str,
        topic: str,
        loop: asyncio.AbstractEventLoop,
        handle_timeout_s: float = 300.0,
        step: str,
    ) -> None:
        self._worker = worker
        self._namesrv = namesrv
        self._consumer_group = consumer_group
        self._topic = topic
        self._loop = loop
        self._handle_timeout_s = handle_timeout_s
        self._step = step
        self._consumer: Any = None
        self._status: Any = None

    def start(self) -> None:
        from rocketmq.client import ConsumeStatus, PushConsumer # optional extra

        self._status = ConsumeStatus
        consumer = PushConsumer(self._consumer_group)
        consumer.set_name_server_address(self._namesrv)
        consumer.subscribe(self._topic, self._on_message, "*")
        consumer.start()
        self._consumer = consumer
        log_event(
            LOGGER,
            logging.INFO,
            "Worker consumer started",
            step=self._step,
            topic=self._topic,
            consumerGroup=self._consumer_group,
        )

    def shutdown(self) -> None:
        if self._consumer is not None:
            self._consumer.shutdown()
            self._consumer = None

    def _on_message(self, message: Any) -> object:
        body = bytes(getattr(message, "body", b""))
        message_id = _message_id(message, body)
        traceparent = _property(message, "traceparent")
        try:
            future = asyncio.run_coroutine_threadsafe(
                self._worker.handle(message_id, body, traceparent=traceparent),
                self._loop,
            )
            outcome = future.result(timeout=self._handle_timeout_s)
        except Exception: # noqa: BLE001 - any failure must return the message to the broker
            log_event(LOGGER, logging.ERROR, "Message handling failed", step=self._step)
            return self._status.RECONSUME_LATER
        if outcome is HandleOutcome.RETRY:
            return self._status.RECONSUME_LATER
        return self._status.CONSUME_SUCCESS


class ParseRequestedConsumer(WorkerPushConsumer):
    def __init__(
        self,
        worker: MessageHandler,
        *,
        namesrv: str,
        consumer_group: str,
        topic: str,
        loop: asyncio.AbstractEventLoop,
        handle_timeout_s: float = 300.0,
    ) -> None:
        super().__init__(
            worker,
            namesrv=namesrv,
            consumer_group=consumer_group,
            topic=topic,
            loop=loop,
            handle_timeout_s=handle_timeout_s,
            step="parse",
        )


class VectorChangeRequestedConsumer(WorkerPushConsumer):
    def __init__(
        self,
        worker: MessageHandler,
        *,
        namesrv: str,
        consumer_group: str,
        topic: str,
        loop: asyncio.AbstractEventLoop,
        handle_timeout_s: float = 60.0,
    ) -> None:
        super().__init__(
            worker,
            namesrv=namesrv,
            consumer_group=consumer_group,
            topic=topic,
            loop=loop,
            handle_timeout_s=handle_timeout_s,
            step="vector-change",
        )
