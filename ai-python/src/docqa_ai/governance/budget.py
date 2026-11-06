from __future__ import annotations

import asyncio
import time
from collections.abc import Callable
from dataclasses import dataclass
from typing import Protocol

from redis.asyncio import Redis
from redis.exceptions import RedisError, WatchError


class BudgetError(Exception):
    pass


class BudgetUnavailable(BudgetError):
    pass


class BudgetStateInvalid(BudgetError):
    pass


@dataclass(frozen=True)
class BudgetSnapshot:
    tokens_used: int
    tool_calls_made: int
    rounds_used: int
    token_budget: int
    max_tool_calls: int
    max_rounds: int
    deadline_at: str


@dataclass(frozen=True)
class TokenSettlement:
    tokens_used: int
    overrun: bool


class BudgetStore(Protocol):
    async def snapshot(self, trace_id: str) -> BudgetSnapshot: ...

    async def consume_round(self, trace_id: str) -> bool: ...

    async def consume_tool(self, trace_id: str) -> bool: ...

    async def reserve_tokens(self, trace_id: str, amount: int) -> bool: ...

    async def settle_tokens(self, trace_id: str, reserved: int, actual: int) -> TokenSettlement: ...


def _parse_int(data: dict[str, str], field: str) -> int:
    raw = data.get(field)
    if raw is None:
        raise BudgetStateInvalid(f"budget field is missing: {field}")
    try:
        value = int(raw)
    except ValueError as exc:
        raise BudgetStateInvalid(f"budget field is invalid: {field}") from exc
    if value < 0:
        raise BudgetStateInvalid(f"budget field is negative: {field}")
    return value


class RedisBudgetStore:
    """Atomic budget counters stored in the hash initialized by agent-gateway."""

    def __init__(
        self,
        redis: Redis,
        prefix: str = "agent:budget:",
        max_attempts: int = 3,
        sleep: Callable[[float], object] = asyncio.sleep,
    ) -> None:
        self._redis = redis
        self._prefix = prefix
        self._max_attempts = max_attempts
        self._sleep = sleep

    def _key(self, trace_id: str) -> str:
        return f"{self._prefix}{trace_id}"

    async def snapshot(self, trace_id: str) -> BudgetSnapshot:
        try:
            data = await self._redis.hgetall(self._key(trace_id))
        except RedisError as exc:
            raise BudgetUnavailable("redis budget read failed") from exc
        if not data:
            raise BudgetStateInvalid("budget state is missing")
        deadline_at = data.get("deadlineAt")
        if not deadline_at:
            raise BudgetStateInvalid("budget field is missing: deadlineAt")
        return BudgetSnapshot(
            tokens_used=_parse_int(data, "tokensUsed"),
            tool_calls_made=_parse_int(data, "toolCallsMade"),
            rounds_used=_parse_int(data, "roundsUsed"),
            token_budget=_parse_int(data, "tokenBudget"),
            max_tool_calls=_parse_int(data, "maxToolCalls"),
            max_rounds=_parse_int(data, "maxRounds"),
            deadline_at=deadline_at,
        )

    async def consume_round(self, trace_id: str) -> bool:
        return await self._increment_with_cap(trace_id, "roundsUsed", "maxRounds")

    async def consume_tool(self, trace_id: str) -> bool:
        return await self._increment_with_cap(trace_id, "toolCallsMade", "maxToolCalls")

    async def reserve_tokens(self, trace_id: str, amount: int) -> bool:
        if amount < 0:
            raise BudgetStateInvalid("token reservation must be non-negative")

        def decide(data: dict[str, str]) -> tuple[bool, int]:
            used = _parse_int(data, "tokensUsed")
            budget = _parse_int(data, "tokenBudget")
            return used + amount <= budget, amount

        return await self._transact_increment(trace_id, "tokensUsed", decide)

    async def settle_tokens(self, trace_id: str, reserved: int, actual: int) -> TokenSettlement:
        if reserved < 0 or actual < 0:
            raise BudgetStateInvalid("token settlement must be non-negative")
        key = self._key(trace_id)
        for attempt in range(self._max_attempts):
            pipe = self._redis.pipeline()
            try:
                await pipe.watch(key)
                data = await pipe.hgetall(key)
                if not data:
                    raise BudgetStateInvalid("budget state is missing")
                used = _parse_int(data, "tokensUsed")
                budget = _parse_int(data, "tokenBudget")
                target = max(0, used + actual - reserved)
                overrun = target > budget
                target = min(target, budget)
                if target == used:
                    await pipe.unwatch()
                    return TokenSettlement(tokens_used=used, overrun=overrun)
                pipe.multi()
                pipe.hset(key, "tokensUsed", str(target))
                await pipe.execute()
                return TokenSettlement(tokens_used=target, overrun=overrun)
            except WatchError:
                await self._sleep(0.005 * (attempt + 1))
            except RedisError as exc:
                raise BudgetUnavailable("redis budget write failed") from exc
            finally:
                await pipe.reset()
        raise BudgetUnavailable("redis budget transaction did not converge")

    async def _increment_with_cap(self, trace_id: str, field: str, cap_field: str) -> bool:
        def decide(data: dict[str, str]) -> tuple[bool, int]:
            current = _parse_int(data, field)
            cap = _parse_int(data, cap_field)
            return current + 1 <= cap, 1

        return await self._transact_increment(trace_id, field, decide)

    async def _transact_increment(
        self,
        trace_id: str,
        field: str,
        decide: Callable[[dict[str, str]], tuple[bool, int]],
    ) -> bool:
        key = self._key(trace_id)
        for attempt in range(self._max_attempts):
            pipe = self._redis.pipeline()
            try:
                await pipe.watch(key)
                data = await pipe.hgetall(key)
                if not data:
                    raise BudgetStateInvalid("budget state is missing")
                allowed, amount = decide(data)
                if not allowed:
                    await pipe.unwatch()
                    return False
                pipe.multi()
                pipe.hincrby(key, field, amount)
                await pipe.execute()
                return True
            except WatchError:
                await self._sleep(0.005 * (attempt + 1))
            except RedisError as exc:
                raise BudgetUnavailable("redis budget write failed") from exc
            finally:
                await pipe.reset()
        raise BudgetUnavailable("redis budget transaction did not converge")


class InMemoryBudgetStore:
    """Deterministic budget store for tests and local component checks only."""

    def __init__(self, snapshot: BudgetSnapshot) -> None:
        self._lock = asyncio.Lock()
        self._tokens = snapshot.tokens_used
        self._tools = snapshot.tool_calls_made
        self._rounds = snapshot.rounds_used
        self._token_budget = snapshot.token_budget
        self._max_tools = snapshot.max_tool_calls
        self._max_rounds = snapshot.max_rounds
        self._deadline = snapshot.deadline_at

    async def snapshot(self, trace_id: str) -> BudgetSnapshot:
        async with self._lock:
            return BudgetSnapshot(
                tokens_used=self._tokens,
                tool_calls_made=self._tools,
                rounds_used=self._rounds,
                token_budget=self._token_budget,
                max_tool_calls=self._max_tools,
                max_rounds=self._max_rounds,
                deadline_at=self._deadline,
            )

    async def consume_round(self, trace_id: str) -> bool:
        async with self._lock:
            if self._rounds + 1 > self._max_rounds:
                return False
            self._rounds += 1
            return True

    async def consume_tool(self, trace_id: str) -> bool:
        async with self._lock:
            if self._tools + 1 > self._max_tools:
                return False
            self._tools += 1
            return True

    async def reserve_tokens(self, trace_id: str, amount: int) -> bool:
        async with self._lock:
            if self._tokens + amount > self._token_budget:
                return False
            self._tokens += amount
            return True

    async def settle_tokens(self, trace_id: str, reserved: int, actual: int) -> TokenSettlement:
        async with self._lock:
            target = max(0, self._tokens + actual - reserved)
            overrun = target > self._token_budget
            self._tokens = min(target, self._token_budget)
            return TokenSettlement(tokens_used=self._tokens, overrun=overrun)


class Deadline:
    """Monotonic total deadline; every downstream call gets only the remaining time."""

    def __init__(self, milliseconds: int, clock: Callable[[], float] = time.monotonic) -> None:
        if milliseconds <= 0:
            raise ValueError("deadline must be positive")
        self._clock = clock
        self._deadline = clock() + milliseconds / 1000.0

    def remaining_ms(self) -> int:
        return max(0, int((self._deadline - self._clock()) * 1000))

    def remaining_seconds(self) -> float:
        return max(0.0, self._deadline - self._clock())

    def expired(self) -> bool:
        return self.remaining_seconds() <= 0
