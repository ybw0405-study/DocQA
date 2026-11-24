from __future__ import annotations

import asyncio
import uuid
from collections.abc import Callable
from datetime import UTC, datetime
from typing import Protocol

from redis.asyncio import Redis
from redis.exceptions import RedisError

KEY_PREFIX = "agent:tool:rl:"
WINDOW_SECONDS = 90
DEFAULT_LIMIT = 20


class ToolRateLimitUnavailable(Exception):
    """The limiter itself is unreachable; callers must fail closed."""


class ToolRateLimiter(Protocol):
    async def allow(self, user_id: str | uuid.UUID) -> bool: ...


def _epoch_minute(now: datetime) -> int:
    return int(now.timestamp() // 60)


def _utc_now() -> datetime:
    return datetime.now(UTC)


def _key(user_id: str | uuid.UUID, now: datetime) -> str:
    return f"{KEY_PREFIX}{user_id}:{_epoch_minute(now)}"


class RedisToolRateLimiter:
    """fixed-window limiter: one INCR + EXPIRE per call, 20 calls per user-minute.

    The key embeds the epoch minute, so a stale key can never be read by the next window;
    the 90s TTL only keeps abandoned keys from accumulating.
    """

    def __init__(
        self,
        redis: Redis,
        limit: int = DEFAULT_LIMIT,
        *,
        clock: Callable[[], datetime] = _utc_now,
        window_seconds: int = WINDOW_SECONDS,
    ) -> None:
        if limit < 1:
            raise ValueError("tool rate limit must be positive")
        if window_seconds < 1:
            raise ValueError("tool rate limit window must be positive")
        self._redis = redis
        self._limit = limit
        self._clock = clock
        self._window_seconds = window_seconds

    async def allow(self, user_id: str | uuid.UUID) -> bool:
        key = _key(user_id, self._clock())
        try:
            pipe = self._redis.pipeline()
            pipe.incr(key)
            pipe.expire(key, self._window_seconds)
            count, _ = await pipe.execute()
        except RedisError as exc:
            raise ToolRateLimitUnavailable("redis tool rate limit failed") from exc
        return int(count) <= self._limit


class InMemoryToolRateLimiter:
    """Deterministic limiter for tests; never used by production wiring."""

    def __init__(
        self,
        limit: int = DEFAULT_LIMIT,
        *,
        clock: Callable[[], datetime] = _utc_now,
    ) -> None:
        if limit < 1:
            raise ValueError("tool rate limit must be positive")
        self._limit = limit
        self._clock = clock
        self._counts: dict[tuple[str, int], int] = {}
        self._lock = asyncio.Lock()

    async def allow(self, user_id: str | uuid.UUID) -> bool:
        key = str(user_id)
        minute = _epoch_minute(self._clock())
        async with self._lock:
            bucket = (key, minute)
            self._counts[bucket] = self._counts.get(bucket, 0) + 1
            return self._counts[bucket] <= self._limit
