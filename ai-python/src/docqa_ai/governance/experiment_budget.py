from __future__ import annotations

import asyncio
from collections.abc import Callable
from dataclasses import dataclass
from typing import Protocol

from redis.asyncio import Redis
from redis.exceptions import RedisError, WatchError

from docqa_ai.governance.budget import BudgetStateInvalid, BudgetUnavailable

EXPERIMENT_KEY_SUFFIX = "session-tokens"
_CALLS_FIELD = "callsUsed"
_TOKENS_FIELD = "tokensUsed"
# 上限字段只做回显：Java 管理面读它们判断 `limitsKnown`（D-153 / S9-5e），
# 不在 Python 的准入逻辑里参与判断——准入用的是构造期传入的限额，不是 Redis 里的值。
_CALL_LIMIT_FIELD = "callLimit"
_TOKEN_LIMIT_FIELD = "tokenLimit"


@dataclass(frozen=True)
class ExperimentSnapshot:
    """跨请求累计的真实调用账本快照。上限来自配置，不是 Java 下发的每轮 `tokenBudget`。"""

    calls_used: int
    tokens_used: int
    call_limit: int
    token_limit: int


class ExperimentBudgetStore(Protocol):
    """真实模型调用的额度门禁：调用前预订，调用后用实际用量对账。"""

    async def snapshot(self) -> ExperimentSnapshot: ...

    async def publish_limits(self) -> None: ...

    async def authorize_call(self, estimated_tokens: int) -> bool: ...

    async def settle_call(self, reserved: int, actual: int) -> ExperimentSnapshot: ...

    async def release_call(self, reserved: int) -> ExperimentSnapshot: ...


def _read_int(data: dict[str, str], field: str) -> int:
    """缺失按 0 处理，非法值才报错。

    与每轮预算的 `snapshot()` 相反：那份哈希由 agent-gateway 先写后读，缺键说明请求非法；
    这份账本由 Python 自己用 `HINCRBY` 创建，第一次读就是空的，把它当错误会让额度门禁
    在冷启动时 fail-closed 到「一次都不能调」，与配置的额度本身矛盾。
    """

    raw = data.get(field)
    if raw is None:
        return 0
    try:
        value = int(raw)
    except ValueError as exc:
        raise BudgetStateInvalid(f"experiment budget field is invalid: {field}") from exc
    if value < 0:
        raise BudgetStateInvalid(f"experiment budget field is negative: {field}")
    return value


class RedisExperimentBudgetStore:
    """Redis 上的全局累计账本，键为 `{budget_key_prefix}session-tokens`，不设过期。

    预订与实际分开（`authorize_call` / `settle_call`），是为了关闭「已经花钱但没记账」的窗口：
    调用前先把上界占住，超限当场拒绝，调用结束再用真实 `usage` 回补差额。
    """

    def __init__(
        self,
        redis: Redis,
        call_limit: int,
        token_limit: int,
        prefix: str = "agent:budget:",
        max_attempts: int = 3,
        sleep: Callable[[float], object] = asyncio.sleep,
    ) -> None:
        if call_limit < 0 or token_limit < 0:
            raise ValueError("experiment budget limits must be non-negative")
        self._redis = redis
        self._call_limit = call_limit
        self._token_limit = token_limit
        self._prefix = prefix
        self._max_attempts = max_attempts
        self._sleep = sleep

    def _key(self) -> str:
        return f"{self._prefix}{EXPERIMENT_KEY_SUFFIX}"

    async def snapshot(self) -> ExperimentSnapshot:
        try:
            data = await self._redis.hgetall(self._key())
        except RedisError as exc:
            raise BudgetUnavailable("redis experiment budget read failed") from exc
        return ExperimentSnapshot(
            calls_used=_read_int(data, _CALLS_FIELD),
            tokens_used=_read_int(data, _TOKENS_FIELD),
            call_limit=self._call_limit,
            token_limit=self._token_limit,
        )

    async def publish_limits(self) -> None:
        """启动时把配置里的两个上限写进账本，供 Java 管理面回显（S9-5e）。

        只写两个字段：`callsUsed` / `tokensUsed` 是花过的钱，任何启动动作都不许动它们。
        每次启动覆盖一次是刻意的——上限来自环境变量，改配置重启后管理面必须看到新值。
        """

        try:
            await self._redis.hset(
                self._key(),
                mapping={
                    _CALL_LIMIT_FIELD: str(self._call_limit),
                    _TOKEN_LIMIT_FIELD: str(self._token_limit),
                },
            )
        except RedisError as exc:
            raise BudgetUnavailable("redis experiment budget limit publish failed") from exc

    async def authorize_call(self, estimated_tokens: int) -> bool:
        if estimated_tokens < 0:
            raise BudgetStateInvalid("estimated tokens must be non-negative")
        if self._call_limit == 0 or self._token_limit == 0:
            return False
        key = self._key()
        for attempt in range(self._max_attempts):
            pipe = self._redis.pipeline()
            try:
                await pipe.watch(key)
                data = await pipe.hgetall(key)
                calls = _read_int(data, _CALLS_FIELD)
                tokens = _read_int(data, _TOKENS_FIELD)
                if calls + 1 > self._call_limit or tokens + estimated_tokens > self._token_limit:
                    await pipe.unwatch()
                    return False
                pipe.multi()
                pipe.hincrby(key, _CALLS_FIELD, 1)
                pipe.hincrby(key, _TOKENS_FIELD, estimated_tokens)
                await pipe.execute()
                return True
            except WatchError:
                await self._sleep(0.005 * (attempt + 1))
            except RedisError as exc:
                raise BudgetUnavailable("redis experiment budget write failed") from exc
            finally:
                await pipe.reset()
        raise BudgetUnavailable("redis experiment budget transaction did not converge")

    async def settle_call(self, reserved: int, actual: int) -> ExperimentSnapshot:
        if reserved < 0 or actual < 0:
            raise BudgetStateInvalid("token settlement must be non-negative")
        key = self._key()
        for attempt in range(self._max_attempts):
            pipe = self._redis.pipeline()
            try:
                await pipe.watch(key)
                data = await pipe.hgetall(key)
                calls = _read_int(data, _CALLS_FIELD)
                tokens = _read_int(data, _TOKENS_FIELD)
                # 刻意不夹紧到上限：这是花钱的账本，超出必须看得见。`authorize_call` 用的是
                # 「已用量 + 预订 > 上限」，超记之后仍然拒绝下一次调用，安全性不依赖夹紧。
                target = max(0, tokens - reserved + actual)
                if target != tokens:
                    pipe.multi()
                    pipe.hset(key, _TOKENS_FIELD, str(target))
                    await pipe.execute()
                    tokens = target
                return ExperimentSnapshot(
                    calls_used=calls,
                    tokens_used=tokens,
                    call_limit=self._call_limit,
                    token_limit=self._token_limit,
                )
            except WatchError:
                await self._sleep(0.005 * (attempt + 1))
            except RedisError as exc:
                raise BudgetUnavailable("redis experiment budget write failed") from exc
            finally:
                await pipe.reset()
        raise BudgetUnavailable("redis experiment budget transaction did not converge")

    async def release_call(self, reserved: int) -> ExperimentSnapshot:
        """收回一次**没有发生**的调用：调用数与预订一起退回（D-121）。

        与 `settle_call(reserved, 0)` 不是一回事：后者表示「调了但没花钱」，调用数必须留下；
        这里的外呼在最外层本地检查就被拒绝，一个字节都没离开本机，把调用数算掉等于让
        一次拒绝永久吃掉一份额度。
        """

        if reserved < 0:
            raise BudgetStateInvalid("released tokens must be non-negative")
        key = self._key()
        for attempt in range(self._max_attempts):
            pipe = self._redis.pipeline()
            try:
                await pipe.watch(key)
                data = await pipe.hgetall(key)
                calls = _read_int(data, _CALLS_FIELD)
                tokens = _read_int(data, _TOKENS_FIELD)
                target_calls = max(0, calls - 1)
                target_tokens = max(0, tokens - reserved)
                if target_calls != calls or target_tokens != tokens:
                    pipe.multi()
                    pipe.hset(key, _CALLS_FIELD, str(target_calls))
                    pipe.hset(key, _TOKENS_FIELD, str(target_tokens))
                    await pipe.execute()
                # 没有可退的东西时一次都不写：账本键的调用数只由 `authorize_call` 增加。
                return ExperimentSnapshot(
                    calls_used=target_calls,
                    tokens_used=target_tokens,
                    call_limit=self._call_limit,
                    token_limit=self._token_limit,
                )
            except WatchError:
                await self._sleep(0.005 * (attempt + 1))
            except RedisError as exc:
                raise BudgetUnavailable("redis experiment budget write failed") from exc
            finally:
                await pipe.reset()
        raise BudgetUnavailable("redis experiment budget transaction did not converge")


class InMemoryExperimentBudgetStore:
    """离线测试与本地组件检查用的等价实现，语义与 Redis 版逐条对齐。"""

    def __init__(self, call_limit: int, token_limit: int) -> None:
        if call_limit < 0 or token_limit < 0:
            raise ValueError("experiment budget limits must be non-negative")
        self._lock = asyncio.Lock()
        self._call_limit = call_limit
        self._token_limit = token_limit
        self._calls = 0
        self._tokens = 0

    def _snapshot(self) -> ExperimentSnapshot:
        return ExperimentSnapshot(
            calls_used=self._calls,
            tokens_used=self._tokens,
            call_limit=self._call_limit,
            token_limit=self._token_limit,
        )

    async def snapshot(self) -> ExperimentSnapshot:
        async with self._lock:
            return self._snapshot()

    async def publish_limits(self) -> None:
        """内存实现没有第二个读者：上限就在对象字段里，没有可发布的共享状态。"""

    async def authorize_call(self, estimated_tokens: int) -> bool:
        if estimated_tokens < 0:
            raise BudgetStateInvalid("estimated tokens must be non-negative")
        async with self._lock:
            if self._call_limit == 0 or self._token_limit == 0:
                return False
            if self._calls + 1 > self._call_limit or self._tokens + estimated_tokens > self._token_limit:
                return False
            self._calls += 1
            self._tokens += estimated_tokens
            return True

    async def settle_call(self, reserved: int, actual: int) -> ExperimentSnapshot:
        if reserved < 0 or actual < 0:
            raise BudgetStateInvalid("token settlement must be non-negative")
        async with self._lock:
            self._tokens = max(0, self._tokens - reserved + actual)
            return self._snapshot()

    async def release_call(self, reserved: int) -> ExperimentSnapshot:
        if reserved < 0:
            raise BudgetStateInvalid("released tokens must be non-negative")
        async with self._lock:
            self._calls = max(0, self._calls - 1)
            self._tokens = max(0, self._tokens - reserved)
            return self._snapshot()
