from __future__ import annotations

import pytest
from fakeredis.aioredis import FakeRedis
from pydantic import ValidationError
from redis.exceptions import RedisError

from docqa_ai.config import Settings
from docqa_ai.governance.budget import BudgetStateInvalid, BudgetUnavailable
from docqa_ai.governance.experiment_budget import (
    EXPERIMENT_KEY_SUFFIX,
    ExperimentSnapshot,
    InMemoryExperimentBudgetStore,
    RedisExperimentBudgetStore,
)
from tests.conftest import make_settings

KEY = f"agent:budget:{EXPERIMENT_KEY_SUFFIX}"


def redis_store(call_limit: int, token_limit: int) -> RedisExperimentBudgetStore:
    return RedisExperimentBudgetStore(FakeRedis(decode_responses=True), call_limit, token_limit)


async def test_cold_ledger_reads_zero_and_still_allows_the_first_call() -> None:
    """账本键由 Python 用 HINCRBY 创建，第一次读必须当 0，不能把「键不存在」判成非法状态。"""

    store = redis_store(2, 1000)
    assert await store.snapshot() == ExperimentSnapshot(calls_used=0, tokens_used=0, call_limit=2, token_limit=1000)
    assert await store.authorize_call(100) is True


async def test_call_count_limit_is_enforced_on_the_third_request() -> None:
    store = redis_store(2, 100_000)
    assert await store.authorize_call(10) is True
    assert await store.authorize_call(10) is True
    assert await store.authorize_call(10) is False
    snapshot = await store.snapshot()
    assert snapshot.calls_used == 2
    assert snapshot.tokens_used == 20


async def test_token_ceiling_blocks_the_call_that_would_cross_it() -> None:
    store = redis_store(50, 100)
    assert await store.authorize_call(60) is True
    assert await store.authorize_call(50) is False
    assert (await store.snapshot()).tokens_used == 60


async def test_zero_limit_refuses_every_call_including_the_first() -> None:
    """默认配置就是「额度未放行」，此时任何真实调用都不该被授权（fail-closed）。"""

    for call_limit, token_limit in ((0, 100), (10, 0), (0, 0)):
        store = redis_store(call_limit, token_limit)
        assert await store.authorize_call(1) is False
        assert (await store.snapshot()).calls_used == 0


async def test_settlement_returns_the_reserved_overestimate() -> None:
    store = redis_store(10, 1000)
    assert await store.authorize_call(500) is True
    snapshot = await store.settle_call(reserved=500, actual=120)
    assert snapshot.tokens_used == 120
    assert snapshot.calls_used == 1


async def test_settlement_records_the_true_overrun_instead_of_clamping() -> None:
    """账本是花钱记录：超出上限的真实值必须看得见，但下一次调用仍然被拒。"""

    store = redis_store(10, 100)
    assert await store.authorize_call(40) is True
    snapshot = await store.settle_call(reserved=40, actual=250)
    assert snapshot.tokens_used == 250
    assert await store.authorize_call(1) is False


async def test_settlement_never_drives_the_ledger_negative() -> None:
    store = redis_store(10, 1000)
    await store.authorize_call(30)
    snapshot = await store.settle_call(reserved=1000, actual=0)
    assert snapshot.tokens_used == 0


async def test_release_call_returns_both_the_call_and_the_reservation() -> None:
    """本地检查在授权之后拒绝时外呼没有发生：调用数与预订都要退回，额度可以再用一次。"""

    store = redis_store(1, 1000)
    assert await store.authorize_call(400) is True
    snapshot = await store.release_call(400)
    assert snapshot == ExperimentSnapshot(calls_used=0, tokens_used=0, call_limit=1, token_limit=1000)
    assert await store.authorize_call(400) is True


async def test_release_call_is_not_a_settlement_with_zero_usage() -> None:
    """`settle_call(reserved, 0)` 表示「调了但没花钱」，调用数留下；`release_call` 表示「没调」。"""

    settled = redis_store(10, 1000)
    await settled.authorize_call(100)
    assert (await settled.settle_call(reserved=100, actual=0)).calls_used == 1

    released = redis_store(10, 1000)
    await released.authorize_call(100)
    assert (await released.release_call(100)).calls_used == 0


async def test_release_call_without_a_reservation_does_not_create_the_key() -> None:
    """账本键只由 `authorize_call` 创建：空退不能留下一个 0/0 的幽灵键。"""

    redis = FakeRedis(decode_responses=True)
    store = RedisExperimentBudgetStore(redis, 10, 1000)
    snapshot = await store.release_call(100)
    assert snapshot.calls_used == 0
    assert snapshot.tokens_used == 0
    assert await redis.hgetall(KEY) == {}
    await redis.aclose()


@pytest.mark.parametrize("amount", [-1, -100])
async def test_negative_amounts_are_rejected_before_touching_redis(amount: int) -> None:
    store = redis_store(10, 1000)
    with pytest.raises(BudgetStateInvalid):
        await store.authorize_call(amount)
    with pytest.raises(BudgetStateInvalid):
        await store.settle_call(reserved=amount, actual=1)
    with pytest.raises(BudgetStateInvalid):
        await store.settle_call(reserved=1, actual=amount)
    with pytest.raises(BudgetStateInvalid):
        await store.release_call(amount)


async def test_tampered_field_values_fail_closed() -> None:
    for bad in ("not-a-number", "-1"):
        redis = FakeRedis(decode_responses=True)
        await redis.hset(KEY, mapping={"callsUsed": bad, "tokensUsed": "1"})
        store = RedisExperimentBudgetStore(redis, 10, 1000)
        with pytest.raises(BudgetStateInvalid):
            await store.authorize_call(1)
        await redis.aclose()


async def test_ledger_uses_the_key_name_fixed_by_the_task_document() -> None:
    redis = FakeRedis(decode_responses=True)
    store = RedisExperimentBudgetStore(redis, 5, 500)
    assert await store.authorize_call(100) is True
    assert [key async for key in redis.scan_iter(match="*")] == [KEY]
    await redis.aclose()


async def test_redis_read_failure_maps_to_budget_unavailable() -> None:
    class FailingRedis:
        async def hgetall(self, key: str) -> dict[str, str]:
            raise RedisError("down")

        def pipeline(self):
            raise AssertionError("snapshot must not open a transaction")

    store = RedisExperimentBudgetStore(FailingRedis(), 10, 1000)  # type: ignore[arg-type]
    with pytest.raises(BudgetUnavailable):
        await store.snapshot()


async def test_redis_write_failure_maps_to_budget_unavailable() -> None:
    class FailingPipeline:
        async def watch(self, key: str) -> None:
            raise RedisError("down")

        async def hgetall(self, key: str) -> dict[str, str]:
            raise RedisError("down")

        async def unwatch(self) -> None:
            return None

        async def reset(self) -> None:
            return None

    class FailingRedis:
        def pipeline(self) -> FailingPipeline:
            return FailingPipeline()

        async def hgetall(self, key: str) -> dict[str, str]:
            raise RedisError("down")

    store = RedisExperimentBudgetStore(FailingRedis(), 10, 1000)  # type: ignore[arg-type]
    with pytest.raises(BudgetUnavailable):
        await store.authorize_call(1)


def test_negative_limits_are_rejected_at_construction() -> None:
    with pytest.raises(ValueError):
        RedisExperimentBudgetStore(FakeRedis(), -1, 10)
    with pytest.raises(ValueError):
        InMemoryExperimentBudgetStore(10, -1)


async def test_in_memory_store_matches_the_redis_semantics_step_by_step() -> None:
    """两个实现必须逐条同形，否则「CI 里绿、真实额度超支」会在真实额度场景变成不可见差异。"""

    redis = FakeRedis(decode_responses=True)
    redis_side = RedisExperimentBudgetStore(redis, 3, 100)
    memory_side = InMemoryExperimentBudgetStore(3, 100)

    redis_allowed = await redis_side.authorize_call(40)
    memory_allowed = await memory_side.authorize_call(40)
    assert redis_allowed is memory_allowed is True

    redis_blocked = await redis_side.authorize_call(70)
    memory_blocked = await memory_side.authorize_call(70)
    assert redis_blocked is memory_blocked is False
    assert await redis_side.snapshot() == await memory_side.snapshot()

    settled_redis = await redis_side.settle_call(reserved=40, actual=90)
    settled_memory = await memory_side.settle_call(reserved=40, actual=90)
    assert settled_redis == settled_memory
    assert settled_redis.tokens_used == 90

    redis_final = await redis_side.authorize_call(1)
    memory_final = await memory_side.authorize_call(1)
    assert redis_final is memory_final is True

    released_redis = await redis_side.release_call(1)
    released_memory = await memory_side.release_call(1)
    assert released_redis == released_memory
    assert released_redis.calls_used == 1
    assert released_redis.tokens_used == 90
    await redis.aclose()


async def test_publish_limits_writes_only_the_two_limit_fields() -> None:
    """上限发布是回显，不是记账：花过的调用数与 token 数一个都不许被启动动作改写。"""

    redis = FakeRedis(decode_responses=True)
    await redis.hset(KEY, mapping={"callsUsed": "3", "tokensUsed": "120"})
    store = RedisExperimentBudgetStore(redis, 12, 12_000)

    await store.publish_limits()

    assert await redis.hgetall(KEY) == {
        "callsUsed": "3",
        "tokensUsed": "120",
        "callLimit": "12",
        "tokenLimit": "12000",
    }
    await redis.aclose()


async def test_publish_limits_creates_the_hash_before_the_first_call() -> None:
    """冷启动时键还不存在：发布上限会建出哈希，但已用量仍读作 0、首次调用仍被放行。"""

    redis = FakeRedis(decode_responses=True)
    store = RedisExperimentBudgetStore(redis, 2, 1000)

    await store.publish_limits()

    assert await redis.hgetall(KEY) == {"callLimit": "2", "tokenLimit": "1000"}
    assert await store.snapshot() == ExperimentSnapshot(calls_used=0, tokens_used=0, call_limit=2, token_limit=1000)
    assert await store.authorize_call(100) is True
    await redis.aclose()


async def test_republishing_after_a_config_change_replaces_the_limits() -> None:
    """上限来自环境变量：改配置重启必须让管理面看到新值，而不是保留旧值。"""

    redis = FakeRedis(decode_responses=True)
    first = RedisExperimentBudgetStore(redis, 200, 200_000)
    await first.publish_limits()
    await first.authorize_call(500)

    second = RedisExperimentBudgetStore(redis, 300, 300_000)
    await second.publish_limits()

    assert await redis.hgetall(KEY) == {
        "callsUsed": "1",
        "tokensUsed": "500",
        "callLimit": "300",
        "tokenLimit": "300000",
    }
    await redis.aclose()


async def test_publish_limits_failure_maps_to_budget_unavailable() -> None:
    class FailingRedis:
        async def hset(self, key: str, mapping: dict[str, str]) -> None:
            raise RedisError("down")

    store = RedisExperimentBudgetStore(FailingRedis(), 10, 1000)  # type: ignore[arg-type]
    with pytest.raises(BudgetUnavailable):
        await store.publish_limits()


async def test_in_memory_publish_limits_is_a_noop() -> None:
    store = InMemoryExperimentBudgetStore(3, 100)
    await store.authorize_call(10)

    await store.publish_limits()

    assert await store.snapshot() == ExperimentSnapshot(calls_used=1, tokens_used=10, call_limit=3, token_limit=100)


def test_real_provider_without_budget_is_refused_at_startup() -> None:
    with pytest.raises(ValidationError, match="experiment_budget"):
        make_settings(llm_provider="openai", llm_base_url="https://example.invalid/v1")
    with pytest.raises(ValidationError, match="experiment_budget"):
        make_settings(embedding_provider="openai", embedding_base_url="https://example.invalid/v1")
    with pytest.raises(ValidationError, match="experiment_token_budget"):
        make_settings(
            llm_provider="openai",
            llm_base_url="https://example.invalid/v1",
            experiment_budget=5,
        )


def test_offline_providers_stay_allowed_with_zero_budget() -> None:
    """默认形态必须继续零外呼：离线 Provider + 额度 0 是合法的启动组合。"""

    settings = make_settings()
    assert settings.experiment_budget == 0
    assert settings.experiment_token_budget == 0
    assert settings.redacted_summary()["experiment_budget"] == 0


def test_openai_provider_with_both_limits_starts_fine() -> None:
    settings = make_settings(
        _env_file=None,
        llm_provider="openai",
        llm_base_url="https://example.invalid/v1",
        experiment_budget=200,
        experiment_token_budget=200_000,
    )
    summary = settings.redacted_summary()
    assert summary["experiment_budget"] == 200
    assert summary["experiment_token_budget"] == 200000
    assert "api_key" not in str(summary)


def test_experiment_limits_come_from_the_task_document_env_names(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("DOCQA_AI_EXPERIMENT_BUDGET", "200")
    monkeypatch.setenv("DOCQA_AI_EXPERIMENT_TOKEN_BUDGET", "200000")
    settings = Settings(_env_file=None)
    assert settings.experiment_budget == 200
    assert settings.experiment_token_budget == 200000
