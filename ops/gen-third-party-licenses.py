#!/usr/bin/env python3
"""从本地 .venv 的 dist-info/METADATA 汇总生成根目录 THIRD-PARTY-LICENSES.md。

用法（仓库根目录）：python ops/gen-third-party-licenses.py
.venv 是本机的 gitignored 产物，本脚本只是「可复现地汇总」的工具，不是 CI 门禁；
带版本与哈希的权威清单是 .github/workflows/sbom.yml 产出的可下载 SBOM artifact。
"""
from __future__ import annotations

import re
import sys
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SITE_PACKAGES = ROOT / "ai-python" / ".venv" / "Lib" / "site-packages"
PROJECT = "DocQA"
PROJECT_LICENSE = "Apache License 2.0（见 LICENSE）"
OWN_DISTS = {"docqa-ai"}
OUTPUT = ROOT / "THIRD-PARTY-LICENSES.md"


def license_of(metadata: Path) -> str:
    if not metadata.is_file():
        return "未声明（见发行包内 LICENSE）"
    text = metadata.read_text(encoding="utf-8", errors="replace")
    expr = re.search(r"^License-Expression:\s*(.+)$", text, re.M)
    if expr:
        return expr.group(1).strip()
    classifiers = re.findall(r"^Classifier:\s*License :: (.+)$", text, re.M)
    if classifiers:
        return classifiers[-1].split(" :: ")[-1].strip()
    plain = re.search(r"^License:\s*(.*)$", text, re.M)
    if plain:
        value = plain.group(1).strip()
        if value and len(value) < 80:
            return value
    return "未声明（见发行包内 LICENSE）"


def main() -> int:
    if not SITE_PACKAGES.is_dir():
        print(f"未找到 {SITE_PACKAGES}；请先创建本地 .venv 并安装依赖。", file=sys.stderr)
        return 1
    buckets: dict[str, list[tuple[str, str]]] = defaultdict(list)
    for info in sorted(SITE_PACKAGES.glob("*.dist-info")):
        stem = info.name[: -len(".dist-info")]
        if "-" not in stem:
            continue
        name, version = stem.split("-", 1)
        if name.replace("_", "-").lower() in OWN_DISTS:
            continue
        buckets[license_of(info / "METADATA")].append((name.replace("_", "-"), version))
    total = sum(len(v) for v in buckets.values())
    lines = [
        f"# Third-Party Licenses — {PROJECT}",
        "",
        f"本仓库自身采用 {PROJECT_LICENSE}；见根目录 `NOTICE` 与 `LICENSE`。",
        "",
        "Apache-2.0 第 4 节要求保留第三方归属与许可声明，本文件是该声明的汇总入口。",
        "下表由 `ops/gen-third-party-licenses.py` 从本机",
        "`.venv/Lib/site-packages/*.dist-info/METADATA` 实际读取汇总",
        "（`License-Expression` / License Classifier / `License` 字段），按许可证类别分组。",
        "**权威且带版本与哈希的清单**是 `.github/workflows/sbom.yml` 产出的可下载 SBOM artifact；",
        "若此处与发行包内 `LICENSE` / `METADATA` 不一致，以发行包内文本为准。",
        "本文件不构成法律意见。",
        "",
        f"共识别 {total} 个第三方发行包，{len(buckets)} 类许可证声明。",
        "",
    ]
    for lic in sorted(buckets, key=str.lower):
        entries = sorted(buckets[lic])
        lines += [f"## {lic}（{len(entries)}）", "", "| 组件 | 版本 |", "| --- | --- |"]
        lines += [f"| `{name}` | `{version}` |" for name, version in entries]
        lines.append("")
    OUTPUT.write_text("\n".join(lines), encoding="utf-8")
    print(f"wrote {OUTPUT} ({total} packages, {len(buckets)} license groups)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
