from __future__ import annotations

import uuid

import psycopg
import pytest

from docqa_ai.protocol.models import DocumentScope
from docqa_ai.rag.lexical_repository import (
    InMemoryLexicalRepository,
    LexicalStoreError,
    LexicalStoreUnavailable,
    PgLexicalRepository,
    UnavailableLexicalRepository,
    build_lexical_search_sql,
    lexical_params,
    phrase_surfaces,
)
from docqa_ai.rag.repository import ChunkRecord
from docqa_ai.text.lexical import LEXICAL_TSQUERY_SQL, LEXICAL_TSVECTOR_SQL, build_lexical_query, extract_phrases

TABLE = "chunk_embedding_1024"
DOC_A = "11111111-1111-1111-1111-111111111111"
DOC_B = "22222222-2222-2222-2222-222222222222"
DOC_C = "33333333-3333-3333-3333-333333333333"
DOC_D = "44444444-4444-4444-4444-444444444444"
AUTHORIZATION_PREDICATE = "(document_id, index_version) IN ((%s::uuid, %s::text), (%s::uuid, %s::text))"


def scope(document_id: str, index_version: str) -> DocumentScope:
    return DocumentScope(document_id=uuid.UUID(document_id), index_version=index_version)


def chunk(document_id: str, chunk_index: int, content: str, index_version: str) -> ChunkRecord:
    return ChunkRecord(
        vector_id=uuid.uuid5(uuid.NAMESPACE_URL, f"{document_id}:{index_version}:{chunk_index}"),
        document_id=uuid.UUID(document_id),
        chunk_index=chunk_index,
        content=content,
        embedding=(),
        metadata={"index_version": index_version, "page_no": 1},
    )


def test_sql_references_each_lexical_constant_exactly_twice() -> None:
    sql = build_lexical_search_sql(TABLE, 2)
    assert sql.count(LEXICAL_TSVECTOR_SQL) == 2
    assert sql.count(LEXICAL_TSQUERY_SQL) == 2
    assert sql.count("ts_rank") == 1
    assert sql.endswith("LIMIT %s")


def test_sql_keeps_the_vector_channels_authorization_predicate() -> None:
    assert AUTHORIZATION_PREDICATE in build_lexical_search_sql(TABLE, 2)


def test_sql_projects_no_score_column() -> None:
    sql = build_lexical_search_sql(TABLE, 1).lower()
    assert "select document_id, chunk_index, content, metadata from" in sql
    assert "as score" not in sql
    assert "1 - (embedding" not in sql


def test_rejects_table_names_outside_the_settings_contract() -> None:
    for bad in ("", "chunk; drop table x", "Chunk_Embedding", "1chunk"):
        with pytest.raises(ValueError):
            build_lexical_search_sql(bad, 1)
    with pytest.raises(ValueError):
        PgLexicalRepository("postgresql://unused/unused", table="public.chunk")
    with pytest.raises(ValueError):
        build_lexical_search_sql(TABLE, 0)


def test_params_carry_the_same_compiled_query_twice() -> None:
    scopes = [scope(DOC_A, "v1"), scope(DOC_B, "v2")]
    query = build_lexical_query("复习 config.py 幂等性")
    assert query is not None
    params = lexical_params(query, scopes, 50)
    assert params.count(query) == 2
    assert params[-1] == 50
    assert len(params) == len(scopes) * 2 + 3


@pytest.mark.parametrize(
    "text",
    ["config.py", "幂等性", "复习 config.py 幂等性", "学习"],
)
def test_phrase_surfaces_round_trips_the_compiler(text: str) -> None:
    query = build_lexical_query(text)
    assert query is not None
    phrases = extract_phrases(text)
    assert phrase_surfaces(query) == tuple(phrase.surface for phrase in phrases)
    assert all(surface for surface in phrase_surfaces(query))


async def test_pg_repository_short_circuits_without_scopes_or_limit() -> None:
    repository = PgLexicalRepository("postgresql://unused/unused")
    assert await repository.search("'config.py'", [], 10) == []
    assert await repository.search("'config.py'", [scope(DOC_A, "v1")], 0) == []


async def test_pg_repository_rejects_an_empty_compiled_query() -> None:
    repository = PgLexicalRepository("postgresql://unused/unused")
    with pytest.raises(LexicalStoreError):
        await repository.search("", [scope(DOC_A, "v1")], 10)


async def test_pg_repository_wraps_driver_failures_as_unavailable(monkeypatch: pytest.MonkeyPatch) -> None:
    async def fail_connect(*args: object, **kwargs: object) -> None:
        raise OSError("pg is down")

    monkeypatch.setattr(psycopg.AsyncConnection, "connect", fail_connect)
    repository = PgLexicalRepository("postgresql://unused/unused")
    with pytest.raises(LexicalStoreUnavailable):
        await repository.search("'config.py'", [scope(DOC_A, "v1")], 10)


async def test_unavailable_repository_fails_closed() -> None:
    with pytest.raises(LexicalStoreUnavailable):
        await UnavailableLexicalRepository().search("'config.py'", [scope(DOC_A, "v1")], 10)


async def test_inmemory_repository_honours_the_authorized_pairs() -> None:
    repository = InMemoryLexicalRepository(
        [
            chunk(DOC_A, 0, "config.py 的另一版本", "v2"),
            chunk(DOC_A, 1, "config.py 的第二块", "v1"),
        ]
    )
    hits = await repository.search("'config.py'", [scope(DOC_A, "v1")], 10)
    assert [hit.chunk_index for hit in hits] == [1]
    assert not hasattr(hits[0], "score")


async def test_inmemory_repository_matches_the_documented_substring_semantics() -> None:
    repository = InMemoryLexicalRepository(
        [
            chunk(DOC_B, 0, "教学习惯很重要", "v1"),
            chunk(DOC_B, 1, "完全无关的一段话", "v1"),
        ]
    )
    query = build_lexical_query("学习")
    assert query is not None
    hits = await repository.search(query, [scope(DOC_B, "v1")], 10)
    assert [hit.chunk_index for hit in hits] == [0]


async def test_inmemory_repository_orders_by_matched_phrase_count() -> None:
    repository = InMemoryLexicalRepository(
        [
            chunk(DOC_C, 1, "只有学习", "v1"),
            chunk(DOC_C, 0, "复习学习幂等性", "v1"),
        ]
    )
    query = build_lexical_query("学习幂等")
    assert query is not None
    hits = await repository.search(query, [scope(DOC_C, "v1")], 10)
    assert [hit.chunk_index for hit in hits] == [0, 1]


async def test_inmemory_repository_rejects_a_query_without_phrases() -> None:
    repository = InMemoryLexicalRepository([chunk(DOC_D, 0, "内容", "v1")])
    with pytest.raises(LexicalStoreError):
        await repository.search("", [scope(DOC_D, "v1")], 10)
