from __future__ import annotations

import asyncio
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from typing import Any

import psycopg

# 池不是硬依赖：`psycopg_pool` 未安装（离线 venv / 本轮未重装依赖）时必须仍然能起服务与跑测试。
# 生产镜像装上 `psycopg[pool]` 后由组装层一次性建池，注入读侧两路仓库与写侧 writer 共用。
DEFAULT_MIN_SIZE = 1
DEFAULT_MAX_SIZE = 8


def open_pool(
    dsn: str,
    connect_timeout_s: int,
    *,
    min_size: int = DEFAULT_MIN_SIZE,
    max_size: int = DEFAULT_MAX_SIZE,
) -> Any | None:
    """能建池就返回一个未打开的 AsyncConnectionPool，否则返回 None。

    `open=False` 让构造完全同步且零 I/O：真正的连接由第一次 `connection()` 触发 `open()`，
    因此导入期与启动期都不会因为 PG 暂时不可达而失败。
    """

    try:
        from psycopg_pool import AsyncConnectionPool
    except ImportError:
        return None
    return AsyncConnectionPool(
        conninfo=dsn,
        min_size=min_size,
        max_size=max_size,
        open=False,
        kwargs={"connect_timeout": connect_timeout_s},
    )


class PgConnectionProvider:
    """数据面唯一的取连入口：池可用则复用池连接，不可用则退化为逐次直连。

    三种消费者（向量读、词法读、向量写）在同一个进程里注入同一个实例，共享同一个池；
    应用关闭时由持有者调用 `aclose()` 统一释放。
    """

    def __init__(self, dsn: str, connect_timeout_s: int = 3, pool: Any | None = None) -> None:
        self._dsn = dsn
        self._connect_timeout_s = connect_timeout_s
        self._pool = pool
        self._open_lock = asyncio.Lock()
        self._opened = False

    @asynccontextmanager
    async def connection(self) -> AsyncIterator[Any]:
        if self._pool is not None:
            if not self._opened:
                async with self._open_lock:
                    if not self._opened:
                        await self._pool.open()
                        self._opened = True
            async with self._pool.connection() as conn:
                yield conn
            return
        # 直连回退刻意不用 AsyncConnection 的 async-with：写侧需要在同一连接上显式开事务，
        # 且测试用的 recording connection 只需实现 close()。退出即关闭，语义与修复前一致。
        connection = await psycopg.AsyncConnection.connect(
            self._dsn,
            connect_timeout=self._connect_timeout_s,
        )
        try:
            yield connection
        finally:
            await connection.close()

    async def aclose(self) -> None:
        if self._pool is not None and self._opened:
            await self._pool.close()
            self._opened = False


def build_pg_provider(dsn: str, connect_timeout_s: int = 3) -> PgConnectionProvider:
    """单一组装根入口：API 进程与 worker 进程各自只调一次，把同一个 provider 注入下游。

    `open_pool()` 此前全仓零调用点，导致每次问答都新建直连。这里把它真正接上；
    psycopg_pool 未安装时 `open_pool` 返回 None，自动退化为逐次直连（离线/CI 仍可用）。
    """
    return PgConnectionProvider(dsn, connect_timeout_s, pool=open_pool(dsn, connect_timeout_s))
