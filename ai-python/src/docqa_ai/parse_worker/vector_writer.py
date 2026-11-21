from __future__ import annotations

import json
import math
import re
import uuid
from collections.abc import Sequence
from dataclasses import dataclass, field
from typing import Any, Protocol

from docqa_ai.rag.pg_connection import PgConnectionProvider
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
        tenant_id: object | None = None,
    ) -> int: ...

    async def delete(
        self,
        document_id: uuid.UUID,
        index_version: str | None,
        tenant_id: object | None = None,
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
        connection_provider: PgConnectionProvider | None = None,
    ) -> None:
        if not _TABLE_NAME.fullmatch(table):
            raise ValueError("invalid vector table name")
        self._dsn = dsn
        self._table = table
        self._dimensions = dimensions
        self._connect_timeout_s = connect_timeout_s
        self._connections = connection_provider or PgConnectionProvider(dsn, connect_timeout_s)

    async def replace(
        self,
        document_id: uuid.UUID,
        index_version: str,
        records: Sequence[VectorRecord],
        tenant_id: object | None = None,
    ) -> int:
        if not records:
            return 0
        for record in records:
            if len(record.embedding) != self._dimensions:
                raise VectorWriteError("embedding dimension mismatch")
            if not all(math.isfinite(value) for value in record.embedding):
                raise VectorWriteError("embedding contains a non-finite value")
        tenant_value = str(tenant_id) if tenant_id is not None else ""
        tenant_predicate = " AND tenant_id = %s" if tenant_id is not None else ""
        scope_params: list[Any] = [document_id, index_version]
        if tenant_id is not None:
            scope_params.append(tenant_value)
        delete_sql = f"DELETE FROM {self._table} WHERE document_id = %s AND index_version = %s{tenant_predicate}"
        # 每行都带 tenant_id：读侧 WHERE 的纵深谓词才有可比对的值。
        insert_sql = (
            f"INSERT INTO {self._table} "
            "(vector_id, document_id, chunk_index, content, embedding, metadata, index_version, tenant_id) "
            "VALUES (%s, %s, %s, %s, %s::vector, %s::jsonb, %s, %s) "
            "ON CONFLICT (vector_id) DO UPDATE SET "
            "document_id = EXCLUDED.document_id, "
            "chunk_index = EXCLUDED.chunk_index, "
            "content = EXCLUDED.content, "
            "embedding = EXCLUDED.embedding, "
            "metadata = EXCLUDED.metadata, "
            "index_version = EXCLUDED.index_version, "
            "tenant_id = EXCLUDED.tenant_id"
        )
        # CON-3：一次 executemany 代替逐行 INSERT 的网络往返；参数列表与 INSERT 占位符逐位对齐。
        rows: list[tuple[Any, ...]] = [
            (
                record.vector_id,
                document_id,
                record.chunk_index,
                record.content,
                vector_literal(record.embedding),
                json.dumps(record.metadata, ensure_ascii=False, separators=(",", ":")),
                index_version,
                tenant_value,
            )
            for record in records
        ]
        count_sql = (
            f"SELECT COUNT(*) FROM {self._table} WHERE document_id = %s AND index_version = %s{tenant_predicate}"
        )
        try:
            async with self._connections.connection() as connection:
                async with connection.transaction():
                    async with connection.cursor() as cursor:
                        await cursor.execute(delete_sql, tuple(scope_params))
                        await cursor.executemany(insert_sql, rows)
                        await cursor.execute(count_sql, tuple(scope_params))
                        rows_written = await _read_count(cursor)
        except VectorWriteError:
            raise
        except Exception as exc:  # noqa: BLE001 - driver/pool failures are sanitized below
            raise VectorWriteError("vector write failed") from exc
        return rows_written

    async def delete(
        self,
        document_id: uuid.UUID,
        index_version: str | None,
        tenant_id: object | None = None,
    ) -> int:
        """Delete one scope and count what is left inside the same transaction.

        ``index_version=None`` means every version of the document; a missing version never widens
        the predicate, the caller rejects that request before it reaches this method.
        """
        if index_version is None:
            predicate = "document_id = %s"
            params: list[Any] = [document_id]
        else:
            predicate = "document_id = %s AND index_version = %s"
            params = [document_id, index_version]
        if tenant_id is not None:
            predicate += " AND tenant_id = %s"
            params.append(str(tenant_id))
        delete_sql = f"DELETE FROM {self._table} WHERE {predicate}"
        count_sql = f"SELECT COUNT(*) FROM {self._table} WHERE {predicate}"
        bound = tuple(params)
        try:
            async with self._connections.connection() as connection:
                async with connection.transaction():
                    async with connection.cursor() as cursor:
                        await cursor.execute(delete_sql, bound)
                        await cursor.execute(count_sql, bound)
                        remaining_rows = await _read_count(cursor)
        except VectorWriteError:
            raise
        except Exception as exc:  # noqa: BLE001 - driver/pool failures are sanitized below
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
        tenant_id: object | None = None,
    ) -> int:
        self._store[(document_id, index_version)] = tuple(records)
        return len(records)

    async def delete(
        self,
        document_id: uuid.UUID,
        index_version: str | None,
        tenant_id: object | None = None,
    ) -> int:
        self.deleted_scopes.append((document_id, index_version))
        if index_version is None:
            for key in [key for key in self._store if key[0] == document_id]:
                del self._store[key]
        else:
            self._store.pop((document_id, index_version), None)
        return 0

    def records(self, document_id: uuid.UUID, index_version: str) -> tuple[VectorRecord, ...]:
        return self._store.get((document_id, index_version), ())
