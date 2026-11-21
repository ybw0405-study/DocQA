from __future__ import annotations

import math
import re
import uuid
from collections.abc import Sequence
from dataclasses import dataclass, field
from typing import Any, Protocol

import psycopg

from docqa_ai.protocol.models import DocumentScope
from docqa_ai.rag.pg_connection import PgConnectionProvider

_TABLE_NAME = re.compile(r"^[a-z][a-z0-9_]{0,63}$")


class VectorStoreError(Exception):
    pass


class VectorStoreUnavailable(VectorStoreError):
    pass


@dataclass(frozen=True)
class ChunkRecord:
    vector_id: uuid.UUID
    document_id: uuid.UUID
    chunk_index: int
    content: str
    embedding: tuple[float, ...]
    metadata: dict[str, Any] = field(default_factory=dict)
    # 空串表示「未标注租户」的存量/替身行：真实写入路径会带上 str(tenant_id)。
    tenant_id: str = ""


@dataclass(frozen=True)
class VectorHit:
    document_id: uuid.UUID
    chunk_index: int
    content: str
    score: float
    metadata: dict[str, Any] = field(default_factory=dict)


class VectorRepository(Protocol):
    async def search(
        self,
        embedding: Sequence[float],
        scopes: Sequence[DocumentScope],
        top_k: int,
        tenant_id: object | None = None,
    ) -> list[VectorHit]: ...


def vector_literal(embedding: Sequence[float]) -> str:
    values: list[str] = []
    for value in embedding:
        as_float = float(value)
        if not math.isfinite(as_float):
            raise VectorStoreError("embedding contains a non-finite value")
        values.append(repr(as_float))
    return "[" + ",".join(values) + "]"


def bounded_score(value: float) -> float:
    score = float(value)
    if not math.isfinite(score):
        # pgvector 的 <=> 在任一侧模长为 0 时返回 NaN，而 min/max 会把 NaN 静默当成 1.0，
        # 那等于凭空伪造「完美相似度」并绕过置信度门禁，因此非有限分数必须 fail-closed。
        raise VectorStoreError("vector score is not finite")
    return max(0.0, min(1.0, score))


class PgVectorRepository:
    """Read-only PGVector access. The table name is validated by Settings and never user supplied."""

    def __init__(
        self,
        dsn: str,
        table: str = "chunk_embedding_1024",
        connect_timeout_s: int = 3,
        dimensions: int = 1024,
        *,
        connection_provider: PgConnectionProvider | None = None,
    ) -> None:
        self._dsn = dsn
        if not _TABLE_NAME.fullmatch(table):
            raise ValueError("invalid vector table name")
        self._table = table
        self._connect_timeout_s = connect_timeout_s
        self._dimensions = dimensions
        self._connections = connection_provider or PgConnectionProvider(dsn, connect_timeout_s)

    async def search(
        self,
        embedding: Sequence[float],
        scopes: Sequence[DocumentScope],
        top_k: int,
        tenant_id: object | None = None,
    ) -> list[VectorHit]:
        if len(embedding) != self._dimensions:
            raise VectorStoreError("embedding dimension mismatch")
        if not any(value != 0.0 for value in embedding):
            # 余弦相似度在任一侧模长为 0 时未定义，pgvector 0.8.7 实测返回 NaN，必须在查询前 fail-closed
            raise VectorStoreError("embedding has zero magnitude")
        if not scopes or top_k <= 0:
            return []
        vector = vector_literal(embedding)
        tenant_value = str(tenant_id) if tenant_id is not None else None
        # 纵深防御：租户谓词与授权 IN 谓词同时成立，不能只靠 document scope。
        # 谓词置于 WHERE 最前，绑定参数也置于列表最前，占位符与参数逐位对齐（MAINT-1 对账）。
        tenant_prefix = "tenant_id = %s AND " if tenant_value is not None else ""
        placeholders = ", ".join("(%s::uuid, %s::text)" for _ in scopes)
        params: list[object] = [vector] if tenant_value is None else [tenant_value, vector]
        for scope in scopes:
            params.extend([str(scope.document_id), scope.index_version])
        params.extend([vector, top_k])
        sql = (
            f"SELECT document_id, chunk_index, content, metadata, "
            f"1 - (embedding <=> %s::vector) AS score "
            f"FROM {self._table} "
            f"WHERE {tenant_prefix}(document_id, index_version) IN ({placeholders}) "
            f"ORDER BY embedding <=> %s::vector "
            f"LIMIT %s"
        )
        try:
            async with self._connections.connection() as connection:
                async with connection.cursor() as cursor:
                    await cursor.execute(sql, params)
                    rows = await cursor.fetchall()
        except (psycopg.Error, OSError, TimeoutError) as exc:
            raise VectorStoreUnavailable("vector query failed") from exc
        return [
            VectorHit(
                document_id=row[0],
                chunk_index=int(row[1]),
                content=str(row[2] or ""),
                score=bounded_score(row[4]),
                metadata=dict(row[3] or {}),
            )
            for row in rows
        ]


def _dot(left: Sequence[float], right: Sequence[float]) -> float:
    return sum(a * b for a, b in zip(left, right, strict=False))


class UnavailableVectorRepository:
    """Fail-closed repository used when PGVector is not configured."""

    async def search(
        self,
        embedding: Sequence[float],
        scopes: Sequence[DocumentScope],
        top_k: int,
        tenant_id: object | None = None,
    ) -> list[VectorHit]:
        raise VectorStoreUnavailable("PGVector is not configured")


class InMemoryVectorRepository:
    """Deterministic test double; it keeps the same authorization filter semantics."""

    def __init__(self, chunks: Sequence[ChunkRecord] | None = None) -> None:
        self._chunks = list(chunks or [])

    def add(self, chunk: ChunkRecord) -> None:
        self._chunks.append(chunk)

    async def search(
        self,
        embedding: Sequence[float],
        scopes: Sequence[DocumentScope],
        top_k: int,
        tenant_id: object | None = None,
    ) -> list[VectorHit]:
        allowed = {(scope.document_id, scope.index_version) for scope in scopes}
        tenant = str(tenant_id) if tenant_id is not None else None
        scored: list[tuple[float, ChunkRecord]] = []
        for chunk in self._chunks:
            # 已标注租户的块必须匹配；未标注（空串）的存量/替身行不参与租户过滤。
            if tenant is not None and chunk.tenant_id and chunk.tenant_id != tenant:
                continue
            version = str(chunk.metadata.get("index_version", ""))
            if (chunk.document_id, version) not in allowed:
                continue
            score = _dot(embedding, chunk.embedding)
            scored.append((bounded_score(score), chunk))
        scored.sort(key=lambda item: (-item[0], str(item[1].document_id), item[1].chunk_index))
        return [
            VectorHit(
                document_id=chunk.document_id,
                chunk_index=chunk.chunk_index,
                content=chunk.content,
                score=score,
                metadata=dict(chunk.metadata),
            )
            for score, chunk in scored[:top_k]
        ]
