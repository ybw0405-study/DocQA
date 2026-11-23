"""agent 级评测套件与基线门禁的回归测试。

套件本身逐用例断言治理不变量（见 eval/agent.py）；这里锁三件事：
- 全部用例必须通过（任何治理回归都会让对应用例失败）；
- 提交基线与当前运行一致（基线漂移必须显式重录）；
- 基线门禁真的能抓住回归与用例集漂移，CLI 退出码语义正确。
"""

from __future__ import annotations

import asyncio
import json
from pathlib import Path
from typing import Any

import pytest

from docqa_ai.eval.agent import run_agent_eval
from docqa_ai.eval.runner import baseline_violations, main, run_eval, update_baseline


def test_agent_suite_all_cases_pass() -> None:
    payload = asyncio.run(run_agent_eval())
    assert payload["case_count"] == 8
    assert payload["pass_rate"] == 1.0
    for case in payload["cases"]:
        assert case["passed"], f"{case['case_id']}: {case['detail']}"


def test_agent_suite_locks_governance_outcomes() -> None:
    payload = asyncio.run(run_agent_eval())
    details = {case["case_id"]: case["detail"] for case in payload["cases"]}
    assert "citations=3" in details["citations_scope_and_cap"]
    assert "BUDGET_EXHAUSTED" in details["budget_exhausted"]
    assert "AGT-VALIDATION-003" in details["pii_blocked"]
    assert "AGT-UPSTREAM-003" in details["kill_switch"]
    assert "TOOL_EXECUTED + AGENT_COMPLETED" in details["tool_then_final_audit"]


def test_agent_baseline_check_is_green() -> None:
    payload = asyncio.run(run_agent_eval())
    assert baseline_violations("agent", payload) == []


def test_retrieval_baseline_check_is_green() -> None:
    payload = asyncio.run(run_eval())
    assert baseline_violations("retrieval", payload) == []


def test_cli_suite_all_check_baseline() -> None:
    assert main(["--suite", "all", "--check-baseline"]) == 0


def test_cli_fails_when_a_case_fails(monkeypatch: pytest.MonkeyPatch) -> None:
    async def failing() -> dict[str, Any]:
        return {
            "suite": "agent",
            "case_count": 1,
            "passed_count": 0,
            "pass_rate": 0.0,
            "cases": [{"case_id": "broken", "passed": False, "detail": "boom"}],
        }

    monkeypatch.setattr("docqa_ai.eval.agent.run_agent_eval", failing)
    assert main(["--suite", "agent"]) == 1


def test_baseline_update_roundtrip(tmp_path: Path) -> None:
    payload = asyncio.run(run_agent_eval())
    path = update_baseline("agent", payload, baseline_dir=tmp_path)
    assert path.exists()
    assert baseline_violations("agent", payload, baseline_dir=tmp_path) == []


def test_baseline_detects_retrieval_regression(tmp_path: Path) -> None:
    payload = asyncio.run(run_eval())
    update_baseline("retrieval", payload, baseline_dir=tmp_path)
    degraded = json.loads(json.dumps(payload))
    degraded["mrr"] -= 0.1
    violations = baseline_violations("retrieval", degraded, baseline_dir=tmp_path)
    assert any("mrr" in item for item in violations)


def test_baseline_detects_agent_case_drift(tmp_path: Path) -> None:
    payload = asyncio.run(run_agent_eval())
    update_baseline("agent", payload, baseline_dir=tmp_path)
    drifted = json.loads(json.dumps(payload))
    drifted["cases"] = drifted["cases"][:-1]
    drifted["case_count"] -= 1
    assert baseline_violations("agent", drifted, baseline_dir=tmp_path)
