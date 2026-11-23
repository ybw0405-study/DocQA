"""长期记忆（PR3）：显式指令写入、按租户+用户隔离、上限淘汰与 TTL。

存储复用 langgraph-checkpoint-postgres 提供的 AsyncPostgresStore（与 checkpoint 同一依赖、
同一个库），但连接池独立：store 的 SQL 同样依赖 autocommit / 禁用 prepared statement /
dict_row，参数与 agent/checkpoint.py 逐字同形，不复用 rag/pg_connection.open_pool。

记忆文本只存**掩码后**的事实；写入前的 PII 拒写判定在 agent/service.py，这里不做二次放行。
"""

from __future__ import annotations

import re
import uuid
from collections.abc import Callable
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta

from langgraph.store.base import BaseStore, Item
from langgraph.store.postgres.aio import AsyncPostgresStore
from psycopg.rows import dict_row
from psycopg_pool import AsyncConnectionPool

from docqa_ai.config import Settings

MAX_FACTS_PER_USER = 50
TTL_DAYS = 90
_TTL_MINUTES = float(TTL_DAYS * 24 * 60)

# 只对掩码后的问题做前缀判定；「记住」后必须紧跟分隔符，避免把普通问句误判成写入指令。
_MEMORY_COMMAND = re.compile(r"^(记住[:：,，\s]|remember[:：\s])")


def memory_command_fact(masked_question: str) -> str | None:
    """命中显式记忆指令时返回前缀之后的事实文本（可为空串）；未命中返回 None。"""

    match = _MEMORY_COMMAND.match(masked_question)
    if match is None:
        return None
    return masked_question[match.end() :].strip()


@dataclass(frozen=True)
class MemoryFact:
    key: str
    text: str
    created_at: datetime


def _namespace(tenant_id: object, user_id: object) -> tuple[str, str, str]:
    return ("memories", str(tenant_id), str(user_id))


def _created_at(item: Item) -> datetime:
    raw = item.value.get("created_at")
    if isinstance(raw, str):
        try:
            parsed = datetime.fromisoformat(raw)
            if parsed.tzinfo is not None:
                return parsed
        except ValueError:
            pass
    # 值形态损坏时按最旧处理：上限淘汰会先清掉它，而不是让一条读不懂的行永久占位。
    return datetime.min.replace(tzinfo=UTC)


class MemoryStore:
    """AsyncPostgresStore 的治理包装：命名空间隔离、每用户 50 条上限、90 天 TTL。

    TTL 有两层：Postgres store 自带 ttl 配置负责物理过期；这里再按 created_at 过滤，
    让内存替身（测试）与 sweep 尚未跑到的真实库在读取语义上一致。
    """

    def __init__(self, store: BaseStore, *, clock: Callable[[], datetime] | None = None) -> None:
        self._store = store
        self._clock = clock or (lambda: datetime.now(UTC))

    @property
    def backend(self) -> BaseStore:
        return self._store

    async def remember(self, tenant_id: object, user_id: object, text: str) -> str:
        namespace = _namespace(tenant_id, user_id)
        key = str(uuid.uuid4())
        # 物理 TTL 只在支持的 backend 上声明（内存替身不支持）；读取侧的 created_at
        # 过滤对两种 backend 都强制执行同一个 90 天窗口，语义不因替身而漂移。
        ttl = _TTL_MINUTES if getattr(self._store, "supports_ttl", False) else None
        await self._store.aput(
            namespace,
            key,
            {"text": text, "created_at": self._clock().isoformat()},
            ttl=ttl,
        )
        await self._evict(namespace)
        return key

    async def recall(
        self,
        tenant_id: object,
        user_id: object,
        *,
        limit: int = 5,
        max_chars: int = 800,
    ) -> tuple[str, ...]:
        items = await self._store.asearch(_namespace(tenant_id, user_id), limit=limit)
        notes: list[str] = []
        remaining = max_chars
        for fact in self._fresh(items)[:limit]:
            if remaining <= 0:
                break
            text = fact.text[:remaining]
            notes.append(text)
            remaining -= len(text)
        return tuple(notes)

    async def list_facts(self, tenant_id: object, user_id: object) -> tuple[MemoryFact, ...]:
        items = await self._store.asearch(_namespace(tenant_id, user_id), limit=MAX_FACTS_PER_USER)
        return self._fresh(items)

    async def forget(self, tenant_id: object, user_id: object, key: str) -> bool:
        namespace = _namespace(tenant_id, user_id)
        if await self._store.aget(namespace, key) is None:
            return False
        await self._store.adelete(namespace, key)
        return True

    def _fresh(self, items: list[Item]) -> tuple[MemoryFact, ...]:
        cutoff = self._clock() - timedelta(days=TTL_DAYS)
        facts = [
            MemoryFact(key=item.key, text=str(item.value.get("text", "")), created_at=_created_at(item))
            for item in items
        ]
        alive = [fact for fact in facts if fact.created_at >= cutoff and fact.text]
        return tuple(sorted(alive, key=lambda fact: fact.created_at, reverse=True))

    async def _evict(self, namespace: tuple[str, str, str]) -> None:
        # asearch 无查询词时按存储自身排序返回，这里统一按 created_at 重排后淘汰最旧，
        # 顺带清掉 TTL 已过期但 sweep 尚未处理的行。limit 取上限的整数倍，足够覆盖溢出。
        items = await self._store.asearch(namespace, limit=MAX_FACTS_PER_USER * 4)
        cutoff = self._clock() - timedelta(days=TTL_DAYS)
        ordered = sorted(items, key=_created_at, reverse=True)
        for index, item in enumerate(ordered):
            if index >= MAX_FACTS_PER_USER or _created_at(item) < cutoff:
                await self._store.adelete(namespace, item.key)


def build_memory_store(settings: Settings) -> MemoryStore:
    """构造官方 Postgres store；连接参数与 checkpoint 池同形（见模块注释）。

    `open=False` 让构造零 I/O：真正的连接由 lifespan 的 setup_memory_store 触发。
    """

    dsn = settings.memory_dsn or settings.pg_dsn
    pool = AsyncConnectionPool(
        conninfo=dsn,
        min_size=1,
        max_size=4,
        open=False,
        kwargs={
            "autocommit": True,
            "prepare_threshold": 0,
            "row_factory": dict_row,
            "connect_timeout": settings.pg_connect_timeout_s,
        },
    )
    store = AsyncPostgresStore(conn=pool, ttl={"default_ttl": _TTL_MINUTES, "refresh_on_read": False})
    return MemoryStore(store)


async def setup_memory_store(memory: MemoryStore) -> None:
    """lifespan 里调用一次：开池并幂等建表；PG 不可达时启动直接失败，不留到运行期。"""

    conn = getattr(memory.backend, "conn", None)
    if isinstance(conn, AsyncConnectionPool):
        await conn.open(wait=True)
    await memory.backend.setup()


async def close_memory_store(memory: MemoryStore) -> None:
    """释放 store 持有的连接池；内存替身（测试注入）没有池，直接跳过。"""

    conn = getattr(memory.backend, "conn", None)
    if isinstance(conn, AsyncConnectionPool):
        await conn.close()
