from __future__ import annotations

import re

import pytest

from docqa_ai.pii.masker import PLACEHOLDERS, mask_text
from docqa_ai.providers.embeddings import OfflineEmbeddingProvider
from docqa_ai.text.lexical import (
    CJK_CHAR_CLASS,
    LEXICAL_TSQUERY_SQL,
    LEXICAL_TSVECTOR_SQL,
    MAX_PHRASES,
    build_lexical_query,
    extract_phrases,
)

QUERY_RE = re.compile(r"'[^']+'(?: <-> '[^']+')*(?: \| '[^']+'(?: <-> '[^']+')*)*")


def tsqueries(text: str) -> tuple[str, ...]:
    return tuple(phrase.tsquery for phrase in extract_phrases(text))


def test_tsvector_expression_is_the_single_source_for_where_and_order_by() -> None:
    assert CJK_CHAR_CLASS == "一-鿿"
    assert CJK_CHAR_CLASS[0] == "\u4e00" and CJK_CHAR_CLASS[-1] == "\u9fff"
    assert LEXICAL_TSVECTOR_SQL == (
        "to_tsvector('simple'::regconfig, regexp_replace(content, '([一-鿿])', ' \\1 ', 'g'))"
    )
    assert "'g'" in LEXICAL_TSVECTOR_SQL
    assert "'simple'::regconfig" in LEXICAL_TSVECTOR_SQL


def test_tsquery_expression_takes_exactly_one_bound_parameter() -> None:
    assert LEXICAL_TSQUERY_SQL == "to_tsquery('simple'::regconfig, %s)"
    assert LEXICAL_TSQUERY_SQL.count("%s") == 1


def test_ascii_connectors_stay_inside_one_phrase() -> None:
    assert tsqueries("config.py") == ("'config.py'",)
    assert tsqueries("vector_id") == ("'vector_id'",)
    assert tsqueries("3.5-alpha") == ("'3.5-alpha'",)
    assert tsqueries("1.2.3") == ("'1.2.3'",)


def test_uppercase_terms_are_folded_before_tokenize_and_still_match_content() -> None:
    assert tsqueries("Config.Py 与 VECTOR_id") == ("'config.py'", "'与'", "'vector_id'")
    assert build_lexical_query("Config.PY") == "'config.py'"


def test_cjk_run_becomes_adjacent_bigrams_and_never_a_bare_word() -> None:
    phrases = extract_phrases("幂等性")
    assert tuple(phrase.surface for phrase in phrases) == ("幂等", "等性")
    assert tuple(phrase.tsquery for phrase in phrases) == ("'幂' <-> '等'", "'等' <-> '性'")
    assert build_lexical_query("幂等性") == "'幂' <-> '等' | '等' <-> '性'"
    assert "'幂等性'" not in build_lexical_query("幂等性")


def test_single_cjk_segment_degrades_to_one_lexeme() -> None:
    assert tsqueries("学") == ("'学'",)
    assert tsqueries("幂等性 学") == ("'幂' <-> '等'", "'等' <-> '性'", "'学'")


def test_mixed_cjk_and_ascii_segments_keep_both_shapes() -> None:
    assert build_lexical_query("复习 config.py 幂等性") == (
        "'复' <-> '习' | 'config.py' | '幂' <-> '等' | '等' <-> '性'"
    )


def test_only_or_and_adjacent_operators_are_emitted() -> None:
    text = "学习 config.py 幂等性 与并发控制"
    phrases = extract_phrases(text)
    query = build_lexical_query(text)
    assert query is not None
    assert " & " not in query
    assert query.count(" | ") == len(phrases) - 1
    assert re.fullmatch(QUERY_RE, query)


def test_duplicate_phrases_are_deduplicated_in_first_seen_order() -> None:
    assert tsqueries("学习学习幂等") == ("'学' <-> '习'", "'习' <-> '学'", "'习' <-> '幂'", "'幂' <-> '等'")
    assert tsqueries("config.py config.py") == ("'config.py'",)


def test_out_of_class_cjk_is_treated_as_a_separator() -> None:
    assert tsqueries("\u3400\u4dbf学习") == ("'学' <-> '习'",)
    assert tsqueries("\uf900\u3400") == ()
    assert build_lexical_query("\u3400\u4dbf\uf900\ufaff") is None


def test_fullwidth_forms_are_not_folded_into_ascii_lexemes() -> None:
    query = build_lexical_query("token １２３ end")
    assert query == "'token' | 'end'"
    assert "123" not in query


def test_mask_placeholders_contribute_no_phrase() -> None:
    masked = mask_text("我的手机 13800138000 邮箱 a@b.com 身份证 11010519491231002X").masked
    query = build_lexical_query(masked)
    assert query is not None
    for placeholder in PLACEHOLDERS.values():
        assert placeholder.lower().strip("[]") not in query
    assert "'phone'" not in query
    assert "'id_card'" not in query
    assert "'email'" not in query


def test_placeholder_is_removed_as_a_separator_not_deleted() -> None:
    assert tsqueries("学[PHONE]习") == ("'学'", "'习'")


def test_untrusted_input_never_reaches_the_tsquery_grammar() -> None:
    hostile = [
        "' OR 'a",
        "a! & b <->",
        "'; DROP TABLE chunk_embedding_1024; --",
        "config.py' <-> 'x' | 'y",
        '\\ \\" <-> | & ! ( ) : * < >',
        "'<->''",
        "power:1.5 && foo",
    ]
    for text in hostile:
        query = build_lexical_query(text)
        if query is None:
            continue
        assert re.fullmatch(QUERY_RE, query), text
        for char in '\\"&!():*':
            assert char not in query, text


def test_operator_only_input_produces_no_query() -> None:
    assert build_lexical_query("<->") is None
    assert build_lexical_query("| & !") is None
    assert build_lexical_query("！！！？？？") is None
    assert build_lexical_query("") is None
    assert extract_phrases("") == ()


def test_phrase_count_is_clamped_and_deterministic() -> None:
    long_run = "".join(chr(0x4E00 + (index % 80)) for index in range(400))
    first = extract_phrases(long_run)
    assert len(first) == MAX_PHRASES
    assert first == extract_phrases(long_run)
    assert first[0].tsquery == "'一' <-> '丁'"
    assert first[-1].tsquery == tsqueries(long_run[: MAX_PHRASES + 1])[-1]
    query = build_lexical_query(long_run)
    assert query is not None
    assert len(query.split(" | ")) == MAX_PHRASES


@pytest.mark.parametrize("max_phrases", [1, 3, 11, MAX_PHRASES])
def test_clamp_truncates_only_when_the_input_exceeds_it(max_phrases: int) -> None:
    available = len(extract_phrases("复习学习幂等性与并发控制"))
    assert available == 11
    assert len(extract_phrases("复习学习幂等性与并发控制", max_phrases=max_phrases)) == min(max_phrases, available)


def test_shares_the_offline_provider_vocabulary_but_not_its_result() -> None:
    cjk = "幂等性与并发控制"
    provider_tokens = {token for token, _ in OfflineEmbeddingProvider._tokens(cjk)}
    our_surfaces = {phrase.surface for phrase in extract_phrases(cjk)}
    assert our_surfaces <= provider_tokens

    dotted = "config.py"
    provider_tokens = {token for token, _ in OfflineEmbeddingProvider._tokens(dotted)}
    assert {"config", "py"} <= provider_tokens
    assert "config.py" not in provider_tokens
    assert tsqueries(dotted) == ("'config.py'",)

    rare = "\u3400"
    provider_tokens = {token for token, _ in OfflineEmbeddingProvider._tokens(rare)}
    assert rare in provider_tokens
    assert tsqueries(rare) == ()
