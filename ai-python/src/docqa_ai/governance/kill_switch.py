from __future__ import annotations

import logging
import time
from typing import Protocol
from uuid import UUID

from redis.asyncio import Redis
from redis.exceptions import RedisError

from docqa_ai.logging_setup import log_event

LOGGER = logging.getLogger(__name__)


class KillSwitch(Protocol):
    async def is_active(self, tenant_id: UUID | None = None) -> bool: ...


class RedisKillSwitch:
    """Reads the same kill switch keys as Java: a global key plus an optional per-tenant key.

    The global key is the platform-wide circuit breaker; the per-tenant key isolates a single
    workspace. A short cache keeps lookups off the graph; both keys are checked with OR semantics
    so either switch can stop AI calls.
    """

    TENANT_KEY_PREFIX = "agent:kill-switch:tenant:"

    def __init__(self, redis: Redis, key: str = "agent:kill-switch", cache_ms: int = 5000) -> None:
        self._redis = redis
        self._key = key
        self._cache_seconds = cache_ms / 1000.0
        # Cache keyed by full redis key: global switch and each tenant switch cache independently.
        self._cache: dict[str, tuple[bool, float]] = {}
        self._clock = time.monotonic

    @classmethod
    def tenant_key(cls, tenant_id: UUID) -> str:
        return f"{cls.TENANT_KEY_PREFIX}{tenant_id}"

    async def is_active(self, tenant_id: UUID | None = None) -> bool:
        if await self._read(self._key):
            return True
        if tenant_id is None:
            return False
        return await self._read(self.tenant_key(tenant_id))

    async def _read(self, key: str) -> bool:
        now = self._clock()
        cached = self._cache.get(key)
        if cached is not None and now < cached[1]:
            return cached[0]
        try:
            value = await self._redis.get(key)
        except RedisError:
            log_event(
                LOGGER,
                logging.WARNING,
                "Kill switch lookup failed; AI calls remain enabled",
                step="kill_switch",
            )
            active = False
        else:
            if isinstance(value, bytes):
                text = value.decode("utf-8", "ignore")
            else:
                text = "" if value is None else str(value)
            active = text.strip().lower() in {"1", "true", "on", "yes"}
        self._cache[key] = (active, now + self._cache_seconds)
        return active


class StaticKillSwitch:
    def __init__(self, active: bool) -> None:
        self._active = active

    async def is_active(self, tenant_id: UUID | None = None) -> bool:
        return self._active
