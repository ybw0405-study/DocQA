"""向量空间守卫的行为面：真实 embedding 不许写进 offline 空间。

判据本身在 `config.py` 的 `validate_consistency` 里，是**启动期**的 fail-closed 检查；
它与  的分表迁移（`migrations/003_*.sql`）是一对：迁移给出「另一个空间」，守卫保证
「切了 Provider 却忘了切表」不会变成静默的混空间。
"""

from __future__ import annotations

import pytest
from pydantic import ValidationError

from docqa_ai.config import OFFLINE_VECTOR_TABLE
from tests.conftest import make_settings
from tests.test_migrations import REAL_MODEL_TABLE

REAL_EMBEDDING = {
    "embedding_provider": "openai",
    "embedding_base_url": "https://example.invalid/v1",
    "embedding_api_key": "keyword-injected",
    "experiment_budget": 200,
    "experiment_token_budget": 200_000,
}


def test_offline_provider_stays_allowed_on_the_offline_table() -> None:
    """默认形态必须一行不动：offline Provider + 默认表 + 额度 0 是合法组合。"""

    settings = make_settings()
    assert settings.embedding_provider == "offline"
    assert settings.pg_table == OFFLINE_VECTOR_TABLE


def test_real_embedding_provider_must_leave_the_offline_space() -> None:
    with pytest.raises(ValidationError, match="dedicated vector space"):
        make_settings(**REAL_EMBEDDING)


def test_real_embedding_provider_with_the_split_table_starts_fine() -> None:
    settings = make_settings(pg_table=REAL_MODEL_TABLE, **REAL_EMBEDDING)
    assert settings.pg_table == REAL_MODEL_TABLE
    # 启动日志要读得出写向哪个空间，否则混空间只在事后由人工对账才发现（的同一课）。
    assert settings.redacted_summary()["pg_table"] == REAL_MODEL_TABLE


def test_the_guard_is_deliberately_one_sided() -> None:
    """已知边界：反向组合（offline Provider 写真实模型表）**不拦**。

    拦它等于关掉「不花额度也能验证 003 形态」这条运维路径——一次性库里用离线伪向量试建表、试索引形态是
    本期唯一零成本的形态取证手段。反向污染的检测归  的有界对账查询：它按 `metadata->>'embedding_model'`
    分组，两个方向的混写都会露出第二行。守卫只消掉最容易 accidental 的那一格（切了 Provider 忘了切表）。
    """

    settings = make_settings(pg_table=REAL_MODEL_TABLE)
    assert settings.embedding_provider == "offline"
    assert settings.pg_table == REAL_MODEL_TABLE


def test_budget_guard_still_fires_before_the_space_guard() -> None:
    """两条守卫的先后是刻意的：先问能不能花钱，再问写到哪张表。

    这条用例保护的是覆盖面而不是实现细节——`test_experiment_budget` 里「embedding 轴缺额度」的断言
    一旦排到空间守卫之后，它测到的就是另一件事了。
    """

    without_budget = {key: value for key, value in REAL_EMBEDDING.items() if not key.startswith("experiment_")}
    with pytest.raises(ValidationError, match="experiment_budget"):
        make_settings(**without_budget)


def test_generation_and_rerank_providers_do_not_move_the_vector_space() -> None:
    """只有 embedding 写向量：`llm_provider` / `rerank_provider` 切真时默认表仍然合法。"""

    llm_only = make_settings(
        llm_provider="openai",
        llm_base_url="https://example.invalid/v1",
        experiment_budget=200,
        experiment_token_budget=200_000,
    )
    assert llm_only.pg_table == OFFLINE_VECTOR_TABLE

    rerank_only = make_settings(
        rerank_provider="dashscope",
        rerank_base_url="https://example.invalid",
        rerank_api_key="keyword-injected",
        experiment_budget=200,
        experiment_token_budget=200_000,
    )
    assert rerank_only.pg_table == OFFLINE_VECTOR_TABLE
