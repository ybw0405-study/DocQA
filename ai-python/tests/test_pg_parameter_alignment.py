"""占位符与绑定参数对齐的静态护栏（无需真实 PG）。

背景：PgVectorRepository.search 曾把 tenant_value 放在参数列表首位，而 SQL 的
第一个 %s 是 SELECT 里的相似度向量。736 个用内存替身的单测全绿，真实 PG 却
100% 报 InvalidTextRepresentation —— 只有接 pgvector 的契约用例能抓到。
这里用捕获型 cursor 在任何环境都把「逐位对齐」钉住，避免同类回归再次穿过 CI。
"""

from __future__ import annotations

import uuid

import pytest

from docqa_ai.protocol.models import DocumentScope
from docqa_ai.rag.repository import PgVectorRepository, vector_literal

TABLE = "chunk_embedding_1024"
EMBEDDING = tuple([1.0] + [0.0] * 1023)


class _CaptureCursor:
    def __init__(self) -> None:
        self.captured: tuple[str, list[object]] | None = None

    async def execute(self, sql: str, params: list[object]) -> _CaptureCursor:
        self.captured = (sql, params)
        return self

    async def fetchall(self) -> list[object]:
        return []

    async def __aenter__(self) -> _CaptureCursor:
        return self

    async def __aexit__(self, *args: object) -> bool:
        return False


class _CaptureConnection:
    def __init__(self, cursor: _CaptureCursor) -> None:
        self._cursor = cursor

    def cursor(self) -> _CaptureCursor:
        return self._cursor

    async def __aenter__(self) -> _CaptureConnection:
        return self

    async def __aexit__(self, *args: object) -> bool:
        return False


class _CaptureProvider:
    def __init__(self) -> None:
        self.cursor = _CaptureCursor()

    def connection(self) -> _CaptureConnection:
        return _CaptureConnection(self.cursor)


async def _search(tenant_id: object | None) -> tuple[str, list[object]]:
    provider = _CaptureProvider()
    repository = PgVectorRepository("postgresql://unused", TABLE, 3, 1024, connection_provider=provider)
    scope = DocumentScope(document_id=uuid.uuid4(), index_version="v1")
    await repository.search(EMBEDDING, [scope], 10, tenant_id=tenant_id)
    assert provider.cursor.captured is not None
    return provider.cursor.captured


@pytest.mark.asyncio
async def test_tenant_scoped_search_binds_the_vector_first() -> None:
    sql, params = await _search("tenant-a")

    assert sql.count("%s") == len(params), "占位符数量与绑定参数数量必须一致"
    assert params[0] == vector_literal(EMBEDDING), "第一个 %s 是 SELECT 的相似度向量"
    assert params[0].startswith("["), "参数错位时 PG 会把 UUID 当 vector 解析"
    assert params[1] == "tenant-a", "第二个 %s 才是 WHERE tenant_id"
    assert params[-2] == vector_literal(EMBEDDING)
    assert params[-1] == 10
    assert "tenant_id = %s AND" in sql


@pytest.mark.asyncio
async def test_unscoped_search_keeps_the_same_alignment() -> None:
    sql, params = await _search(None)

    assert sql.count("%s") == len(params)
    assert params[0] == vector_literal(EMBEDDING)
    assert params[-1] == 10
    assert "tenant_id = %s AND" not in sql
