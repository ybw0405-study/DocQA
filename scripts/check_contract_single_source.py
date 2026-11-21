#!/usr/bin/env python3
"""CT-3：契约单源校验（CI 门禁脚本，零第三方依赖）。

事实源是 ``docs/02-design/openapi-java.yaml``。本脚本做三件事：

1. 用正则扫出 yaml 里**每一处约束关键字**（nullable / minimum / maxLength / pattern /
   enum / format / minItems / maxItems / uniqueItems / additionalProperties），生成一份机器
   登记表（``--emit-registry``）。前端 ``contract-drift.spec.ts`` 用一条独立正则反向扫描，
   断言「扫到的每条都在登记表里」，从而堵住方案 CT-3 指出的「369 处约束只有约 60 条手工
   登记、且没有『所有约束均已登记』反向扫描」这个缺口。
2. ``--springdoc-spec`` 接收运行时 dump 的 OpenAPI JSON，逐字段 diff yaml（properties /
   required / enum / nullable / 数值约束）。仓库当前没有 springdoc 依赖，spec 由 CI 在起服务
   后提供；未传时只做本地校验，``--require-springdoc`` 可强制 CI 必须提供（否则 exit 2）。
3. 解析出的 schema 结构供 ``ai-python/tests/test_contract_single_source.py`` 复用，从
   Pydantic ``model_json_schema()`` 提取字段 / required 与 yaml 对账。

用法::

    python scripts/check_contract_single_source.py --emit-registry scripts/contract_constraints.json
    python scripts/check_contract_single_source.py --registry scripts/contract_constraints.json
    python scripts/check_contract_single_source.py --registry ... --springdoc-spec openapi.json --require-springdoc
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from typing import Any

CONSTRAINT_KEYWORDS = (
    "nullable",
    "minimum",
    "maximum",
    "minLength",
    "maxLength",
    "pattern",
    "enum",
    "format",
    "minItems",
    "maxItems",
    "uniqueItems",
    "additionalProperties",
)

# 与前端 contract-drift.spec.ts 的反向扫描保持逐字一致的关键字集合。
KEYWORD_LINE = re.compile(
    r"^(\s+)(" + "|".join(CONSTRAINT_KEYWORDS) + r"):\s*(.*)$"
)
SCHEMA_LINE = re.compile(r"^    ([A-Za-z][A-Za-z0-9_]*):\s*$")
SECTION_LINE = re.compile(r"^  ([A-Za-z][A-Za-z0-9_]*):\s*$")
FIELD_LINE = re.compile(r"^        ([A-Za-z][A-Za-z0-9_]*):\s*$")
LIST_ITEM = re.compile(r"^\s+- (.+?)\s*$")


def _clean_value(raw: str) -> Any:
    value = raw.strip()
    if len(value) >= 2 and value[0] == value[-1] and value[0] in ("'", '"'):
        return value[1:-1]
    if value.lower() == "true":
        return True
    if value.lower() == "false":
        return False
    if re.fullmatch(r"-?\d+", value):
        return int(value)
    return value


def parse_contract(path: Path) -> dict[str, Any]:
    """解析 components.schemas 结构 + 扫描全文约束。只依赖缩进约定，不引入 PyYAML。

    约束登记表必须覆盖**全文**（paths / parameters / schemas 段都有约束），因此约束扫描
    遍历整个文件；只有 ``  schemas:`` 区段内才解析 properties / required / enum 结构，
    用于 springdoc diff 与 Pydantic 对账。
    """
    lines = path.read_text(encoding="utf-8").splitlines()
    if "  schemas:" not in lines:
        raise ValueError("openapi-java.yaml 缺少 components.schemas 区段")

    schemas: dict[str, Any] = {}
    constraints: list[dict[str, Any]] = []
    current_schema: str | None = None
    current_field = ""
    in_schemas = False
    pending_list_key: tuple[str, str] | None = None  # ("required" | "enum", owner)

    for index, line in enumerate(lines):
        line_no = index + 1
        if line == "  schemas:":
            in_schemas = True
            continue
        if in_schemas and SECTION_LINE.match(line):
            in_schemas = False
            current_schema = None
            current_field = ""
            pending_list_key = None

        # ① 全文约束扫描：登记表与前端反向扫描共用同一套正则与关键字。
        keyword_match = KEYWORD_LINE.match(line)
        if keyword_match:
            indent, keyword, raw = keyword_match.groups()
            raw_value = _clean_value(raw)
            field_owner = current_field if (in_schemas and len(indent) >= 10) else ""
            constraints.append(
                {
                    "line": line_no,
                    "keyword": keyword,
                    "value": "" if keyword == "enum" else raw_value,
                    "schema": current_schema if in_schemas else "",
                    "field": field_owner,
                }
            )
            if in_schemas and field_owner:
                if keyword == "enum":
                    schemas[current_schema]["fields"][field_owner]["enum"] = []
                else:
                    schemas[current_schema]["fields"][field_owner][keyword] = raw_value
            pending_list_key = (
                (keyword, field_owner) if (in_schemas and keyword in ("enum", "required")) else None
            )
            continue

        if not in_schemas:
            continue

        # ② 仅 schemas 区段内解析结构。
        schema_match = SCHEMA_LINE.match(line)
        if schema_match:
            current_schema = schema_match.group(1)
            current_field = ""
            pending_list_key = None
            schemas[current_schema] = {"properties": [], "required": [], "enum": [], "fields": {}}
            continue
        if current_schema is None:
            continue

        # required: 列表头是 6 空格且不在 CONSTRAINT_KEYWORDS 里，单独识别。
        if re.match(r"^      required:\s*$", line):
            current_field = ""
            pending_list_key = ("required", "")
            continue

        field_match = FIELD_LINE.match(line)
        if field_match and not line.lstrip().startswith("- "):
            current_field = field_match.group(1)
            schemas[current_schema]["properties"].append(current_field)
            schemas[current_schema]["fields"][current_field] = {}
            pending_list_key = None
            continue

        item_match = LIST_ITEM.match(line)
        if item_match and pending_list_key is not None:
            keyword, owner = pending_list_key
            item = _clean_value(item_match.group(1))
            if keyword == "required" and not owner:
                schemas[current_schema]["required"].append(item)
            elif keyword == "enum" and owner:
                schemas[current_schema]["fields"][owner].setdefault("enum", []).append(item)
            elif keyword == "enum" and not owner:
                schemas[current_schema]["enum"].append(item)
            continue

        # 6 空格的 properties:/description: 等会结束当前字段上下文。
        if re.match(r"^      [A-Za-z]", line):
            current_field = ""
            pending_list_key = None

    return {"schemas": schemas, "constraints": constraints, "lines": lines}


def scan_keyword_lines(lines: list[str]) -> set[str]:
    """与前端测试同款的独立反向扫描：返回 ``{行号:关键字}`` 集合。"""
    found: set[str] = set()
    for index, line in enumerate(lines):
        match = KEYWORD_LINE.match(line)
        if match:
            found.add(f"{index + 1}:{match.group(2)}")
    return found


def emit_registry(contract: dict[str, Any], path: Path) -> None:
    payload = {
        "contract": "docs/02-design/openapi-java.yaml",
        "keywords": list(CONSTRAINT_KEYWORDS),
        "entries": contract["constraints"],
    }
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def check_registry(contract: dict[str, Any], registry_path: Path) -> list[str]:
    if not registry_path.exists():
        return [f"登记表不存在：{registry_path}（先跑 --emit-registry）"]
    payload = json.loads(registry_path.read_text(encoding="utf-8"))
    if payload.get("keywords") != list(CONSTRAINT_KEYWORDS):
        return ["登记表的关键字集合与脚本不一致，需重新生成"]
    entries = payload.get("entries", [])
    scanned = scan_keyword_lines(contract["lines"])
    registered = {f"{entry['line']}:{entry['keyword']}" for entry in entries}
    problems: list[str] = []
    missing = sorted(scanned - registered)
    stale = sorted(registered - scanned)
    if missing:
        problems.append(f"有 {len(missing)} 处约束未进登记表，例如 {missing[:5]}")
    if stale:
        problems.append(f"登记表有 {len(stale)} 条已不在 yaml，例如 {stale[:5]}")
    if not scanned:
        problems.append("反向扫描结果为空：解析器或契约结构失效")
    return problems


def diff_springdoc(contract: dict[str, Any], spec_path: Path) -> tuple[list[str], int]:
    """运行时 dump 的 spec 逐字段 diff yaml；返回 (drift 列表, 共同 schema 数)。"""
    spec = json.loads(spec_path.read_text(encoding="utf-8"))
    spec_schemas = spec.get("components", {}).get("schemas", {}) or {}
    yaml_schemas = contract["schemas"]
    common = sorted(set(yaml_schemas) & set(spec_schemas))
    drifts: list[str] = []
    for name in common:
        lhs = yaml_schemas[name]
        rhs = spec_schemas[name]
        lhs_fields = set(lhs["properties"])
        rhs_fields = set(rhs.get("properties", {}))
        for field in sorted(lhs_fields ^ rhs_fields):
            side = "仅 yaml" if field in lhs_fields else "仅 springdoc spec"
            drifts.append(f"{name}.{field}: {side}")
        lhs_required = set(lhs["required"])
        rhs_required = set(rhs.get("required", []))
        for field in sorted(lhs_required ^ rhs_required):
            side = "仅 yaml" if field in lhs_required else "仅 springdoc spec"
            drifts.append(f"{name}: required['{field}'] {side}")
        for field in sorted(lhs_fields & rhs_fields):
            lhs_field = lhs["fields"].get(field, {})
            rhs_field = rhs["properties"].get(field, {}) or {}
            for keyword in ("nullable", "minimum", "maximum", "minLength", "maxLength", "pattern", "format"):
                if keyword in lhs_field and keyword in rhs_field:
                    if str(lhs_field[keyword]) != str(rhs_field[keyword]):
                        drifts.append(f"{name}.{field}.{keyword}: yaml={lhs_field[keyword]} spec={rhs_field[keyword]}")
            if "enum" in lhs_field and "enum" in rhs_field:
                if list(lhs_field["enum"]) != list(rhs_field["enum"]):
                    drifts.append(f"{name}.{field}.enum: yaml={lhs_field['enum']} spec={rhs_field['enum']}")
        if lhs["enum"] and rhs.get("enum") and lhs["enum"] != rhs["enum"]:
            drifts.append(f"{name}.enum: yaml={lhs['enum']} spec={rhs['enum']}")
    return drifts, len(common)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="CT-3 契约单源校验")
    default_contract = Path(__file__).resolve().parents[1] / "docs/02-design/openapi-java.yaml"
    parser.add_argument("--contract", type=Path, default=default_contract)
    parser.add_argument("--emit-registry", type=Path, help="写出约束登记表 JSON 后退出")
    parser.add_argument("--registry", type=Path, help="校验登记表与 yaml 的双向完整性")
    parser.add_argument("--springdoc-spec", type=Path, help="运行时 dump 的 OpenAPI JSON，逐字段 diff yaml")
    parser.add_argument("--require-springdoc", action="store_true", help="CI 强制要求提供 springdoc spec")
    args = parser.parse_args(argv)

    try:
        contract = parse_contract(args.contract)
    except (OSError, ValueError) as exc:
        print(f"[contract] 解析失败: {exc}", file=sys.stderr)
        return 1

    if args.emit_registry is not None:
        emit_registry(contract, args.emit_registry)
        print(f"[contract] 已写出 {len(contract['constraints'])} 条约束 -> {args.emit_registry}")
        return 0

    problems: list[str] = []
    total_schemas = len(contract["schemas"])
    total_constraints = len(contract["constraints"])
    print(f"[contract] schemas={total_schemas} constraints={total_constraints}")

    if args.registry is not None:
        found = check_registry(contract, args.registry)
        problems.extend(found)
        print(f"[contract] 登记表对账: {'PASS' if not found else 'FAIL'} ({args.registry})")
    else:
        print("[contract] 未提供 --registry，跳过登记表对账（CI 建议提供）")

    if args.springdoc_spec is not None:
        drifts, common = diff_springdoc(contract, args.springdoc_spec)
        print(f"[contract] springdoc diff: 共同 schema={common} drift={len(drifts)}")
        for drift in drifts[:20]:
            print(f"  - {drift}")
        if drifts:
            problems.append(f"springdoc spec 与 yaml 有 {len(drifts)} 处字段漂移")
        if common == 0:
            problems.append("没有可比对的重名 schema：需提供 schema 名映射或核对 dump 来源")
    elif args.require_springdoc:
        problems.append("--require-springdoc 已设置但未提供 --springdoc-spec")
    else:
        print("[contract] 未提供 springdoc spec：本机不做运行时 dump diff（需 CI 起服务后接线）")

    if problems:
        for problem in problems:
            print(f"[contract] FAIL: {problem}", file=sys.stderr)
        return 1
    print("[contract] PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
