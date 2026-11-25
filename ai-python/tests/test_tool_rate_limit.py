from __future__ import annotations

from datetime import UTC, datetime

import pytest
from fakeredis.aioredis import FakeRedis
from redis.exceptions import RedisError

from docqa_ai.governance.tool_rate_limit import (
    KEY_PREFIX,
    InMemoryToolRateLimiter,
    RedisToolRateLimiter,
    ToolRateLimitUnavailable,
)

NOW = datetime(2026, 11, 3, 10, 0, 15, tzinfo=UTC)


def minute_key(user_id: str, now: datetime = NOW) -> str:
    return f"{KEY_PREFIX}{user_id}:{int(now.timestamp() // 60)}"


async def test_redis_limiter_counts_per_user_minute_and_sets_the_ttl() -> None:
    redis = FakeRedis(decode_responses=True)
    limiter = RedisToolRateLimiter(redis, 2, clock=lambda: NOW)

    assert await limiter.allow("u1") is True
    assert await limiter.allow("u1") is True
    assert await limiter.allow("u1") is False

    assert await redis.get(minute_key("u1")) == "3"
    ttl = await redis.ttl(minute_key("u1"))
    assert 0 < ttl <= 90
    await redis.aclose()


async def test_redis_limiter_separates_users_and_minutes() -> None:
    now = [NOW]
    redis = FakeRedis(decode_responses=True)
    limiter = RedisToolRateLimiter(redis, 1, clock=lambda: now[0])

    assert await limiter.allow("u1") is True
    assert await limiter.allow("u1") is False
    assert await limiter.allow("u2") is True

    now[0] = NOW.replace(minute=1)
    assert await limiter.allow("u1") is True
    await redis.aclose()


async def test_redis_failure_is_fail_closed() -> None:
    class BrokenRedis:
        def pipeline(self) -> object:
            raise RedisError("redis is down")

    limiter = RedisToolRateLimiter(BrokenRedis(), 20, clock=lambda: NOW) # type: ignore[arg-type]
    with pytest.raises(ToolRateLimitUnavailable):
        await limiter.allow("u1")


def test_limit_must_be_positive() -> None:
    with pytest.raises(ValueError):
        RedisToolRateLimiter(FakeRedis(), 0)
    with pytest.raises(ValueError):
        InMemoryToolRateLimiter(0)


async def test_in_memory_limiter_matches_the_redis_window() -> None:
    now = [NOW]
    limiter = InMemoryToolRateLimiter(2, clock=lambda: now[0])
    assert await limiter.allow("u1") is True
    assert await limiter.allow("u1") is True
    assert await limiter.allow("u1") is False

    now[0] = NOW.replace(minute=1)
    assert await limiter.allow("u1") is True
