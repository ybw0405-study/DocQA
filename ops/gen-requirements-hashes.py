#!/usr/bin/env python3
"""生成 ai-python/requirements.lock.txt（G15，精确版本 + 真实 --hash，可复现脚本）。

用法（仓库根目录）：
    python ops/gen-requirements-hashes.py            # 重新生成锁文件
    python ops/gen-requirements-hashes.py --check   # 只校验，不写回

版本来源：本机 ai-python/.venv/Lib/site-packages/*.dist-info（即已跑过
738 passed / 3 skipped 的那套依赖），排除 pip / setuptools / wheel / 项目本体。
哈希来源：https://pypi.org/pypi/<name>/<version>/json 的 urls[].digests.sha256，
覆盖该版本全部发行文件。取不到即报错退出，**绝不编造 hash**。

仅 Windows 可安装的发行包（如 pywin32）自动加 `; sys_platform == "win32"` marker，
避免 Linux CI 上找不到匹配发行包；hash 仍照常列出。

CI 安装方式（见 .github/workflows/ci.yml）：
    pip install --require-hashes -r requirements.lock.txt
    pip install --no-deps -e .
"""
from __future__ import annotations

import argparse
import json
import sys
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
VENV = ROOT / "ai-python" / ".venv" / "Lib" / "site-packages"
LOCK = ROOT / "ai-python" / "requirements.lock.txt"
EXCLUDE = {"pip", "setuptools", "wheel", "distribute", "docqa-ai"}

HEADER = """# DocQA ai-python 依赖锁文件（G15：精确版本 + 真实 --hash）
# 由 ops/gen-requirements-hashes.py 生成：版本取自本机 ai-python/.venv 的
# dist-info（即通过测试的那套依赖），hash 取自 PyPI JSON API，覆盖全部发行文件。
# 请勿手工编辑；重新生成：python ops/gen-requirements-hashes.py
# CI 用 `pip install --require-hashes -r requirements.lock.txt` 离线可复现安装，
# 项目本体再以 `pip install --no-deps -e .` 单独安装（--require-hashes 不接受 -e）。
# 校验：python ops/gen-requirements-hashes.py --check
"""


def win_only(urls: list[dict]) -> bool:
    """该版本的全部发行文件是否都只适用于 Windows。"""
    for item in urls:
        filename = item.get("filename", "").lower()
        if any(tag in filename for tag in ("win32", "win_amd64", "win-arm64")) or filename.endswith(".exe"):
            continue
        return False
    return True


def fetch(name: str, version: str) -> tuple[list[str], bool]:
    url = f"https://pypi.org/pypi/{name}/{version}/json"
    try:
        with urllib.request.urlopen(url, timeout=30) as response:
            data = json.load(response)
    except (urllib.error.URLError, TimeoutError) as exc:
        raise RuntimeError(f"无法获取 {name}=={version} 的 PyPI 元数据：{exc}") from exc
    urls = data.get("urls", [])
    hashes = sorted({u["digests"]["sha256"] for u in urls if "sha256" in u.get("digests", {})})
    if not hashes:
        raise RuntimeError(f"{name}=={version} 在 PyPI 上没有 sha256 记录，拒绝生成无 hash 的锁行")
    return hashes, win_only(urls)


def collect_pins() -> list[tuple[str, str]]:
    if not VENV.is_dir():
        raise RuntimeError(f"未找到 {VENV}；请先创建 ai-python/.venv 并安装依赖")
    pins: dict[str, str] = {}
    for info in sorted(VENV.glob("*.dist-info")):
        stem = info.name[: -len(".dist-info")]
        if "-" not in stem:
            continue
        name, version = stem.rsplit("-", 1)
        norm = name.replace("_", "-").lower()
        if norm in EXCLUDE:
            continue
        pins[norm] = version
    if not pins:
        raise RuntimeError("venv 中没有解析到任何发行包")
    return sorted(pins.items())


def render(pins: list[tuple[str, str]]) -> str:
    lines = [HEADER.rstrip(), ""]
    for name, version in pins:
        hashes, windows_only = fetch(name, version)
        marker = ' ; sys_platform == "win32"' if windows_only else ""
        lines.append(f"{name}=={version}{marker} \\")
        for index, digest in enumerate(hashes):
            tail = " \\" if index < len(hashes) - 1 else ""
            lines.append(f"    --hash=sha256:{digest}{tail}")
    return "\n".join(lines) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true", help="只校验，不写回")
    args = parser.parse_args()

    pins = collect_pins()
    print(f"从 venv 解析到 {len(pins)} 个精确 pin，开始联网取真实 sha256 ...")
    rendered = render(pins)
    if args.check:
        if not LOCK.is_file() or rendered.rstrip() != LOCK.read_text(encoding="utf-8").rstrip():
            print("锁文件与生成结果不一致，请重新运行生成脚本。", file=sys.stderr)
            return 1
        print("check OK：锁文件哈希与 PyPI 一致。")
        return 0
    LOCK.write_text(rendered, encoding="utf-8")
    print(f"wrote {LOCK}（{len(pins)} pins，全部带真实 PyPI sha256）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
