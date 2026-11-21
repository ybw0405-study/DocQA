from __future__ import annotations

import re
from pathlib import Path

from docqa_ai.config import OFFLINE_VECTOR_TABLE
from docqa_ai.rag.repository import _TABLE_NAME
from docqa_ai.text.lexical import CJK_CHAR_CLASS, LEXICAL_TSVECTOR_SQL

MIGRATIONS = Path(__file__).resolve().parents[1] / "migrations"
VECTOR_MIGRATION = "001_chunk_embedding_1024.sql"
LEXICAL_MIGRATION = "002_chunk_lexical_index.sql"
REAL_MODEL_MIGRATION = "003_chunk_embedding_1024_text_embedding_v4.sql"
TENANT_MIGRATION = "004_add_tenant_id.sql"
FINALIZE_MIGRATION = "005_drop_tenant_default.sql"
OFFLINE_TABLE = "chunk_embedding_1024"
REAL_MODEL_TABLE = "chunk_embedding_1024_text_embedding_v4"

CREATE_INDEX = re.compile(r"create index if not exists ([a-z0-9_]+)")
GIN_EXPRESSION = re.compile(r"using gin \((.*?)\);", re.S)


def flatten(text: str) -> str:
    return re.sub(r"\s+", " ", text).strip()


def migration_sql(name: str) -> str:
    return (MIGRATIONS / name).read_text(encoding="utf-8").lower()


def executable_sql(name: str) -> str:
    """只保留可执行语句：迁移头部的中文措辞不该被当成 DDL 行为来断言。"""

    lines = (MIGRATIONS / name).read_text(encoding="utf-8").splitlines()
    return "\n".join(line.split("--", 1)[0] for line in lines).lower()


def ddl_text(name: str) -> str:
    """再去掉单引号字面量：`COMMENT ON ... IS '...'` 里提到旧表名不算这张迁移**指向**旧表。"""

    return re.sub(r"'[^']*'", "''", executable_sql(name))


def table_columns(sql: str, table: str) -> list[str]:
    block = sql.split(f"create table if not exists {table} (", 1)[1].split("\n);", 1)[0]
    columns: list[str] = []
    for raw_line in block.splitlines():
        line = raw_line.strip().rstrip(",")
        if not line or line.startswith("unique "):
            continue
        columns.append(line.split()[0])
    return columns


def test_gate_discovers_every_migration_file() -> None:
    assert sorted(path.name for path in MIGRATIONS.glob("*.sql")) == [
        VECTOR_MIGRATION,
        LEXICAL_MIGRATION,
        REAL_MODEL_MIGRATION,
        TENANT_MIGRATION,
        FINALIZE_MIGRATION,
    ]


def test_no_migration_scopes_by_user_id() -> None:
    for path in sorted(MIGRATIONS.glob("*.sql")):
        sql = path.read_text(encoding="utf-8").lower()
        assert "user_id" not in sql, path.name


def test_schema_migrations_declare_the_scope_columns() -> None:
    """建表/租户索引迁移必须同时带 document_id 与 index_version；005 只改列默认值，不在此列。"""
    for name in (VECTOR_MIGRATION, REAL_MODEL_MIGRATION, TENANT_MIGRATION):
        sql = migration_sql(name)
        assert "document_id" in sql, name
        assert "index_version" in sql, name


# ── 004/005 回归护栏：此前 004 只有「文件名存在」断言，索引与默认值完全没纳入门禁 ────


def test_tenant_migration_covers_both_spaces_in_query_order() -> None:
    sql = migration_sql(TENANT_MIGRATION)
    for table in (OFFLINE_TABLE, REAL_MODEL_TABLE):
        assert f"alter table {table}" in sql
        assert "add column if not exists tenant_id text not null default ''" in sql
    # 索引列序必须与读侧 WHERE 的列序一致，否则 tenant 前缀索引会用不上。
    assert "on chunk_embedding_1024 (tenant_id, document_id, index_version);" in sql
    assert "on chunk_embedding_1024_text_embedding_v4 (tenant_id, document_id, index_version);" in sql


def test_read_side_binds_tenant_before_the_document_scope() -> None:
    source = (MIGRATIONS.parent / "src/docqa_ai/rag/repository.py").read_text(encoding="utf-8")
    assert 'tenant_prefix = "tenant_id = %s AND "' in source
    assert "WHERE {tenant_prefix}(document_id, index_version) IN" in source


def test_write_side_never_defaults_tenant_to_an_empty_string() -> None:
    source = (MIGRATIONS.parent / "src/docqa_ai/parse_worker/vector_writer.py").read_text(encoding="utf-8")
    assert "_tenant_or_raise(tenant_id)" in source
    assert 'raise VectorWriteError("tenant_id is required for every vector write")' in source


def test_finalize_migration_drops_the_empty_default_on_both_tables() -> None:
    sql = migration_sql(FINALIZE_MIGRATION)
    assert sql.count("alter column tenant_id drop default") == 2
    for table in (OFFLINE_TABLE, REAL_MODEL_TABLE):
        assert f"alter table {table}" in sql


def test_all_migration_index_names_are_unique_including_tenant_indexes() -> None:
    """004 新增的两个索引此前完全不在唯一性对账里。"""
    by_file = {
        name: CREATE_INDEX.findall(executable_sql(name))
        for name in (VECTOR_MIGRATION, LEXICAL_MIGRATION, REAL_MODEL_MIGRATION, TENANT_MIGRATION)
    }
    every = [index for names in by_file.values() for index in names]
    assert len(by_file[TENANT_MIGRATION]) == 2
    # 001 三条 + 002 一条 + 003 四条 + 004 两条 = 10，且十个名字互不重复。
    assert len(every) == len(set(every)) == 10


def test_vector_column_and_table_match_the_shared_contract() -> None:
    sql = migration_sql(VECTOR_MIGRATION)
    assert "create extension if not exists vector;" in sql
    assert "create table if not exists chunk_embedding_1024" in sql
    assert "vector_id      uuid primary key" in sql or "vector_id uuid primary key" in sql
    assert "embedding      vector(1024) not null" in sql or "embedding vector(1024) not null" in sql
    assert "unique (document_id, index_version, chunk_index)" in sql
    assert "chunk_index    int not null check (chunk_index >= 0)" in sql


def test_migration_has_hnsw_cosine_index_with_required_parameters() -> None:
    sql = migration_sql(VECTOR_MIGRATION)
    assert "using hnsw (embedding vector_cosine_ops)" in sql
    assert "m = 16" in sql
    assert "ef_construction = 64" in sql


def test_migration_metadata_documents_required_fields() -> None:
    sql = migration_sql(VECTOR_MIGRATION)
    for field in ("page_no", "token_count", "content_hash", "embedding_model", "file_name"):
        assert field in sql


def test_lexical_migration_adds_only_the_expression_gin_index() -> None:
    sql = migration_sql(LEXICAL_MIGRATION)
    assert "create index if not exists idx_chunk_embedding_lexical" in sql
    assert "using gin (" in sql
    assert "on chunk_embedding_1024" in sql
    for forbidden in ("create table", "add column", "generated always", "create extension", "drop "):
        assert forbidden not in sql, forbidden


def test_lexical_index_expression_matches_the_query_side_constant_verbatim() -> None:
    sql = flatten(migration_sql(LEXICAL_MIGRATION))
    assert flatten(LEXICAL_TSVECTOR_SQL) in sql
    assert f"([{CJK_CHAR_CLASS}])" in sql


def test_lexical_character_class_covers_only_the_basic_cjk_block() -> None:
    assert CJK_CHAR_CLASS == "一-鿿"
    assert len(CJK_CHAR_CLASS) == 3


def test_real_model_migration_targets_only_the_new_table() -> None:
    ddl = ddl_text(REAL_MODEL_MIGRATION)
    assert f"create table if not exists {REAL_MODEL_TABLE}" in ddl
    # offline 表名只允许出现在注释与 COMMENT 文案里；出现在语句里就是本迁移动了旧表。
    assert re.search(rf"{OFFLINE_TABLE}(?![_a-z0-9])", ddl) is None
    for forbidden in ("insert ", "update ", "delete ", "alter ", "truncate", "drop "):
        assert forbidden not in ddl, forbidden


def test_real_model_table_columns_match_the_offline_table_exactly() -> None:
    """两条通道共用同一份 SQL 的前提是列集合与顺序逐字相同（现状 6），否则「零 SQL 改动」不成立。"""

    expected = [
        "vector_id",
        "document_id",
        "chunk_index",
        "content",
        "embedding",
        "metadata",
        "index_version",
        "created_at",
    ]
    assert table_columns(executable_sql(VECTOR_MIGRATION), OFFLINE_TABLE) == expected
    assert table_columns(executable_sql(REAL_MODEL_MIGRATION), REAL_MODEL_TABLE) == expected
    assert "unique (document_id, index_version, chunk_index)" in executable_sql(REAL_MODEL_MIGRATION)


def test_real_model_migration_carries_both_recall_channels() -> None:
    """缺任何一条通道都是静默能力缺失：向量与词法必须同时在新空间里可用。"""

    executable = executable_sql(REAL_MODEL_MIGRATION)
    assert len(CREATE_INDEX.findall(executable)) == 4
    assert "using hnsw (embedding vector_cosine_ops)" in executable
    assert "m = 16" in executable
    assert "ef_construction = 64" in executable
    assert len(GIN_EXPRESSION.findall(executable)) == 1


def test_real_model_index_names_are_unique_across_the_schema() -> None:
    """PG 索引名在 schema 内全局唯一，所以分表的四条索引必须全部新命名（D-99 代价条目）。"""

    by_file = {
        name: CREATE_INDEX.findall(executable_sql(name))
        for name in (VECTOR_MIGRATION, LEXICAL_MIGRATION, REAL_MODEL_MIGRATION)
    }
    every = [index for names in by_file.values() for index in names]
    assert len(by_file[REAL_MODEL_MIGRATION]) == 4
    # 计数谓词写死：001 三条 + 002 一条 + 003 四条 = 8，且八个名字互不重复。
    assert len(every) == len(set(every)) == 8


def test_real_model_lexical_index_is_verbatim_identical_to_002() -> None:
    """「与 002 逐字同形」是机器判据：方括号与 'g' 两个细节都算语义，写错不报错、只是静默失去召回。"""

    gin_002 = GIN_EXPRESSION.search(executable_sql(LEXICAL_MIGRATION))
    gin_003 = GIN_EXPRESSION.search(executable_sql(REAL_MODEL_MIGRATION))
    assert gin_002 is not None and gin_003 is not None
    assert flatten(gin_002.group(1)) == flatten(gin_003.group(1))
    assert flatten(LEXICAL_TSVECTOR_SQL) in flatten(executable_sql(REAL_MODEL_MIGRATION))
    assert f"([{CJK_CHAR_CLASS}])" in executable_sql(REAL_MODEL_MIGRATION)


def test_real_model_table_name_is_usable_by_the_repositories() -> None:
    """表名要能配置、也能通过两条通道的白名单；PG 标识符上限 63 字符还包含自动命名的主键索引。"""

    assert _TABLE_NAME.fullmatch(REAL_MODEL_TABLE)
    assert len(REAL_MODEL_TABLE) <= 63
    assert len(f"{REAL_MODEL_TABLE}_pkey") <= 63


def test_config_guard_recognizes_the_offline_table_by_its_ddl_name() -> None:
    """D-117 的判据与 001 的表名必须同源，否则守卫拦的是一个不存在的表名。"""

    assert OFFLINE_VECTOR_TABLE == OFFLINE_TABLE
    assert f"create table if not exists {OFFLINE_VECTOR_TABLE}" in executable_sql(VECTOR_MIGRATION)


def test_real_model_migration_declares_the_space_it_defines() -> None:
    """向量空间由「模型 + 预处理口径」共同定义，两个要素都要写在迁移里，否则下一个人会把它当通用表。"""

    text = (MIGRATIONS / REAL_MODEL_MIGRATION).read_text(encoding="utf-8")
    assert "text-embedding-v4" in text
    assert "mask_text" in text
    assert "vector(1024)" in text
