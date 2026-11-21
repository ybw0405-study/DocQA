from __future__ import annotations

import json
from pathlib import Path

import pytest

from docqa_ai.maintenance.backfill_tenant_id import (
    VECTOR_TABLES,
    BackfillError,
    build_plan,
    load_mapping,
    run,
    scan_sql,
    update_sql,
)

TENANT_A = "44444444-4444-4444-8444-444444444444"
TENANT_B = "55555555-5555-4555-8555-555555555555"
DOC_A = "33333333-3333-4333-8333-333333333333"
DOC_B = "66666666-6666-4666-8666-666666666666"


class FakeCursor:
    def __init__(self, connection: FakeConnection) -> None:
        self.connection = connection

    def __enter__(self) -> FakeCursor:
        return self

    def __exit__(self, *exc: object) -> bool:
        return False

    def execute(self, sql: str, params: object = None) -> None:
        self.connection.statements.append((sql, params))
        if sql.startswith("SELECT"):
            for table in VECTOR_TABLES:
                if f"FROM {table} " in sql:
                    self._rows = self.connection.rows_by_table.get(table, [])
                    return
            self._rows = []

    def fetchall(self) -> list[tuple[str, int]]:
        return list(getattr(self, "_rows", []))


class FakeConnection:
    def __init__(self, rows_by_table: dict[str, list[tuple[str, int]]] | None = None) -> None:
        self.rows_by_table = rows_by_table or {}
        self.statements: list[tuple[str, object]] = []
        self.committed = False

    def cursor(self) -> FakeCursor:
        return FakeCursor(self)

    def commit(self) -> None:
        self.committed = True


def test_build_plan_maps_every_document_and_reports_unresolved() -> None:
    plan = build_plan(
        VECTOR_TABLES[0],
        [(DOC_A, 3), (DOC_B, 2)],
        {DOC_A: TENANT_A},
        None,
    )
    assert plan.total_rows == 5
    assert plan.updates == ((TENANT_A, DOC_A, 3),)
    assert plan.unresolved == (DOC_B,)


def test_build_plan_uses_default_tenant_when_mapping_is_missing() -> None:
    plan = build_plan(VECTOR_TABLES[1], [(DOC_B, 1)], {}, TENANT_B)
    assert plan.updates == ((TENANT_B, DOC_B, 1),)
    assert plan.unresolved == ()


def test_update_sql_only_touches_empty_rows_and_is_parameterized() -> None:
    for table in VECTOR_TABLES:
        sql = update_sql(table)
        assert sql.startswith(f"UPDATE {table} ")
        assert "tenant_id = %s" in sql
        assert "document_id = %s" in sql
        assert "tenant_id = ''" in sql
    with pytest.raises(BackfillError):
        update_sql("chunk_embedding_1024; DROP TABLE users")


def test_scan_sql_is_scoped_to_the_empty_marker() -> None:
    for table in VECTOR_TABLES:
        assert "tenant_id = ''" in scan_sql(table)


def test_dry_run_never_writes_or_commits() -> None:
    connection = FakeConnection({VECTOR_TABLES[0]: [(DOC_A, 2)]})
    report = run(connection, {DOC_A: TENANT_A}, None, apply=False)

    assert report["dryRun"] is True
    assert report["appliedUpdates"] == 0
    assert not any(sql.startswith("UPDATE") for sql, _ in connection.statements)
    assert connection.committed is False


def test_apply_refuses_when_any_document_has_no_tenant() -> None:
    connection = FakeConnection({VECTOR_TABLES[0]: [(DOC_A, 1), (DOC_B, 1)]})
    with pytest.raises(BackfillError, match="refusing to apply"):
        run(connection, {DOC_A: TENANT_A}, None, apply=True)
    assert not any(sql.startswith("UPDATE") for sql, _ in connection.statements)
    assert connection.committed is False


def test_apply_covers_both_vector_spaces_in_one_transaction() -> None:
    connection = FakeConnection(
        {
            VECTOR_TABLES[0]: [(DOC_A, 2)],
            VECTOR_TABLES[1]: [(DOC_A, 1), (DOC_B, 1)],
        }
    )
    report = run(connection, {DOC_A: TENANT_A, DOC_B: TENANT_B}, None, apply=True)

    updates = [(sql, params) for sql, params in connection.statements if sql.startswith("UPDATE")]
    assert report["appliedUpdates"] == 3
    assert {sql.split()[1] for sql, _ in updates} == set(VECTOR_TABLES)
    assert all(params and params[0] in {TENANT_A, TENANT_B} for _, params in updates)
    assert connection.committed is True


def test_load_mapping_accepts_both_shapes_and_rejects_blank_tenants(tmp_path: Path) -> None:
    direct = tmp_path / "direct.json"
    direct.write_text(json.dumps({DOC_A: TENANT_A}), encoding="utf-8")
    assert load_mapping(direct) == {DOC_A: TENANT_A}

    wrapped = tmp_path / "wrapped.json"
    wrapped.write_text(json.dumps({"documents": {DOC_B: TENANT_B}}), encoding="utf-8")
    assert load_mapping(wrapped) == {DOC_B: TENANT_B}

    blank = tmp_path / "blank.json"
    blank.write_text(json.dumps({DOC_A: "   "}), encoding="utf-8")
    with pytest.raises(BackfillError, match="blank tenantId"):
        load_mapping(blank)


def test_load_mapping_without_a_file_is_empty() -> None:
    assert load_mapping(None) == {}
