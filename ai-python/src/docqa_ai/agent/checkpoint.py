from __future__ import annotations

from langgraph.checkpoint.postgres.aio import AsyncPostgresSaver
from psycopg.rows import dict_row
from psycopg_pool import AsyncConnectionPool

from docqa_ai.config import Settings


def build_checkpointer(settings: Settings) -> AsyncPostgresSaver:
    """构造官方 Postgres saver；连接参数与检索池刻意不同，不能复用 rag/pg_connection.open_pool。

    saver 的 SQL 依赖 autocommit（setup 的迁移逐条自提交）、禁用 prepared statement
    （连接经池复用，预编译计划会随会话漂移）并要求 dict_row。官方 from_conn_string
    是单连接上下文管理器，无法承载并发请求，这里按同一组参数自建小池注入 saver。
    `open=False` 让构造零 I/O：真正的连接由 lifespan 的 setup_checkpointer 触发。
    """

    dsn = settings.checkpoint_dsn or settings.pg_dsn
    pool = AsyncConnectionPool(
        conninfo=dsn,
        min_size=1,
        max_size=8,
        open=False,
        kwargs={
            "autocommit": True,
            "prepare_threshold": 0,
            "row_factory": dict_row,
            "connect_timeout": settings.pg_connect_timeout_s,
        },
    )
    return AsyncPostgresSaver(conn=pool)


async def setup_checkpointer(saver: AsyncPostgresSaver) -> None:
    """lifespan 里调用一次：开池并幂等建表。

    不写手工迁移——setup() 自带 checkpoint_migrations 版本表，重复执行与升级都收敛；
    PG 不可达时在这里 fail-fast，而不是让问答请求在运行期才发现 checkpoint 写不进去。
    """

    conn = saver.conn
    if isinstance(conn, AsyncConnectionPool):
        await conn.open(wait=True)
    await saver.setup()


async def close_checkpointer(saver: object) -> None:
    """释放 saver 持有的连接池；内存 saver（测试注入）没有池，直接跳过。"""

    conn = getattr(saver, "conn", None)
    if isinstance(conn, AsyncConnectionPool):
        await conn.close()
