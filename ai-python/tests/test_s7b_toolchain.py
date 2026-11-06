from __future__ import annotations

import importlib.util
import json
from pathlib import Path

import pytest

from docqa_ai.config import Settings

REPO_ROOT = Path(__file__).resolve().parents[2]
EXPANSION = REPO_ROOT / "docs" / "06-expansion"


def _load(name: str) -> object:
    with (EXPANSION / name).open(encoding="utf-8") as handle:
        return json.load(handle)


def _offline_settings(**overrides: object) -> Settings:
    base: dict[str, object] = {
        "_env_file": None,
        "environment": "test",
        "embedding_provider": "offline",
        "llm_provider": "offline",
        "pg_dsn": "",
        "mq_enabled": False,
        "minio_enabled": False,
        "otel_enabled": False,
    }
    base.update(overrides)
    return Settings(**base)


def test_lock_pins_the_two_provisioned_servers() -> None:
    lock = _load("s7b-toolchain.lock.json")
    assert isinstance(lock, dict)
    assert lock["policy"].startswith("runtime never installs")

    time_spec = lock["servers"]["time"]
    assert time_spec["version"] == "2026.10.28"
    assert Path(time_spec["command"]).suffix.lower() == ".exe"
    assert time_spec["args"] == ["-m", "mcp_server_time"]
    assert "mcp_server_time-2026.10.28-py3-none-any.whl" in time_spec["artifacts"]

    fs_spec = lock["servers"]["filesystem"]
    assert fs_spec["version"] == "2.1.0"
    assert Path(fs_spec["command"]).name.lower() == "node.exe"
    assert fs_spec["args"][0].endswith("/dist/index.js")
    assert len(fs_spec["args"]) == 2
    assert "modelcontextprotocol-server-filesystem-2.1.0.tgz" in fs_spec["artifacts"]


def test_sample_config_enables_only_time() -> None:
    example = _load("mcp-servers.example.json")
    assert isinstance(example, list)
    assert [entry["name"] for entry in example] == ["time"]
    assert example[0]["transport"] == "stdio"
    assert set(example[0]["allowedTools"]) == {"get_current_time", "convert_time"}

    settings = _offline_settings(mcp_enabled=True, mcp_servers=example)
    assert settings.mcp_enabled is True
    assert settings.mcp_servers[0].name == "time"
    assert settings.mcp_servers[0].transport == "stdio"


def test_mcp_stays_disabled_by_default(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("DOCQA_AI_MCP_ENABLED", raising=False)
    monkeypatch.delenv("DOCQA_AI_MCP_SERVERS", raising=False)
    settings = _offline_settings()
    assert settings.mcp_enabled is False
    assert settings.mcp_servers == []


def test_real_server_script_defaults_to_zero_egress(
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
) -> None:
    monkeypatch.delenv("S7B_REAL", raising=False)
    path = EXPANSION / "s7b-real-server-verify.py"
    spec = importlib.util.spec_from_file_location("s7b_real_server_verify", path)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)

    assert module.main() == 0
    assert "zero egress" in capsys.readouterr().out
