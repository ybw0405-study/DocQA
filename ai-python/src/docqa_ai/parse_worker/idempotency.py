from __future__ import annotations

import asyncio
from enum import Enum
from typing import Protocol

from redis.asyncio import Redis
from redis.exceptions import RedisError

PROCESSING = "processing"
DONE = "done"
IDEMPOTENCY_PREFIX = "mq:idem:parse-requested:"
VECTOR_IDEMPOTENCY_PREFIX = "mq:idem:vector-change-requested:"


class IdempotencyUnavailable(Exception):
    """Redis is required for safe consumption; the message should be redelivered."""


class BeginResult(Enum):
    ACQUIRED = "ACQUIRED"
    DONE = "DONE"
    BUSY = "BUSY"


class IdempotencyStore(Protocol):
    async def begin(self, message_id: str) -> BeginResult: ...

    async def complete(self, message_id: str) -> None: ...

    async def release(self, message_id: str) -> None: ...


class RedisIdempotencyStore:
    """Keys mirror the Java consumer: ``mq:idem:parse-requested:{messageId}``."""

    def __init__(
        self,
        redis: Redis,
        *,
        processing_ttl_s: int = 900,
        done_ttl_s: int = 604800,
        prefix: str = IDEMPOTENCY_PREFIX,
    ) -> None:
        self._redis = redis
        self._processing_ttl = processing_ttl_s
        self._done_ttl = done_ttl_s
        self._prefix = prefix

    def _key(self, message_id: str) -> str:
        return f"{self._prefix}{message_id}"

    async def begin(self, message_id: str) -> BeginResult:
        key = self._key(message_id)
        try:
            acquired = await self._redis.set(key, PROCESSING, nx=True, ex=self._processing_ttl)
            if acquired:
                return BeginResult.ACQUIRED
            current = await self._redis.get(key)
        except RedisError as exc:
            raise IdempotencyUnavailable("redis idempotency read failed") from exc
        if isinstance(current, bytes):
            current = current.decode("utf-8", "ignore")
        return BeginResult.DONE if current == DONE else BeginResult.BUSY

    async def complete(self, message_id: str) -> None:
        try:
            await self._redis.set(self._key(message_id), DONE, ex=self._done_ttl)
        except RedisError as exc:
            raise IdempotencyUnavailable("redis idempotency write failed") from exc

    async def release(self, message_id: str) -> None:
        try:
            await self._redis.delete(self._key(message_id))
        except RedisError:
            return


class InMemoryIdempotencyStore:
    """Test double with the same state machine, no TTL."""

    def __init__(self) -> None:
        self._states: dict[str, str] = {}
        self._lock = asyncio.Lock()

    async def begin(self, message_id: str) -> BeginResult:
        async with self._lock:
            state = self._states.get(message_id)
            if state == DONE:
                return BeginResult.DONE
            if state == PROCESSING:
                return BeginResult.BUSY
            self._states[message_id] = PROCESSING
            return BeginResult.ACQUIRED

    async def complete(self, message_id: str) -> None:
        async with self._lock:
            self._states[message_id] = DONE

    async def release(self, message_id: str) -> None:
        async with self._lock:
            self._states.pop(message_id, None)
