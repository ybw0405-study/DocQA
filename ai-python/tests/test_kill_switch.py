from __future__ import annotations

from uuid import UUID

import pytest
from fakeredis.aioredis import FakeRedis
from redis.exceptions import RedisError

from docqa_ai.governance.kill_switch import RedisKillSwitch, StaticKillSwitch

KEY = "agent:kill-switch"
TENANT_A = UUID("11111111-1111-4111-8111-111111111111")
TENANT_B = UUID("22222222-2222-4222-8222-222222222222")


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


async def test_tenant_key_is_namespaced_by_tenant_id() -> None:
    assert RedisKillSwitch.tenant_key(TENANT_A) == (f"agent:kill-switch:tenant:{TENANT_A}")
    assert RedisKillSwitch.tenant_key(TENANT_A) != RedisKillSwitch.tenant_key(TENANT_B)


async def test_tenant_switch_stops_only_its_own_tenant() -> None:
    redis = FakeRedis(decode_responses=False)
    switch = RedisKillSwitch(redis, KEY, cache_ms=0)
    await redis.set(RedisKillSwitch.tenant_key(TENANT_A), b"1")

    assert await switch.is_active(TENANT_A) is True
    assert await switch.is_active(TENANT_B) is False
    assert await switch.is_active() is False
    await redis.aclose()


async def test_global_switch_stops_every_tenant() -> None:
    redis = FakeRedis(decode_responses=False)
    switch = RedisKillSwitch(redis, KEY, cache_ms=0)
    await redis.set(KEY, b"1")

    assert await switch.is_active(TENANT_A) is True
    assert await switch.is_active(TENANT_B) is True
    await redis.aclose()


async def test_tenant_and_global_keys_cache_independently() -> None:
    redis = FakeRedis(decode_responses=False)
    switch = RedisKillSwitch(redis, KEY, cache_ms=5000)
    now = [0.0]
    switch._clock = lambda: now[0]
    await redis.set(RedisKillSwitch.tenant_key(TENANT_A), b"1")

    assert await switch.is_active(TENANT_A) is True
    assert await switch.is_active(TENANT_B) is False
    now[0] = 6.0
    await redis.delete(RedisKillSwitch.tenant_key(TENANT_A))
    assert await switch.is_active(TENANT_A) is False
    await redis.aclose()
