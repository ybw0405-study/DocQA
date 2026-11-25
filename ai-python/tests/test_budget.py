from __future__ import annotations

import pytest
from fakeredis.aioredis import FakeRedis
from redis.exceptions import RedisError

from docqa_ai.governance.budget import (
    BudgetSnapshot,
    BudgetStateInvalid,
    BudgetUnavailable,
    Deadline,
    InMemoryBudgetStore,
    RedisBudgetStore,
)

TRACE = "a" * 32
KEY = f"agent:budget:{TRACE}"


def fields(**overrides: str) -> dict[str, str]:
    values = {
        "tokensUsed": "10",
        "toolCallsMade": "0",
        "roundsUsed": "1",
        "tokenBudget": "100",
        "maxToolCalls": "2",
        "maxRounds": "3",
        "deadlineAt": "2026-10-27T10:00:10Z",
    }
    values.update(overrides)
    return values


async def store_with(redis: FakeRedis, **overrides: str) -> RedisBudgetStore:
    await redis.hset(KEY, mapping=fields(**overrides))
    return RedisBudgetStore(redis)


async def test_snapshot_reads_hash_initialized_by_java() -> None:
    redis = FakeRedis(decode_responses=True)
    store = await store_with(redis)
    snapshot = await store.snapshot(TRACE)
    assert snapshot == BudgetSnapshot(
        tokens_used=10,
        tool_calls_made=0,
        rounds_used=1,
        token_budget=100,
        max_tool_calls=2,
        max_rounds=3,
        deadline_at="2026-10-27T10:00:10Z",
    )
    await redis.aclose()


async def test_missing_or_invalid_state_fails_closed() -> None:
    redis = FakeRedis(decode_responses=True)
    store = RedisBudgetStore(redis)
    with pytest.raises(BudgetStateInvalid):
        await store.snapshot(TRACE)
    await redis.hset(KEY, mapping=fields(tokensUsed="not-a-number"))
    with pytest.raises(BudgetStateInvalid):
        await store.snapshot(TRACE)
    await redis.hset(KEY, mapping=fields(tokensUsed="-1"))
    with pytest.raises(BudgetStateInvalid):
        await store.snapshot(TRACE)
    await redis.delete(KEY)
    await redis.hset(KEY, mapping={"tokensUsed": "1"})
    with pytest.raises(BudgetStateInvalid):
        await store.snapshot(TRACE)
    await redis.aclose()


async def test_round_and_tool_counters_are_capped_atomically() -> None:
    redis = FakeRedis(decode_responses=True)
    store = await store_with(redis, roundsUsed="2", maxRounds="3", toolCallsMade="1", maxToolCalls="2")
    assert await store.consume_round(TRACE) is True
    assert await store.consume_round(TRACE) is False
    assert await store.consume_tool(TRACE) is True
    assert await store.consume_tool(TRACE) is False
    snapshot = await store.snapshot(TRACE)
    assert snapshot.rounds_used == 3
    assert snapshot.tool_calls_made == 2
    await redis.aclose()


async def test_token_reservation_never_exceeds_budget() -> None:
    redis = FakeRedis(decode_responses=True)
    store = await store_with(redis, tokensUsed="10", tokenBudget="100")
    assert await store.reserve_tokens(TRACE, 50) is True
    assert await store.reserve_tokens(TRACE, 41) is False
    assert (await store.snapshot(TRACE)).tokens_used == 60
    with pytest.raises(BudgetStateInvalid):
        await store.reserve_tokens(TRACE, -1)
    await redis.aclose()


async def test_settlement_reconciles_reserved_and_actual_tokens() -> None:
    redis = FakeRedis(decode_responses=True)
    store = await store_with(redis, tokensUsed="10", tokenBudget="100")
    assert await store.reserve_tokens(TRACE, 50) is True
    settlement = await store.settle_tokens(TRACE, reserved=50, actual=20)
    assert settlement.tokens_used == 30
    assert settlement.overrun is False
    await redis.aclose()


async def test_settlement_reports_overrun_and_clamps_budget() -> None:
    redis = FakeRedis(decode_responses=True)
    store = await store_with(redis, tokensUsed="90", tokenBudget="100")
    settlement = await store.settle_tokens(TRACE, reserved=0, actual=20)
    assert settlement.tokens_used == 100
    assert settlement.overrun is True
    await redis.aclose()


async def test_redis_errors_are_mapped_to_budget_unavailable() -> None:
    class FailingRedis:
        async def hgetall(self, key: str) -> dict[str, str]:
            raise RedisError("down")

    store = RedisBudgetStore(FailingRedis()) # type: ignore[arg-type]
    with pytest.raises(BudgetUnavailable):
        await store.snapshot(TRACE)


async def test_in_memory_store_matches_counter_semantics() -> None:
    store = InMemoryBudgetStore(
        BudgetSnapshot(
            tokens_used=0,
            tool_calls_made=0,
            rounds_used=0,
            token_budget=100,
            max_tool_calls=1,
            max_rounds=2,
            deadline_at="2026-10-27T10:00:10Z",
        )
    )
    assert await store.consume_round(TRACE) is True
    assert await store.consume_round(TRACE) is True
    assert await store.consume_round(TRACE) is False
    assert await store.reserve_tokens(TRACE, 100) is True
    assert await store.reserve_tokens(TRACE, 1) is False
    settlement = await store.settle_tokens(TRACE, reserved=100, actual=40)
    assert settlement.tokens_used == 40


def test_deadline_uses_total_remaining_time() -> None:
    now = [100.0]
    deadline = Deadline(100, clock=lambda: now[0])
    assert 99 <= deadline.remaining_ms() <= 100
    now[0] = 100.05
    assert 0 < deadline.remaining_ms() <= 50
    now[0] = 100.2
    assert deadline.expired()
    with pytest.raises(ValueError):
        Deadline(0)
