from __future__ import annotations

import json
import uuid

import psycopg
import pytest

from docqa_ai.parse_worker import vector_writer
from docqa_ai.parse_worker.vector_writer import (
    InMemoryVectorWriter,
    PgVectorWriter,
    VectorRecord,
    VectorWriteError,
    make_vector_id,
)
from tests.conftest import DOCUMENT_ID

VERSION = "v1"


def record(index: int, *, dimensions: int = 1024) -> VectorRecord:
    embedding = [0.0] * dimensions
    embedding[index % dimensions] = 1.0
    return VectorRecord(
        vector_id=make_vector_id(DOCUMENT_ID, VERSION, index),
        chunk_index=index,
        content=f"chunk {index}",
        embedding=tuple(embedding),
        metadata={"page_no": index + 1, "token_count": 3, "content_hash": "a" * 64},
    )


class FakeCursor:
    def __init__(self, statements: list[tuple[object, object]], count_row: tuple[int] = (7,)) -> None:
        self._statements = statements
        self._count_row = count_row

    async def __aenter__(self) -> FakeCursor:
        return self

    async def __aexit__(self, *exc: object) -> bool:
        return False

    async def execute(self, sql: str, params: object) -> None:
        self._statements.append((sql, params))

    async def fetchone(self) -> tuple[int] | None:
        return self._count_row


class FakeTransaction:
    def __init__(self, statements: list[tuple[object, object]]) -> None:
        self._statements = statements

    async def __aenter__(self) -> FakeTransaction:
        return self

    async def __aexit__(self, exc_type: object, exc: object, tb: object) -> bool:
        self._statements.append(("COMMIT" if exc_type is None else "ROLLBACK", None))
        return False


class FakeConnection:
    def __init__(self, count_row: tuple[int] = (7,)) -> None:
        self.statements: list[tuple[object, object]] = []
        self.closed = False
        self._count_row = count_row

    def cursor(self) -> FakeCursor:
        return FakeCursor(self.statements, self._count_row)

    def transaction(self) -> FakeTransaction:
        return FakeTransaction(self.statements)

    async def close(self) -> None:
        self.closed = True


def install_connection(monkeypatch: pytest.MonkeyPatch, connection: object) -> list[tuple[object, object]]:
    calls: list[tuple[object, object]] = []

    async def fake_connect(dsn: str, **kwargs: object) -> object:
        calls.append((dsn, kwargs))
        if isinstance(connection, Exception):
            raise connection
        return connection

    monkeypatch.setattr(psycopg.AsyncConnection, "connect", staticmethod(fake_connect))
    return calls


async def test_replace_deletes_scope_then_inserts_parameterized_rows(monkeypatch: pytest.MonkeyPatch) -> None:
    connection = FakeConnection(count_row=(2,))
    calls = install_connection(monkeypatch, connection)
    records = [record(0), record(1)]

    rows_written = await PgVectorWriter("postgresql://db/test", dimensions=1024).replace(DOCUMENT_ID, VERSION, records)

    assert rows_written == 2
    assert calls == [("postgresql://db/test", {"connect_timeout": 3})]
    assert connection.closed is True
    statements = connection.statements
    assert statements[0][0].startswith("DELETE FROM chunk_embedding_1024")
    assert statements[0][1] == (DOCUMENT_ID, VERSION)
    assert statements[-1] == ("COMMIT", None)

    inserts = [item for item in statements if str(item[0]).startswith("INSERT INTO")]
    assert len(inserts) == 2
    for insert, expected in zip(inserts, records, strict=True):
        sql, params = insert
        assert "%s::vector" in sql
        assert "%s::jsonb" in sql
        assert "ON CONFLICT (vector_id) DO UPDATE" in sql
        assert "user_id" not in sql
        assert params == (
            expected.vector_id,
            DOCUMENT_ID,
            expected.chunk_index,
            expected.content,
            vector_writer.vector_literal(expected.embedding),
            json.dumps(expected.metadata, ensure_ascii=False, separators=(",", ":")),
            VERSION,
        )


async def test_replace_counts_the_scope_inside_the_same_transaction(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    connection = FakeConnection(count_row=(3,))
    install_connection(monkeypatch, connection)

    await PgVectorWriter("postgresql://db/test", dimensions=1024).replace(DOCUMENT_ID, VERSION, [record(0)])

    statements = connection.statements
    commit_index = next(index for index, item in enumerate(statements) if item[0] == "COMMIT")
    count_index, count = next(
        (index, item) for index, item in enumerate(statements) if str(item[0]).startswith("SELECT COUNT")
    )
    assert count_index < commit_index, "the row count must be read before the transaction commits"
    assert count[0] == "SELECT COUNT(*) FROM chunk_embedding_1024 WHERE document_id = %s AND index_version = %s"
    assert count[1] == (DOCUMENT_ID, VERSION)


async def test_delete_document_scope_covers_every_version(monkeypatch: pytest.MonkeyPatch) -> None:
    connection = FakeConnection(count_row=(0,))
    install_connection(monkeypatch, connection)

    remaining = await PgVectorWriter("postgresql://db/test").delete(DOCUMENT_ID, None)

    assert remaining == 0
    delete_sql, delete_params = connection.statements[0]
    assert delete_sql == "DELETE FROM chunk_embedding_1024 WHERE document_id = %s"
    assert delete_params == (DOCUMENT_ID,)
    assert "index_version" not in delete_sql
    assert "user_id" not in delete_sql
    count_sql, count_params = next(item for item in connection.statements if str(item[0]).startswith("SELECT COUNT"))
    assert count_sql == "SELECT COUNT(*) FROM chunk_embedding_1024 WHERE document_id = %s"
    assert count_params == (DOCUMENT_ID,)
    assert connection.statements[-1] == ("COMMIT", None)
    assert connection.closed is True


async def test_delete_version_scope_is_bound_to_that_version(monkeypatch: pytest.MonkeyPatch) -> None:
    connection = FakeConnection(count_row=(4,))
    install_connection(monkeypatch, connection)

    remaining = await PgVectorWriter("postgresql://db/test").delete(DOCUMENT_ID, "v1")

    assert remaining == 4, "a leftover count is reported as-is; Java turns it into DIVERGED"
    delete_sql, delete_params = connection.statements[0]
    assert delete_sql == "DELETE FROM chunk_embedding_1024 WHERE document_id = %s AND index_version = %s"
    assert delete_params == (DOCUMENT_ID, "v1")


async def test_delete_wraps_database_errors(monkeypatch: pytest.MonkeyPatch) -> None:
    install_connection(monkeypatch, psycopg.OperationalError("down"))
    with pytest.raises(VectorWriteError):
        await PgVectorWriter("postgresql://db/test").delete(DOCUMENT_ID, None)


async def test_delete_rolls_back_when_the_count_query_fails(monkeypatch: pytest.MonkeyPatch) -> None:
    class BrokenCountCursor(FakeCursor):
        async def fetchone(self) -> tuple[int] | None:
            raise psycopg.InterfaceError("no row")

    class BrokenCountConnection(FakeConnection):
        def cursor(self) -> FakeCursor:
            return BrokenCountCursor(self.statements)

    install_connection(monkeypatch, BrokenCountConnection())
    with pytest.raises(VectorWriteError):
        await PgVectorWriter("postgresql://db/test").delete(DOCUMENT_ID, None)


async def test_empty_replace_is_a_noop(monkeypatch: pytest.MonkeyPatch) -> None:
    connection = FakeConnection()
    calls = install_connection(monkeypatch, connection)
    await PgVectorWriter("postgresql://db/test").replace(DOCUMENT_ID, VERSION, [])
    assert calls == []
    assert connection.statements == []


async def test_dimension_mismatch_is_rejected_before_connecting(monkeypatch: pytest.MonkeyPatch) -> None:
    calls = install_connection(monkeypatch, FakeConnection())
    with pytest.raises(VectorWriteError):
        await PgVectorWriter("postgresql://db/test", dimensions=1024).replace(
            DOCUMENT_ID, VERSION, [record(0, dimensions=4)]
        )
    assert calls == []


async def test_non_finite_embedding_is_rejected(monkeypatch: pytest.MonkeyPatch) -> None:
    calls = install_connection(monkeypatch, FakeConnection())
    broken = VectorRecord(
        vector_id=uuid.uuid4(),
        chunk_index=0,
        content="x",
        embedding=tuple([float("nan")] + [0.0] * 1023),
    )
    with pytest.raises(VectorWriteError):
        await PgVectorWriter("postgresql://db/test", dimensions=1024).replace(DOCUMENT_ID, VERSION, [broken])
    assert calls == []


async def test_database_errors_are_wrapped(monkeypatch: pytest.MonkeyPatch) -> None:
    install_connection(monkeypatch, psycopg.OperationalError("down"))
    with pytest.raises(VectorWriteError):
        await PgVectorWriter("postgresql://db/test", dimensions=1024).replace(DOCUMENT_ID, VERSION, [record(0)])


def test_table_name_is_validated() -> None:
    for table in ("Chunk_Embedding", "chunk;DROP", "1chunk", "chunk-embedding"):
        with pytest.raises(ValueError):
            PgVectorWriter("postgresql://db/test", table)


def test_vector_ids_are_deterministic_and_scoped() -> None:
    first = make_vector_id(DOCUMENT_ID, VERSION, 0)
    assert first == make_vector_id(DOCUMENT_ID, VERSION, 0)
    assert first != make_vector_id(DOCUMENT_ID, VERSION, 1)
    assert first != make_vector_id(DOCUMENT_ID, "v2", 0)
    assert first != make_vector_id(uuid.uuid4(), VERSION, 0)
    with pytest.raises(ValueError):
        make_vector_id(DOCUMENT_ID, VERSION, -1)


async def test_in_memory_writer_replaces_only_the_target_scope() -> None:
    writer = InMemoryVectorWriter()
    other_version = make_vector_id(DOCUMENT_ID, "v2", 0)
    await writer.replace(
        DOCUMENT_ID,
        VERSION,
        [record(0)],
    )
    await writer.replace(
        DOCUMENT_ID,
        "v2",
        [VectorRecord(vector_id=other_version, chunk_index=0, content="v2", embedding=tuple([0.0] * 1024))],
    )
    assert await writer.replace(DOCUMENT_ID, VERSION, [record(1)]) == 1
    assert [item.chunk_index for item in writer.records(DOCUMENT_ID, VERSION)] == [1]
    assert [item.content for item in writer.records(DOCUMENT_ID, "v2")] == ["v2"]
    assert writer.records(uuid.uuid4(), VERSION) == ()


async def test_in_memory_writer_deletes_one_version_then_the_whole_document() -> None:
    writer = InMemoryVectorWriter()
    stranger = uuid.uuid4()
    other_version = make_vector_id(DOCUMENT_ID, "v2", 0)
    await writer.replace(DOCUMENT_ID, VERSION, [record(0)])
    await writer.replace(
        DOCUMENT_ID,
        "v2",
        [VectorRecord(vector_id=other_version, chunk_index=0, content="v2", embedding=tuple([0.0] * 1024))],
    )
    await writer.replace(stranger, VERSION, [record(0)])

    await writer.delete(DOCUMENT_ID, VERSION)
    assert writer.deleted_scopes == [(DOCUMENT_ID, VERSION)]
    assert writer.records(DOCUMENT_ID, VERSION) == ()
    assert [item.content for item in writer.records(DOCUMENT_ID, "v2")] == ["v2"], (
        "one version must not clear the other"
    )

    await writer.delete(DOCUMENT_ID, None)
    assert writer.records(DOCUMENT_ID, "v2") == ()
    assert writer.records(stranger, VERSION) != (), "the delete never leaves the requested document"
    assert writer.deleted_scopes[-1] == (DOCUMENT_ID, None)
