from __future__ import annotations

import logging
import time
from typing import Protocol

from redis.asyncio import Redis
from redis.exceptions import RedisError

from docqa_ai.logging_setup import log_event

LOGGER = logging.getLogger(__name__)


class KillSwitch(Protocol):
    async def is_active(self) -> bool: ...


class RedisKillSwitch:
    """Reads the same agent:kill-switch key as Java; a short cache keeps it independent of the graph."""

    def __init__(self, redis: Redis, key: str = "agent:kill-switch", cache_ms: int = 5000) -> None:
        self._redis = redis
        self._key = key
        self._cache_seconds = cache_ms / 1000.0
        self._cached_value = False
        self._cached_until = 0.0
        self._clock = time.monotonic

    async def is_active(self) -> bool:
        now = self._clock()
        if now < self._cached_until:
            return self._cached_value
        try:
            value = await self._redis.get(self._key)
        except RedisError:
            log_event(LOGGER, logging.WARNING, "Kill switch lookup failed; AI calls remain enabled", step="kill_switch")
            self._cached_value = False
        else:
            if isinstance(value, bytes):
                text = value.decode("utf-8", "ignore")
            else:
                text = "" if value is None else str(value)
            self._cached_value = text.strip().lower() in {"1", "true", "on", "yes"}
        self._cached_until = now + self._cache_seconds
        return self._cached_value


class StaticKillSwitch:
    def __init__(self, active: bool) -> None:
        self._active = active

    async def is_active(self) -> bool:
        return self._active
