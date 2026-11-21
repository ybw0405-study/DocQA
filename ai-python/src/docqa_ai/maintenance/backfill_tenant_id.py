"""004_add_tenant_id.sql 存量数据回填工具（默认 dry-run，不写库）。

背景：004 给两张向量空间加了 ``tenant_id TEXT NOT NULL DEFAULT ''``，而读侧强制
``WHERE tenant_id = <真实UUID>``。存量行与「修复前写入的行」都是空串，向量与词法
召回恒为 0。本脚本按 documentId → tenantId 映射把这些行改回真实租户。

执行顺序（runbook，顺序错会让新写入直接失败）：
  1. 先部署「写侧强制 tenant_id」的代码，让所有新写入都带真实租户；
  2. 从控制面导出 documentId → tenantId 映射，用默认 dry-run 出报表；
  3. 确认 unresolved 为空后执行 ``--apply``；
  4. 执行 ``migrations/005_drop_tenant_default.sql`` 去掉默认值。

用法：
  python -m docqa_ai.maintenance.backfill_tenant_id --mapping tenants.json
  python -m docqa_ai.maintenance.backfill_tenant_id --mapping tenants.json --apply
  python -m docqa_ai.maintenance.backfill_tenant_id --default-tenant <uuid> --apply

映射文件格式（二选一）：
  {"33333333-...": "44444444-...", ...}
  {"documents": {"33333333-...": "44444444-...", ...}}

DSN 从 ``DOCQA_AI_PG_DSN`` 或 ``--dsn`` 取得。所有 SQL 参数绑定，表名来自固定白名单。
"""
from __future__ import annotations

import argparse
import json
import os
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any

VECTOR_TABLES = ("chunk_embedding_1024", "chunk_embedding_1024_text_embedding_v4")
_EMPTY_TENANT = ""


class BackfillError(Exception):
    pass


@dataclass(frozen=True)
class BackfillPlan:
    table: str
    # (tenant_id, document_id, row_count)
    updates: tuple[tuple[str, str, int], ...]
    unresolved: tuple[str, ...]
    total_rows: int


def load_mapping(path: Path | None) -> dict[str, str]:
    if path is None:
        return {}
    raw = json.loads(path.read_text(encoding="utf-8"))
    if isinstance(raw, dict) and "documents" in raw:
        raw = raw["documents"]
    if not isinstance(raw, dict):
        raise BackfillError("mapping must be a JSON object of documentId -> tenantId")
    mapping: dict[str, str] = {}
    for document_id, tenant_id in raw.items():
        tenant = str(tenant_id).strip()
        if not tenant:
            raise BackfillError(f"blank tenantId for documentId={document_id}")
        mapping[str(document_id)] = tenant
    return mapping


def update_sql(table: str) -> str:
    """只改空串行：已回填的值不会被覆盖，重复执行天然幂等。"""
    if table not in VECTOR_TABLES:
        raise BackfillError(f"unexpected table: {table}")
    return f"UPDATE {table} SET tenant_id = %s WHERE document_id = %s AND tenant_id = ''"


def scan_sql(table: str) -> str:
    if table not in VECTOR_TABLES:
        raise BackfillError(f"unexpected table: {table}")
    return (
        f"SELECT document_id, COUNT(*) FROM {table} "
        "WHERE tenant_id = '' GROUP BY document_id ORDER BY document_id"
    )


def build_plan(
    table: str,
    rows: list[tuple[str, int]],
    mapping: dict[str, str],
    default_tenant: str | None,
) -> BackfillPlan:
    updates: list[tuple[str, str, int]] = []
    unresolved: list[str] = []
    total = 0
    for document_id, count in rows:
        count = int(count)
        total += count
        tenant = (mapping.get(str(document_id)) or default_tenant or "").strip()
        if not tenant:
            unresolved.append(str(document_id))
            continue
        updates.append((tenant, str(document_id), count))
    return BackfillPlan(table, tuple(updates), tuple(unresolved), total)


def collect_plans(
    connection: Any,
    mapping: dict[str, str],
    default_tenant: str | None,
) -> list[BackfillPlan]:
    plans: list[BackfillPlan] = []
    for table in VECTOR_TABLES:
        with connection.cursor() as cursor:
            cursor.execute(scan_sql(table))
            rows = [(str(row[0]), int(row[1])) for row in cursor.fetchall()]
        plans.append(build_plan(table, rows, mapping, default_tenant))
    return plans


def run(
    connection: Any,
    mapping: dict[str, str],
    default_tenant: str | None,
    *,
    apply: bool,
) -> dict[str, Any]:
    plans = collect_plans(connection, mapping, default_tenant)
    unresolved = sorted({doc for plan in plans for doc in plan.unresolved})
    report: dict[str, Any] = {
        "dryRun": not apply,
        "tables": [
            {
                "table": plan.table,
                "emptyTenantRows": plan.total_rows,
                "documentsToUpdate": len(plan.updates),
                "unresolvedDocuments": len(plan.unresolved),
            }
            for plan in plans
        ],
        "unresolvedDocuments": unresolved,
        "appliedUpdates": 0,
    }
    if not apply:
        return report
    if unresolved:
        raise BackfillError(
            f"refusing to apply: {len(unresolved)} document(s) have no tenant mapping; "
            "supply --mapping or --default-tenant"
        )
    applied = 0
    with connection.cursor() as cursor:
        for plan in plans:
            for tenant, document_id, _count in plan.updates:
                cursor.execute(update_sql(plan.table), (tenant, document_id))
                applied += 1
    connection.commit()
    report["appliedUpdates"] = applied
    return report


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Backfill tenant_id on the two vector spaces")
    parser.add_argument("--mapping", type=Path, help="JSON file: documentId -> tenantId")
    parser.add_argument("--default-tenant", help="Fallback tenant id for unmapped documents (single-tenant deploys)")
    parser.add_argument("--dsn", default=os.getenv("DOCQA_AI_PG_DSN", ""), help="PostgreSQL DSN")
    parser.add_argument("--apply", action="store_true", help="Actually write; default is a dry run")
    args = parser.parse_args(argv)

    try:
        mapping = load_mapping(args.mapping)
        if not args.dsn:
            raise BackfillError("set DOCQA_AI_PG_DSN or pass --dsn")
        if not mapping and not args.default_tenant:
            raise BackfillError("nothing to do: supply --mapping or --default-tenant")
        import psycopg

        with psycopg.connect(args.dsn) as connection:
            report = run(connection, mapping, args.default_tenant, apply=args.apply)
    except (BackfillError, OSError, ValueError, json.JSONDecodeError) as exc:
        print(f"backfill aborted: {exc}", file=sys.stderr)
        return 2
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
