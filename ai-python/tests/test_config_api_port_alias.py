"""配置契约：ops/env/local.env 里的 DOCQA_AI_PORT 必须真正决定监听端口。

env_prefix 不会作用于显式 validation_alias，字段名 api_port 自动派生出的
DOCQA_AI_API_PORT 又不在契约里，所以这个别名一旦丢失就会静默退回默认 8000：
网关按 DOCQA_PYTHON_BASE_URL(:18000) 调用恒定 connection refused，而数据面自检仍是「启动成功」。
"""

from __future__ import annotations

from docqa_ai.config import Settings


def test_ai_port_env_key_drives_api_port(monkeypatch) -> None:
    monkeypatch.setenv("DOCQA_AI_PORT", "18000")
    assert Settings().api_port == 18000


def test_api_port_default_is_untouched_without_env(monkeypatch) -> None:
    monkeypatch.delenv("DOCQA_AI_PORT", raising=False)
    monkeypatch.delenv("DOCQA_AI_API_PORT", raising=False)
    assert Settings().api_port == 8000
