"""真实 PG 契约测试：替身语义不可用来验证 SQL（方案 B3 / DoD ①）。

本机没有 PostgreSQL 时自动跳过；CI 需提供 postgres + pgvector service 并设置
``DOCQA_TEST_PG_DSN``，否则「写入带 tenant A → A 命中 / B 0 行 / 漏传抛错」这条门禁
不会真正运行——这正是 004 回归能穿过全绿 CI 的直接原因。
"""

from __future__ import annotations

import os
import uuid

import psycopg
import pytest

from docqa_ai.parse_worker.vector_writer import (
    PgVectorWriter,
    VectorRecord,
    VectorWriteError,
    make_vector_id,
)
from docqa_ai.protocol.models import DocumentScope
from docqa_ai.rag.pg_connection import build_pg_provider
from docqa_ai.rag.repository import PgVectorRepository, vector_literal
from tests.conftest import TENANT_ID

PG_DSN = os.getenv("DOCQA_TEST_PG_DSN")
TABLE = "chunk_embedding_1024"
EMBEDDING = tuple([1.0] + [0.0] * 1023)
VERSION = "v1"

pytestmark = pytest.mark.skipif(
    not PG_DSN,
    reason="set DOCQA_TEST_PG_DSN to a PostgreSQL instance with pgvector to run the real-SQL contract",
)

DDL = """
CREATE EXTENSION IF NOT EXISTS vector;
CREATE TABLE IF NOT EXISTS chunk_embedding_1024 (
    vector_id uuid PRIMARY KEY,
    document_id uuid NOT NULL,
    chunk_index int NOT NULL,
    content text NOT NULL,
    embedding vector(1024) NOT NULL,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    index_version text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    tenant_id text NOT NULL DEFAULT ''
);
"""


def _record(document_id: uuid.UUID, index: int) -> VectorRecord:
    return VectorRecord(
        vector_id=make_vector_id(document_id, VERSION, index),
        chunk_index=index,
        content=f"chunk {index}",
        embedding=EMBEDDING,
        metadata={"index_version": VERSION},
    )


async def _reset() -> None:
    async with await psycopg.AsyncConnection.connect(PG_DSN) as connection:
        async with connection.cursor() as cursor:
            await cursor.execute(DDL)
            await cursor.execute(f"TRUNCATE {TABLE}")
        await connection.commit()


async def test_write_then_read_is_tenant_scoped_on_real_postgres() -> None:
    await _reset()
    document_id = uuid.uuid4()
    tenant_a = str(TENANT_ID)
    tenant_b = str(uuid.uuid4())
    scope = DocumentScope(document_id=document_id, index_version=VERSION)
    provider = build_pg_provider(PG_DSN)
    writer = PgVectorWriter(PG_DSN, TABLE, dimensions=1024, connection_provider=provider)
    repository = PgVectorRepository(PG_DSN, TABLE, 3, 1024, connection_provider=provider)

    try:
        assert await writer.replace(document_id, VERSION, [_record(document_id, 0)], tenant_id=tenant_a) == 1
        assert len(await repository.search(EMBEDDING, [scope], 10, tenant_id=tenant_a)) == 1
        assert await repository.search(EMBEDDING, [scope], 10, tenant_id=tenant_b) == []
    finally:
        await provider.aclose()


async def test_missing_tenant_is_rejected_before_any_row_is_written() -> None:
    await _reset()
    document_id = uuid.uuid4()
    provider = build_pg_provider(PG_DSN)
    writer = PgVectorWriter(PG_DSN, TABLE, dimensions=1024, connection_provider=provider)

    try:
        with pytest.raises(VectorWriteError, match="tenant_id is required"):
            await writer.replace(document_id, VERSION, [_record(document_id, 0)])
        async with provider.connection() as connection:
            async with connection.cursor() as cursor:
                await cursor.execute(f"SELECT COUNT(*) FROM {TABLE}")
                row = await cursor.fetchone()
        assert row is not None and row[0] == 0
    finally:
        await provider.aclose()


async def test_unlabelled_legacy_rows_are_never_returned_by_a_tenant_query() -> None:
    await _reset()
    document_id = uuid.uuid4()
    tenant_a = str(TENANT_ID)
    scope = DocumentScope(document_id=document_id, index_version=VERSION)
    async with await psycopg.AsyncConnection.connect(PG_DSN) as connection:
        async with connection.cursor() as cursor:
            await cursor.execute(
                f"INSERT INTO {TABLE} "
                "(vector_id, document_id, chunk_index, content, embedding, metadata, index_version, tenant_id) "
                "VALUES (%s, %s, %s, %s, %s::vector, %s::jsonb, %s, '')",
                (
                    make_vector_id(document_id, VERSION, 0),
                    document_id,
                    0,
                    "pre-migration row",
                    vector_literal(EMBEDDING),
                    "{}",
                    VERSION,
                ),
            )
        await connection.commit()

    provider = build_pg_provider(PG_DSN)
    repository = PgVectorRepository(PG_DSN, TABLE, 3, 1024, connection_provider=provider)
    try:
        assert await repository.search(EMBEDDING, [scope], 10, tenant_id=tenant_a) == []
    finally:
        await provider.aclose()
