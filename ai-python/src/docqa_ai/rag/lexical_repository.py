from __future__ import annotations

import re
import uuid
from collections.abc import Sequence
from dataclasses import dataclass, field
from typing import Any, Protocol

import psycopg

from docqa_ai.protocol.models import DocumentScope
from docqa_ai.rag.pg_connection import PgConnectionProvider
from docqa_ai.rag.repository import _TABLE_NAME, ChunkRecord
from docqa_ai.text.lexical import LEXICAL_TSQUERY_SQL, LEXICAL_TSVECTOR_SQL


class LexicalStoreError(Exception):
    pass


class LexicalStoreUnavailable(LexicalStoreError):
    pass


@dataclass(frozen=True)
class LexicalHit:
    """词法命中**没有分数字段**：SQL 的 `SELECT` 列表里没有相似度，也没有 `ts_rank` 列（§4.4.4）。"""

    document_id: uuid.UUID
    chunk_index: int
    content: str
    metadata: dict[str, Any] = field(default_factory=dict)


class LexicalRepository(Protocol):
    async def search(
        self,
        lexical_query: str,
        scopes: Sequence[DocumentScope],
        limit: int,
        tenant_id: object | None = None,
    ) -> list[LexicalHit]: ...


def build_lexical_search_sql(table: str, scope_count: int, *, tenant_scoped: bool = False) -> str:
    """词法通道唯一的检索语句形态。

    两个常量各出现两次：`to_tsvector(...)` 在 `WHERE` 与 `ORDER BY` 的 `ts_rank` 里必须同形，
    `to_tsquery(...)` 在同样两处引用同一个绑定值。理由不是风格——只按 `vector_id` 排时密集命中
    会被规划成不使用 GIN 的主键顺序扫描（E30），而这一形态在零命中、窄授权、密集命中三种分布下
    都稳定走 `BitmapAnd(docver, lexical)`（E31）。授权谓词与向量通道逐字同形，词法通道不单独
    构造范围（D-87）。

    `tenant_scoped=True` 时在 WHERE 最前追加 `tenant_id = %s`，与 `lexical_params` 的同名开关
    必须成对传入，否则占位符与参数错位（由 MAINT-1 的 SQL 契约测试对账）。
    """
    if not _TABLE_NAME.fullmatch(table):
        raise ValueError("invalid lexical table name")
    if scope_count <= 0:
        raise ValueError("scope_count must be positive")
    placeholders = ", ".join("(%s::uuid, %s::text)" for _ in range(scope_count))
    tenant_prefix = "tenant_id = %s AND " if tenant_scoped else ""
    return (
        f"SELECT document_id, chunk_index, content, metadata "
        f"FROM {table} "
        f"WHERE {tenant_prefix}(document_id, index_version) IN ({placeholders}) "
        f"AND {LEXICAL_TSVECTOR_SQL} @@ {LEXICAL_TSQUERY_SQL} "
        f"ORDER BY ts_rank({LEXICAL_TSVECTOR_SQL}, {LEXICAL_TSQUERY_SQL}) DESC, vector_id "
        f"LIMIT %s"
    )


def lexical_params(
    lexical_query: str,
    scopes: Sequence[DocumentScope],
    limit: int,
    *,
    tenant_id: object | None = None,
) -> list[object]:
    """位置参数表：租户 → 授权对 → `WHERE` 的 tsquery → `ORDER BY` 的 tsquery → `LIMIT`。

    两个 tsquery 位是**同一个局部变量**，所以不存在「两次编译产生两份查询」的可能；
    调用方不得为了填第二个占位符再调一次 `build_lexical_query`。
    `tenant_id` 非空时在列表最前插入绑定值，与 `build_lexical_search_sql(tenant_scoped=True)`
    的 `tenant_id = %s` 对齐；为空时保持修复前的形态（本地 dev / 替身测试零改动）。
    """
    values: list[object] = []
    if tenant_id is not None:
        values.append(str(tenant_id))
    for scope in scopes:
        values.extend([str(scope.document_id), scope.index_version])
    values.extend([lexical_query, lexical_query, limit])
    return values


class PgLexicalRepository:
    """Read-only lexical access over the expression GIN index created by migration 002."""

    def __init__(
        self,
        dsn: str,
        table: str = "chunk_embedding_1024",
        connect_timeout_s: int = 3,
        *,
        connection_provider: PgConnectionProvider | None = None,
    ) -> None:
        self._dsn = dsn
        if not _TABLE_NAME.fullmatch(table):
            raise ValueError("invalid lexical table name")
        self._table = table
        self._connect_timeout_s = connect_timeout_s
        self._connections = connection_provider or PgConnectionProvider(dsn, connect_timeout_s)

    async def search(
        self,
        lexical_query: str,
        scopes: Sequence[DocumentScope],
        limit: int,
        tenant_id: object | None = None,
    ) -> list[LexicalHit]:
        if not scopes or limit <= 0:
            return []
        if not lexical_query:
            raise LexicalStoreError("lexical query is empty")
        sql = build_lexical_search_sql(self._table, len(scopes), tenant_scoped=tenant_id is not None)
        params = lexical_params(lexical_query, scopes, limit, tenant_id=tenant_id)
        try:
            async with self._connections.connection() as connection:
                async with connection.cursor() as cursor:
                    await cursor.execute(sql, params)
                    rows = await cursor.fetchall()
        except (psycopg.Error, OSError, TimeoutError) as exc:
            raise LexicalStoreUnavailable("lexical query failed") from exc
        return [
            LexicalHit(
                document_id=row[0],
                chunk_index=int(row[1]),
                content=str(row[2] or ""),
                metadata=dict(row[3] or {}),
            )
            for row in rows
        ]


class UnavailableLexicalRepository:
    """Fail-closed double used when the lexical index is not configured."""

    async def search(
        self,
        lexical_query: str,
        scopes: Sequence[DocumentScope],
        limit: int,
        tenant_id: object | None = None,
    ) -> list[LexicalHit]:
        raise LexicalStoreUnavailable("lexical index is not configured")


_QUOTED_LEXEME = re.compile(r"'([^']*)'")


def phrase_surfaces(lexical_query: str) -> tuple[str, ...]:
    """把已编译的 tsquery 还原成词组原文片段：`'a' <-> 'b'` → `ab`、`'config.py'` → `config.py`。

    引号内不可能再出现引号（`build_lexical_query` 的发出口径，E23），所以按引号对提取是确定的。
    融合层与覆盖度（`lexical-coverage-v1`）都用它，不再各自解析一遍。
    """
    surfaces: list[str] = []
    for phrase in lexical_query.split(" | "):
        lexemes = _QUOTED_LEXEME.findall(phrase)
        if lexemes:
            surfaces.append("".join(lexemes))
    return tuple(surfaces)


class InMemoryLexicalRepository:
    """确定性替身，供服务层逻辑测试使用（D-93）。

    它复刻**授权过滤**与实测过的「相邻二元组 = 精确子串」语义；它**不**复刻 `ts_rank`
    随词频移动的那一半，名次只按命中词组数排。真实计划形态、索引命中与延迟需在一次性
    schema 内取证，不进 CI。
    """

    def __init__(self, chunks: Sequence[ChunkRecord] | None = None) -> None:
        self._chunks = list(chunks or [])

    def add(self, chunk: ChunkRecord) -> None:
        self._chunks.append(chunk)

    async def search(
        self,
        lexical_query: str,
        scopes: Sequence[DocumentScope],
        limit: int,
        tenant_id: object | None = None,
    ) -> list[LexicalHit]:
        if not scopes or limit <= 0:
            return []
        surfaces = phrase_surfaces(lexical_query)
        if not surfaces:
            raise LexicalStoreError("lexical query is empty")
        allowed = {(scope.document_id, scope.index_version) for scope in scopes}
        tenant = str(tenant_id) if tenant_id is not None else None
        ranked: list[tuple[int, LexicalHit]] = []
        for chunk in self._chunks:
            # B3：与向量替身同形修正——真实 SQL 的 tenant_id = %s 不会命中空串行。
            if tenant is not None and (chunk.tenant_id or "") != tenant:
                continue
            version = str(chunk.metadata.get("index_version", ""))
            if (chunk.document_id, version) not in allowed:
                continue
            haystack = chunk.content.lower()
            matched = sum(1 for surface in surfaces if surface in haystack)
            if not matched:
                continue
            ranked.append(
                (
                    matched,
                    LexicalHit(
                        document_id=chunk.document_id,
                        chunk_index=chunk.chunk_index,
                        content=chunk.content,
                        metadata=dict(chunk.metadata),
                    ),
                )
            )
        ranked.sort(key=lambda item: (-item[0], str(item[1].document_id), item[1].chunk_index))
        return [hit for _, hit in ranked[:limit]]


__all__ = [
    "InMemoryLexicalRepository",
    "LexicalHit",
    "LexicalRepository",
    "LexicalStoreError",
    "LexicalStoreUnavailable",
    "PgLexicalRepository",
    "UnavailableLexicalRepository",
    "build_lexical_search_sql",
    "lexical_params",
    "phrase_surfaces",
]
