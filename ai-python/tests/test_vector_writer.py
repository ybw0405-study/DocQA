from __future__ import annotations

import json
import uuid
from collections.abc import Sequence

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
from tests.conftest import DOCUMENT_ID, TENANT_ID

VERSION = "v1"
TENANT = str(TENANT_ID)
OTHER_TENANT = "55555555-5555-4555-8555-555555555555"


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

    async def executemany(self, sql: str, batch: Sequence[tuple[object, ...]]) -> None:
        # 批量写入只产生一次语句记录，参数保留为 list，便于断言整批内容。
        self._statements.append((sql, list(batch)))

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

    rows_written = await PgVectorWriter("postgresql://db/test", dimensions=1024).replace(
        DOCUMENT_ID, VERSION, records, tenant_id=TENANT
    )

    assert rows_written == 2
    assert calls == [("postgresql://db/test", {"connect_timeout": 3})]
    assert connection.closed is True
    statements = connection.statements
    assert statements[0][0].startswith("DELETE FROM chunk_embedding_1024")
    assert statements[0][1] == (DOCUMENT_ID, VERSION, TENANT)
    assert "AND tenant_id = %s" in statements[0][0], "删除范围必须绑定租户"
    assert statements[-1] == ("COMMIT", None)

    inserts = [item for item in statements if str(item[0]).startswith("INSERT INTO")]
    assert len(inserts) == 1, "executemany 应把整批合并为一次网络往返"
    sql, params = inserts[0]
    assert isinstance(params, list) and len(params) == len(records)
    assert "%s::vector" in sql and "%s::jsonb" in sql
    assert "ON CONFLICT (vector_id) DO UPDATE" in sql
    assert "tenant_id" in sql and "user_id" not in sql
    for row, expected in zip(params, records, strict=True):
        assert row == (
            expected.vector_id,
            DOCUMENT_ID,
            expected.chunk_index,
            expected.content,
            vector_writer.vector_literal(expected.embedding),
            json.dumps(expected.metadata, ensure_ascii=False, separators=(",", ":")),
            VERSION,
            TENANT, # J2：写侧落真实租户，读侧 WHERE tenant_id = <真实UUID> 才可能命中
        )


async def test_replace_counts_the_scope_inside_the_same_transaction(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    connection = FakeConnection(count_row=(3,))
    install_connection(monkeypatch, connection)

    await PgVectorWriter("postgresql://db/test", dimensions=1024).replace(
        DOCUMENT_ID, VERSION, [record(0)], tenant_id=TENANT
    )

    statements = connection.statements
    commit_index = next(index for index, item in enumerate(statements) if item[0] == "COMMIT")
    count_index, count = next(
        (index, item) for index, item in enumerate(statements) if str(item[0]).startswith("SELECT COUNT")
    )
    assert count_index < commit_index, "the row count must be read before the transaction commits"
    assert count[0] == (
        "SELECT COUNT(*) FROM chunk_embedding_1024 WHERE document_id = %s AND index_version = %s AND tenant_id = %s"
    )
    assert count[1] == (DOCUMENT_ID, VERSION, TENANT)


async def test_delete_document_scope_covers_every_version(monkeypatch: pytest.MonkeyPatch) -> None:
    connection = FakeConnection(count_row=(0,))
    install_connection(monkeypatch, connection)

    remaining = await PgVectorWriter("postgresql://db/test").delete(DOCUMENT_ID, None, tenant_id=TENANT)

    assert remaining == 0
    delete_sql, delete_params = connection.statements[0]
    assert delete_sql == "DELETE FROM chunk_embedding_1024 WHERE document_id = %s AND tenant_id = %s"
    assert delete_params == (DOCUMENT_ID, TENANT)
    assert "index_version" not in delete_sql
    assert "user_id" not in delete_sql
    count_sql, count_params = next(item for item in connection.statements if str(item[0]).startswith("SELECT COUNT"))
    assert count_sql == "SELECT COUNT(*) FROM chunk_embedding_1024 WHERE document_id = %s AND tenant_id = %s"
    assert count_params == (DOCUMENT_ID, TENANT)
    assert connection.statements[-1] == ("COMMIT", None)
    assert connection.closed is True


async def test_delete_version_scope_is_bound_to_that_version(monkeypatch: pytest.MonkeyPatch) -> None:
    connection = FakeConnection(count_row=(4,))
    install_connection(monkeypatch, connection)

    remaining = await PgVectorWriter("postgresql://db/test").delete(DOCUMENT_ID, "v1", tenant_id=TENANT)

    assert remaining == 4, "a leftover count is reported as-is; Java turns it into DIVERGED"
    delete_sql, delete_params = connection.statements[0]
    assert delete_sql == (
        "DELETE FROM chunk_embedding_1024 WHERE document_id = %s AND index_version = %s AND tenant_id = %s"
    )
    assert delete_params == (DOCUMENT_ID, "v1", TENANT)


async def test_delete_wraps_database_errors(monkeypatch: pytest.MonkeyPatch) -> None:
    install_connection(monkeypatch, psycopg.OperationalError("down"))
    with pytest.raises(VectorWriteError):
        await PgVectorWriter("postgresql://db/test").delete(DOCUMENT_ID, None, tenant_id=TENANT)


async def test_delete_rolls_back_when_the_count_query_fails(monkeypatch: pytest.MonkeyPatch) -> None:
    class BrokenCountCursor(FakeCursor):
        async def fetchone(self) -> tuple[int] | None:
            raise psycopg.InterfaceError("no row")

    class BrokenCountConnection(FakeConnection):
        def cursor(self) -> FakeCursor:
            return BrokenCountCursor(self.statements)

    install_connection(monkeypatch, BrokenCountConnection())
    with pytest.raises(VectorWriteError):
        await PgVectorWriter("postgresql://db/test").delete(DOCUMENT_ID, None, tenant_id=TENANT)


async def test_empty_replace_is_a_noop(monkeypatch: pytest.MonkeyPatch) -> None:
    connection = FakeConnection()
    calls = install_connection(monkeypatch, connection)
    await PgVectorWriter("postgresql://db/test").replace(DOCUMENT_ID, VERSION, [], tenant_id=TENANT)
    assert calls == []
    assert connection.statements == []


async def test_dimension_mismatch_is_rejected_before_connecting(monkeypatch: pytest.MonkeyPatch) -> None:
    calls = install_connection(monkeypatch, FakeConnection())
    with pytest.raises(VectorWriteError):
        await PgVectorWriter("postgresql://db/test", dimensions=1024).replace(
            DOCUMENT_ID, VERSION, [record(0, dimensions=4)], tenant_id=TENANT
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
        await PgVectorWriter("postgresql://db/test", dimensions=1024).replace(
            DOCUMENT_ID, VERSION, [broken], tenant_id=TENANT
        )
    assert calls == []


async def test_database_errors_are_wrapped(monkeypatch: pytest.MonkeyPatch) -> None:
    install_connection(monkeypatch, psycopg.OperationalError("down"))
    with pytest.raises(VectorWriteError):
        await PgVectorWriter("postgresql://db/test", dimensions=1024).replace(
            DOCUMENT_ID, VERSION, [record(0)], tenant_id=TENANT
        )


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
    await writer.replace(DOCUMENT_ID, VERSION, [record(0)], tenant_id=TENANT)
    await writer.replace(
        DOCUMENT_ID,
        "v2",
        [VectorRecord(vector_id=other_version, chunk_index=0, content="v2", embedding=tuple([0.0] * 1024))],
        tenant_id=TENANT,
    )
    assert await writer.replace(DOCUMENT_ID, VERSION, [record(1)], tenant_id=TENANT) == 1
    assert [item.chunk_index for item in writer.records(DOCUMENT_ID, VERSION)] == [1]
    assert [item.content for item in writer.records(DOCUMENT_ID, "v2")] == ["v2"]
    assert writer.records(uuid.uuid4(), VERSION) == ()


async def test_in_memory_writer_deletes_one_version_then_the_whole_document() -> None:
    writer = InMemoryVectorWriter()
    stranger = uuid.uuid4()
    other_version = make_vector_id(DOCUMENT_ID, "v2", 0)
    await writer.replace(DOCUMENT_ID, VERSION, [record(0)], tenant_id=TENANT)
    await writer.replace(
        DOCUMENT_ID,
        "v2",
        [VectorRecord(vector_id=other_version, chunk_index=0, content="v2", embedding=tuple([0.0] * 1024))],
        tenant_id=TENANT,
    )
    await writer.replace(stranger, VERSION, [record(0)], tenant_id=TENANT)

    await writer.delete(DOCUMENT_ID, VERSION, tenant_id=TENANT)
    assert writer.deleted_scopes == [(DOCUMENT_ID, VERSION)]
    assert writer.records(DOCUMENT_ID, VERSION) == ()
    assert [item.content for item in writer.records(DOCUMENT_ID, "v2")] == ["v2"], (
        "one version must not clear the other"
    )

    await writer.delete(DOCUMENT_ID, None, tenant_id=TENANT)
    assert writer.records(DOCUMENT_ID, "v2") == ()
    assert writer.records(stranger, VERSION) != (), "the delete never leaves the requested document"
    assert writer.deleted_scopes[-1] == (DOCUMENT_ID, None)


# ── 004 回归的机检护栏：这些断言在修复前全部会静默通过 ─────────────────────────


async def test_replace_without_tenant_is_rejected_before_connecting(monkeypatch: pytest.MonkeyPatch) -> None:
    calls = install_connection(monkeypatch, FakeConnection())
    with pytest.raises(VectorWriteError, match="tenant_id is required"):
        await PgVectorWriter("postgresql://db/test", dimensions=1024).replace(DOCUMENT_ID, VERSION, [record(0)])
    assert calls == [], "缺租户信封不得产生任何写库动作"


async def test_delete_without_tenant_is_rejected_before_connecting(monkeypatch: pytest.MonkeyPatch) -> None:
    calls = install_connection(monkeypatch, FakeConnection())
    with pytest.raises(VectorWriteError, match="tenant_id is required"):
        await PgVectorWriter("postgresql://db/test").delete(DOCUMENT_ID, None)
    assert calls == []


@pytest.mark.parametrize("blank", [None, "", " "])
async def test_blank_tenant_never_becomes_an_empty_column_value(
    monkeypatch: pytest.MonkeyPatch, blank: str | None
) -> None:
    calls = install_connection(monkeypatch, FakeConnection())
    with pytest.raises(VectorWriteError, match="tenant_id is required"):
        await PgVectorWriter("postgresql://db/test", dimensions=1024).replace(
            DOCUMENT_ID, VERSION, [record(0)], tenant_id=blank
        )
    assert calls == []


async def test_in_memory_writer_isolates_tenants_on_delete() -> None:
    """替身语义必须与真实 PG 一致：A 的删除不能清掉 B 的行。"""
    writer = InMemoryVectorWriter()
    await writer.replace(DOCUMENT_ID, VERSION, [record(0)], tenant_id=TENANT)
    await writer.replace(DOCUMENT_ID, VERSION, [record(1)], tenant_id=OTHER_TENANT)

    await writer.delete(DOCUMENT_ID, None, tenant_id=TENANT)

    assert writer.records_for_tenant(DOCUMENT_ID, VERSION, TENANT) == ()
    assert [item.chunk_index for item in writer.records_for_tenant(DOCUMENT_ID, VERSION, OTHER_TENANT)] == [1]
