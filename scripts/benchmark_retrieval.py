"""DocQA 离线检索链路压测（单机、可复现、不联网）。

只测「问句编码 → 与候选块余弦打分 → 排序取 top_k」这一段纯计算链路，
不含 HTTP、数据库、LLM 与网络 IO，因此结果只能作为检索算法侧的本地基线。
运行：python scripts/benchmark_retrieval.py --iterations 2000 --workers 1,4,8
"""

from __future__ import annotations

import argparse
import concurrent.futures
import json
import os
import platform
import statistics
import sys
import time
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO_ROOT / "ai-python" / "src"))

from docqa_ai.eval.golden import GoldenIndex, load_golden_set  # noqa: E402


def _percentile(values: list[float], fraction: float) -> float:
    ordered = sorted(values)
    position = max(0, min(len(ordered) - 1, int(len(ordered) * fraction)))
    return round(ordered[position] * 1000, 3)


def _run(index: GoldenIndex, questions: list[str], iterations: int, workers: int) -> dict[str, object]:
    latencies: list[float] = []
    errors = 0

    def one(n: int) -> float:
        started = time.perf_counter()
        index.search(questions[n % len(questions)], top_k=5)
        return time.perf_counter() - started

    started = time.perf_counter()
    if workers == 1:
        for n in range(iterations):
            try:
                latencies.append(one(n))
            except Exception:  # noqa: BLE001 - 压测需统计而非中断
                errors += 1
    else:
        with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as pool:
            futures = [pool.submit(one, n) for n in range(iterations)]
            for future in concurrent.futures.as_completed(futures):
                try:
                    latencies.append(future.result())
                except Exception:  # noqa: BLE001
                    errors += 1
    elapsed = time.perf_counter() - started

    return {
        "workers": workers,
        "iterations": iterations,
        "errors": errors,
        "elapsed_s": round(elapsed, 3),
        "qps": round(iterations / elapsed, 1),
        "latency_ms": {
            "mean": round(statistics.fmean(latencies) * 1000, 3),
            "p50": _percentile(latencies, 0.50),
            "p95": _percentile(latencies, 0.95),
            "p99": _percentile(latencies, 0.99),
            "max": round(max(latencies) * 1000, 3),
        },
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="DocQA 离线检索压测")
    parser.add_argument("--iterations", type=int, default=2000)
    parser.add_argument("--warmup", type=int, default=100)
    parser.add_argument("--workers", default="1,4,8", help="逗号分隔的并发档位")
    parser.add_argument("--report", type=Path, help="把结果写入 JSON 文件")
    args = parser.parse_args()

    index = GoldenIndex()
    questions = [item["question"] for item in load_golden_set()["queries"]]
    for n in range(args.warmup):
        index.search(questions[n % len(questions)], top_k=5)

    worker_counts = [int(value) for value in args.workers.split(",") if value.strip()]
    runs = [_run(index, questions, args.iterations, workers) for workers in worker_counts]
    payload = {
        "scope": "offline-feature-hashing retrieval (encode -> cosine -> top5 sort)",
        "disclaimer": "单机本地基线，不含 HTTP / 数据库 / LLM / 网络；不是线上生产指标",
        "environment": {
            "python": platform.python_version(),
            "platform": platform.platform(),
            "processor": platform.processor() or "unknown",
            "cpu_count": os.cpu_count(),
            "candidate_chunks": len(index.chunks),
            "query_pool": len(questions),
            "warmup": args.warmup,
        },
        "runs": runs,
    }

    text = json.dumps(payload, ensure_ascii=False, indent=2)
    print(text)
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        with args.report.open("w", encoding="utf-8", newline="\n") as handle:
            handle.write(text + "\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
