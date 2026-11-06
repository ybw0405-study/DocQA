from __future__ import annotations

import json
import math
import re
import uuid
from collections.abc import Sequence
from dataclasses import dataclass, field
from typing import Any, Protocol

import psycopg

from docqa_ai.rag.repository import vector_literal

VECTOR_NAMESPACE = uuid.UUID("c9810eb7-698d-511a-b56f-d9430325bda9")
_TABLE_NAME = re.compile(r"^[a-z][a-z0-9_]{0,63}$")


class VectorWriteError(Exception):
    pass


def make_vector_id(document_id: uuid.UUID, index_version: str, chunk_index: int) -> uuid.UUID:
    if chunk_index < 0:
        raise ValueError("chunk_index must be non-negative")
    return uuid.uuid5(VECTOR_NAMESPACE, f"{document_id}:{index_version}:{chunk_index}")


@dataclass(frozen=True)
class VectorRecord:
    vector_id: uuid.UUID
    chunk_index: int
    content: str
    embedding: tuple[float, ...]
    metadata: dict[str, Any] = field(default_factory=dict)


class VectorWriter(Protocol):
    async def replace(
        self,
        document_id: uuid.UUID,
        index_version: str,
        records: Sequence[VectorRecord],
    ) -> int: ...

    async def delete(
        self,
        document_id: uuid.UUID,
        index_version: str | None,
    ) -> int: ...


class PgVectorWriter:
    """Transactional replace of one (documentId, indexVersion) scope. Never touches MySQL."""

    def __init__(
        self,
        dsn: str,
        table: str = "chunk_embedding_1024",
        *,
        dimensions: int = 1024,
        connect_timeout_s: int = 3,
    ) -> None:
        if not _TABLE_NAME.fullmatch(table):
            raise ValueError("invalid vector table name")
        self._dsn = dsn
        self._table = table
        self._dimensions = dimensions
        self._connect_timeout_s = connect_timeout_s

    async def replace(
        self,
        document_id: uuid.UUID,
        index_version: str,
        records: Sequence[VectorRecord],
    ) -> int:
        if not records:
            return 0
        for record in records:
            if len(record.embedding) != self._dimensions:
                raise VectorWriteError("embedding dimension mismatch")
            if not all(math.isfinite(value) for value in record.embedding):
                raise VectorWriteError("embedding contains a non-finite value")
        delete_sql = f"DELETE FROM {self._table} WHERE document_id = %s AND index_version = %s"
        insert_sql = (
            f"INSERT INTO {self._table} "
            "(vector_id, document_id, chunk_index, content, embedding, metadata, index_version) "
            "VALUES (%s, %s, %s, %s, %s::vector, %s::jsonb, %s) "
            "ON CONFLICT (vector_id) DO UPDATE SET "
            "document_id = EXCLUDED.document_id, "
            "chunk_index = EXCLUDED.chunk_index, "
            "content = EXCLUDED.content, "
            "embedding = EXCLUDED.embedding, "
            "metadata = EXCLUDED.metadata, "
            "index_version = EXCLUDED.index_version"
        )
        count_sql = f"SELECT COUNT(*) FROM {self._table} WHERE document_id = %s AND index_version = %s"
        try:
            connection = await psycopg.AsyncConnection.connect(
                self._dsn,
                connect_timeout=self._connect_timeout_s,
            )
            try:
                async with connection.transaction():
                    async with connection.cursor() as cursor:
                        await cursor.execute(delete_sql, (document_id, index_version))
                        for record in records:
                            await cursor.execute(
                                insert_sql,
                                (
                                    record.vector_id,
                                    document_id,
                                    record.chunk_index,
                                    record.content,
                                    vector_literal(record.embedding),
                                    json.dumps(record.metadata, ensure_ascii=False, separators=(",", ":")),
                                    index_version,
                                ),
                            )
                        await cursor.execute(count_sql, (document_id, index_version))
                        rows_written = await _read_count(cursor)
            finally:
                await connection.close()
        except psycopg.Error as exc:
            raise VectorWriteError("vector write failed") from exc
        return rows_written

    async def delete(self, document_id: uuid.UUID, index_version: str | None) -> int:
        """Delete one scope and count what is left inside the same transaction.

        ``index_version=None`` means every version of the document; a missing version never widens
        the predicate, the caller rejects that request before it reaches this method.
        """
        if index_version is None:
            predicate = "document_id = %s"
            params: tuple[Any, ...] = (document_id,)
        else:
            predicate = "document_id = %s AND index_version = %s"
            params = (document_id, index_version)
        delete_sql = f"DELETE FROM {self._table} WHERE {predicate}"
        count_sql = f"SELECT COUNT(*) FROM {self._table} WHERE {predicate}"
        try:
            connection = await psycopg.AsyncConnection.connect(
                self._dsn,
                connect_timeout=self._connect_timeout_s,
            )
            try:
                async with connection.transaction():
                    async with connection.cursor() as cursor:
                        await cursor.execute(delete_sql, params)
                        await cursor.execute(count_sql, params)
                        remaining_rows = await _read_count(cursor)
            finally:
                await connection.close()
        except psycopg.Error as exc:
            raise VectorWriteError("vector delete failed") from exc
        return remaining_rows


async def _read_count(cursor: Any) -> int:
    row = await cursor.fetchone()
    if row is None or row[0] is None:
        raise VectorWriteError("count query returned no rows")
    return int(row[0])


class InMemoryVectorWriter:
    """Deterministic writer double; mirrors the replace/delete-per-scope semantics."""

    def __init__(self) -> None:
        self._store: dict[tuple[uuid.UUID, str], tuple[VectorRecord, ...]] = {}
        self.deleted_scopes: list[tuple[uuid.UUID, str | None]] = []

    async def replace(
        self,
        document_id: uuid.UUID,
        index_version: str,
        records: Sequence[VectorRecord],
    ) -> int:
        self._store[(document_id, index_version)] = tuple(records)
        return len(records)

    async def delete(self, document_id: uuid.UUID, index_version: str | None) -> int:
        self.deleted_scopes.append((document_id, index_version))
        if index_version is None:
            for key in [key for key in self._store if key[0] == document_id]:
                del self._store[key]
        else:
            self._store.pop((document_id, index_version), None)
        return 0

    def records(self, document_id: uuid.UUID, index_version: str) -> tuple[VectorRecord, ...]:
        return self._store.get((document_id, index_version), ())
