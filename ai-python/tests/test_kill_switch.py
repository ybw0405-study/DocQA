from __future__ import annotations

import pytest
from fakeredis.aioredis import FakeRedis
from redis.exceptions import RedisError

from docqa_ai.governance.kill_switch import RedisKillSwitch, StaticKillSwitch

KEY = "agent:kill-switch"


async def test_switch_reads_java_string_values() -> None:
    redis = FakeRedis(decode_responses=False)
    switch = RedisKillSwitch(redis, KEY, cache_ms=0)
    assert await switch.is_active() is False
    for active in (b"1", b"true", b"TRUE", b"on", b"yes"):
        await redis.set(KEY, active)
        assert await switch.is_active() is True
    for inactive in (b"0", b"false", b"", b"off"):
        await redis.set(KEY, inactive)
        assert await switch.is_active() is False
    await redis.aclose()


async def test_switch_cache_avoids_reading_redis_on_every_call() -> None:
    redis = FakeRedis(decode_responses=False)
    switch = RedisKillSwitch(redis, KEY, cache_ms=5000)
    now = [0.0]
    switch._clock = lambda: now[0]
    await redis.set(KEY, b"1")
    assert await switch.is_active() is True
    await redis.set(KEY, b"0")
    assert await switch.is_active() is True
    now[0] = 6.0
    assert await switch.is_active() is False
    await redis.aclose()


async def test_switch_fails_open_when_redis_is_unavailable() -> None:
    class FailingRedis:
        async def get(self, key: str) -> bytes:
            raise RedisError("down")

    switch = RedisKillSwitch(FailingRedis(), KEY, cache_ms=0)  # type: ignore[arg-type]
    assert await switch.is_active() is False


async def test_static_switch_is_deterministic() -> None:
    assert await StaticKillSwitch(True).is_active() is True
    assert await StaticKillSwitch(False).is_active() is False


@pytest.mark.parametrize("value", [b"yes", b"on", b"1", b"true"])
async def test_truthy_variants(value: bytes) -> None:
    redis = FakeRedis(decode_responses=False)
    await redis.set(KEY, value)
    switch = RedisKillSwitch(redis, KEY, cache_ms=0)
    assert await switch.is_active() is True
    await redis.aclose()
