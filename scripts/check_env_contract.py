#!/usr/bin/env python3
"""配置对账（G11）：断言 application*.yml 里的 ${VAR} ⊆ env.example 键集合。

只用标准库，任何 Python 3 都能直接运行（不需要 pyyaml）。

用法：
    python scripts/check_env_contract.py
    python scripts/check_env_contract.py --root . --env ops/env/local.env.example

退出码：
    0 = 所有「项目环境变量」都被 env.example 覆盖；
    1 = 有变量缺失、出现未登记的非项目占位符、或输入文件缺失。

设计要点（避免用 `|| true` 之类把失败吞掉）：
    - 嵌套占位符 ${A:${B:...}} 会用「匹配每个 ${ 后的标识符」的方式同时提取 A、B；
    - 只有带 PROJECT_PREFIXES 前缀的 ${VAR} 才算「部署需注入的环境变量」，必须覆盖；
    - 其余 ${...}（如 Spring Boot 注入的 ${PID:- }）不是环境变量，放在
      FRAMEWORK_PLACEHOLDERS 显式允许清单中并打印出来；未登记的会直接报错，
      逼迫维护者判定它是框架占位符还是漏配的环境变量，而不是静默跳过。
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

# 项目配置环境变量前缀：带这些前缀的 ${VAR} 必须由 env.example 覆盖。
PROJECT_PREFIXES = ("AF_", "DOCQA_")

# 非部署环境变量的占位符（Spring Boot / OS 注入），显式允许并打印。
FRAMEWORK_PLACEHOLDERS = frozenset({
    "PID", # Spring Boot 进程 ID，如 ${PID:- }
    "HOSTNAME",
    "HOME",
    "TMPDIR",
    "RANDOM",
})

# 匹配每个 "${" 之后的标识符；嵌套默认值里的 ${B} 也会被单独捕获。
# 允许 '.' 以覆盖可能的 ${spring.xxx} 自引用（会被归为非项目占位符处理）。
PLACEHOLDER_RE = re.compile(r"\$\{([A-Za-z_][A-Za-z0-9_.]*)")

# env.example 的键：行首可有空白、可带 # 注释掉的示例键、可有 export 前缀。
# 只认全大写环境变量名，避免把注释里的普通单词误判为键。
ENV_KEY_RE = re.compile(r"^\s*(?:#\s*)?(?:export\s+)?([A-Z][A-Z0-9_]*)\s*=")

# rglob 时跳过的目录，避免扫到构建产物 / 依赖目录。
SKIP_PARTS = frozenset({"target", "build", "node_modules", ".venv", ".git", ".idea", ".pytest_cache", ".ruff_cache"})


def parse_env_keys(path: Path) -> dict[str, int]:
    """返回 {键: 首次出现的行号}；被 # 注释掉的示例键也算「已在模板中登记」。"""
    keys: dict[str, int] = {}
    with path.open(encoding="utf-8", errors="replace") as fh:
        for lineno, line in enumerate(fh, 1):
            m = ENV_KEY_RE.match(line)
            if m:
                keys.setdefault(m.group(1), lineno)
    return keys


def find_yml_files(root: Path) -> list[Path]:
    return sorted(
        p for p in root.rglob("application*.yml")
        if not (set(p.parts) & SKIP_PARTS)
    )


def collect_placeholders(yml_files: list[Path]) -> dict[str, set[str]]:
    found: dict[str, set[str]] = {}
    for p in yml_files:
        text = p.read_text(encoding="utf-8", errors="replace")
        for name in PLACEHOLDER_RE.findall(text):
            found.setdefault(name, set()).add(str(p))
    return found


def main() -> int:
    parser = argparse.ArgumentParser(description="application.yml ${VAR} ⊆ env.example 键集合 对账")
    parser.add_argument("--root", default=str(Path(__file__).resolve().parent.parent),
                        help="仓库根目录（默认：本脚本所在 scripts/ 的父目录）")
    parser.add_argument("--env", default="ops/env/local.env.example",
                        help="env.example 相对 root 的路径")
    args = parser.parse_args()

    root = Path(args.root).resolve()
    env_arg = Path(args.env)
    env_path = env_arg if env_arg.is_absolute() else (root / env_arg)

    print(f"[check_env_contract] root : {root}")
    print(f"[check_env_contract] env.example : {env_path}")

    if not env_path.is_file():
        print(f"::error:: env.example 不存在: {env_path}", file=sys.stderr)
        return 1
    yml_files = find_yml_files(root)
    if not yml_files:
        print(f"::error:: {root} 下未找到 application*.yml", file=sys.stderr)
        return 1
    print(f"[check_env_contract] yml files : {len(yml_files)}")

    env_keys = parse_env_keys(env_path)
    placeholders = collect_placeholders(yml_files)

    project_vars = {n: f for n, f in placeholders.items() if n.startswith(PROJECT_PREFIXES)}
    framework = {n: f for n, f in placeholders.items() if not n.startswith(PROJECT_PREFIXES)}

    missing = sorted(n for n in project_vars if n not in env_keys)
    unknown_framework = sorted(set(framework) - FRAMEWORK_PLACEHOLDERS)
    extras = sorted(n for n in env_keys if n.startswith(PROJECT_PREFIXES) and n not in project_vars)

    # 逐项打印分类结果，便于人工复核「区分处理」是否成立
    print(f"[check_env_contract] 项目环境变量（必须被覆盖）: {len(project_vars)}")
    print(f"[check_env_contract] 框架/OS 占位符（显式排除）: {len(framework)}")
    for n in sorted(framework):
        status = "allowlisted" if n in FRAMEWORK_PLACEHOLDERS else "UNKNOWN"
        print(f" [{status}] {n}")
    print(f"[check_env_contract] env.example 中额外模板键（信息项，不判失败）: {len(extras)}")

    if unknown_framework:
        print(f"::error:: 出现未登记的「非项目占位符」{unknown_framework}；"
              f"请判定它属于框架占位符（加入 FRAMEWORK_PLACEHOLDERS）还是漏配的环境变量。",
              file=sys.stderr)
        return 1

    if missing:
        print(f"::error:: {len(missing)} 个 application.yml 变量未出现在 env.example：", file=sys.stderr)
        for n in missing:
            print(f" - {n} (来自 {sorted(project_vars[n])})", file=sys.stderr)
        return 1

    print(f"PASS: {len(project_vars)} 个 {PROJECT_PREFIXES} 变量全部被 env.example 覆盖；"
          f"{len(framework)} 个框架占位符已显式排除；{len(extras)} 个额外模板键。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
