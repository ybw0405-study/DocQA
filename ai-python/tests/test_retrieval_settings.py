from __future__ import annotations

import pytest
from pydantic import ValidationError

from docqa_ai.config import Settings
from tests.conftest import make_settings


def test_s5_retrieval_defaults_are_the_delivered_shape() -> None:
    """配置面默认值必须等于交付形态，不能「测试跑开、线上跑关」（D-90 ②）。"""

    settings = make_settings()
    assert settings.lexical_recall_enabled is True
    assert settings.retrieval_lexical_candidate_limit == 50
    assert settings.fusion_rrf_k == 60
    assert settings.lexical_min_query_chars == 2


def test_lexical_candidate_limit_below_top_k_is_rejected() -> None:
    """否则词法通道每轮都被自己的上限截没（D-90 的跨键校验）。"""

    with pytest.raises(ValidationError, match="retrieval_lexical_candidate_limit"):
        make_settings(retrieval_top_k=20, retrieval_lexical_candidate_limit=10)


@pytest.mark.parametrize(
    "overrides",
    [
        {"retrieval_lexical_candidate_limit": 0},
        {"retrieval_lexical_candidate_limit": 501},
        {"fusion_rrf_k": 0},
        {"fusion_rrf_k": 1001},
        {"lexical_min_query_chars": 0},
        {"lexical_min_query_chars": 17},
    ],
)
def test_s5_keys_are_bounded(overrides: dict[str, int]) -> None:
    with pytest.raises(ValidationError):
        make_settings(**overrides)


def test_redacted_summary_reports_the_effective_retrieval_shape() -> None:
    """启动日志必须读得出实际生效形态，否则退化与关通道在事后不可区分（D-90 的代价条目）。"""

    settings = make_settings(
        lexical_recall_enabled=False,
        retrieval_lexical_candidate_limit=200,
        fusion_rrf_k=120,
        lexical_min_query_chars=3,
    )
    summary = settings.redacted_summary()
    assert summary["lexical_recall_enabled"] is False
    assert summary["retrieval_lexical_candidate_limit"] == 200
    assert summary["fusion_rrf_k"] == 120
    assert summary["lexical_min_query_chars"] == 3


def test_redacted_summary_still_holds_no_credentials_or_dsn() -> None:
    settings = make_settings(pg_dsn="postgresql://user:secret@127.0.0.1:5432/db")
    summary = settings.redacted_summary()
    assert summary["pg_configured"] is True
    assert "pg_dsn" not in summary
    # langfuse_otlp_host 是 PR2 显式裁决暴露的部署面（只有主机名，无凭据无路径）；
    # 其余键仍不得携带任何主机地址——DSN 主机泄漏的判据不因此放宽。
    assert summary["langfuse_otlp_host"] == "127.0.0.1"
    text = str({key: value for key, value in summary.items() if key != "langfuse_otlp_host"})
    assert "secret" not in text
    assert "127.0.0.1" not in text


def test_aliased_dsn_honors_keyword_overrides(monkeypatch: pytest.MonkeyPatch) -> None:
    """声明了 validation_alias 的字段一旦不回传字段名，pg_dsn="" 会被静默丢弃，测试就跑到真实 DSN 上。"""

    monkeypatch.setenv("DOCQA_AI_PG_DSN", "postgresql://env@127.0.0.1:5432/env_db")
    settings = Settings(_env_file=None, pg_dsn="postgresql://caller@127.0.0.1:5432/caller_db")
    assert settings.pg_dsn == "postgresql://caller@127.0.0.1:5432/caller_db"


def test_aliased_dsn_still_comes_from_the_environment_without_an_override(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """生产形态不变：DSN 属于只从环境读取的凭据面，不靠代码传参。"""

    monkeypatch.setenv("DOCQA_AI_PG_DSN", "postgresql://env@127.0.0.1:5432/env_db")
    assert Settings(_env_file=None).pg_dsn == "postgresql://env@127.0.0.1:5432/env_db"
