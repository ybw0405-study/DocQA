"""CT-3：Pydantic ``model_json_schema()`` ↔ ``openapi-java.yaml`` 单源对账。"""
from __future__ import annotations

import importlib.util
import typing
from pathlib import Path
from types import ModuleType

from docqa_ai.protocol.models import AgentAskRequest, BudgetStatus

ROOT = Path(__file__).resolve().parents[2]
CONTRACT = ROOT / "docs/02-design/openapi-java.yaml"
SCRIPT = ROOT / "scripts/check_contract_single_source.py"
REGISTRY = ROOT / "scripts/contract_constraints.json"


def _load_script() -> ModuleType:
    spec = importlib.util.spec_from_file_location("check_contract_single_source", SCRIPT)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_python_schema_uses_serialization_aliases_matching_yaml_field_names() -> None:
    # yaml 用 camelCase，Python 字段用 snake_case + alias；serialization schema 必须给出别名，
    # 否则「字段/required 对账」会对不上两套命名，这正是 CT-3 的第 3/4 份副本风险。
    props = AgentAskRequest.model_json_schema(mode="serialization")["properties"]
    assert "question" in props
    assert "traceId" in props
    assert "documentScopes" in props
    assert "tenant_id" not in props


def test_question_field_constraints_match_the_yaml_ask_request() -> None:
    yaml_schemas = _load_script().parse_contract(CONTRACT)["schemas"]
    ask = yaml_schemas["AskRequest"]
    python = AgentAskRequest.model_json_schema(mode="serialization")
    assert "question" in ask["properties"]
    assert "question" in ask["required"]
    assert "question" in python["required"]
    assert python["properties"]["question"]["maxLength"] == ask["fields"]["question"]["maxLength"]
    assert python["properties"]["question"]["minLength"] == ask["fields"]["question"]["minLength"]


def test_trace_id_pattern_matches_the_envelope_contract() -> None:
    schemas = _load_script().parse_contract(CONTRACT)["schemas"]
    python = AgentAskRequest.model_json_schema(mode="serialization")
    assert python["properties"]["traceId"]["pattern"] == schemas["ApiEnvelope"]["fields"]["traceId"]["pattern"]


def test_budget_status_enum_matches_the_yaml_enum() -> None:
    schemas = _load_script().parse_contract(CONTRACT)["schemas"]
    assert set(typing.get_args(BudgetStatus)) == set(schemas["BudgetStatus"]["enum"])


def test_registry_covers_every_constraint_in_the_contract() -> None:
    script = _load_script()
    contract = script.parse_contract(CONTRACT)
    assert script.check_registry(contract, REGISTRY) == []
