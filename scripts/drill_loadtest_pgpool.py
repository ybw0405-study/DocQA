#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""批次 4 / 性能压测脚本骨架 — 重点：DocQA PG 连接池（B2）。

背景：修复前 `open_pool()` 全仓零调用，每次问答新建 2 条 PG 连接，
几十 QPS 即打满 max_connections（方案 B2 / 修复执行报告 1.2）。
本压测要验证「单一组装根接线后，并发上升时 PG 连接数由池封顶、不随请求线性增长」。

观测指标：
  * /agent/ask p50 / p95 / p99 延迟、错误率、吞吐；
  * pg_stat_activity 中 docqa 应用连接数峰值；
  * 断言：峰值 <= pool_max_size + 余量，且远小于 并发数 * 2（未接线的特征）；
  * 断言：错误率低于 SLO（见 drill_slo_alerts.yaml）。

【脚手架定位 / 诚实边界】
  * 这是可执行骨架，**本机未做真实压测**：没有生产/预发环境、没有真实流量，
    没有产生任何压测报告与实测数值。
  * 默认 dry-run：只打印并发梯度与观测项，不发请求、不连 PG。
  * --execute 才按梯度发请求；PG 连接采样需 psycopg（缺失则跳过并 WARN），
    端点/令牌/DSN 全部由调用方提供。

运行：
  python drill_loadtest_pgpool.py                          # dry-run（默认）
  python drill_loadtest_pgpool.py --execute --ask-url http://127.0.0.1:8000/agent/ask \
      --dsn postgresql://docqa:xxx@127.0.0.1:5432/docqa_vec --internal-token xxx \
      --concurrency 10 50 100 200 --duration 30 --max-pg-connections 40 --report out.json
"""
from __future__ import annotations

import argparse
import concurrent.futures
import json
import statistics
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field

DEFAULT_GRADIENT = [10, 50, 100, 200]

METRICS = [
    "ask_latency_p50/p95/p99_ms",
    "ask_error_rate",
    "throughput_rps",
    "pg_app_connections_peak",
    "pg_app_connections_vs_2x_concurrency",  # 未接线的特征：~2*并发
    "pool_exhausted_errors",
]


@dataclass
class LoadResult:
    execute: bool
    rows: list[dict] = field(default_factory=list)

    def dump(self, path: str | None) -> dict:
        report = {
            "kind": "drill-loadtest-pgpool",
            "executed": self.execute,
            "note": "骨架自检；本机未做真实压测，无实测数值" if not self.execute
                    else "真实执行；数值由调用方环境产生",
            "metrics": METRICS,
            "rows": self.rows,
        }
        if path:
            with open(path, "w", encoding="utf-8") as fh:
                json.dump(report, fh, ensure_ascii=False, indent=2)
        return report


def sample_pg_connections(dsn: str) -> int | None:
    """采样 docqa 应用连接数；psycopg 不可用时返回 None（调用方需 WARN）。"""
    try:
        import psycopg  # noqa: PLC0415
    except ImportError:
        return None
    try:
        with psycopg.connect(dsn, connect_timeout=3) as conn:
            with conn.cursor() as cur:
                cur.execute("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() "
                            "AND application_name LIKE 'docqa%';")
                return int(cur.fetchone()[0])
    except Exception:  # noqa: BLE001
        return None


def one_request(url: str, body: dict, headers: dict, timeout: float) -> tuple[bool, float]:
    req = urllib.request.Request(url, data=json.dumps(body).encode("utf-8"),
                                 method="POST", headers=headers)
    t0 = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            resp.read()
            return (200 <= resp.status < 300), (time.perf_counter() - t0) * 1000
    except Exception:  # noqa: BLE001
        return False, (time.perf_counter() - t0) * 1000


def percentile(values: list[float], p: float) -> float:
    if not values:
        return 0.0
    s = sorted(values)
    k = min(len(s) - 1, int(round((len(s) - 1) * p)))
    return round(s[k], 2)


def run_level(level: int, args: argparse.Namespace) -> dict:
    headers = {"Content-Type": "application/json", "X-Internal-Token": args.internal_token}
    body = {"tenantId": args.tenant, "question": "drill load probe", "documentScopes": []}
    deadline = time.time() + args.duration
    lat: list[float] = []
    errors = 0
    pg_peak = 0
    pg_available = True
    with concurrent.futures.ThreadPoolExecutor(max_workers=level) as pool:
        while time.time() < deadline:
            batch = [pool.submit(one_request, args.ask_url, body, headers, args.timeout)
                     for _ in range(level)]
            for fut in concurrent.futures.as_completed(batch):
                ok, ms = fut.result()
                lat.append(ms)
                if not ok:
                    errors += 1
            conn = sample_pg_connections(args.dsn)
            if conn is None:
                pg_available = False
            else:
                pg_peak = max(pg_peak, conn)
            time.sleep(0.2)
    total = len(lat) or 1
    row = {
        "concurrency": level,
        "requests": len(lat),
        "error_rate": round(errors / total, 4),
        "p50_ms": percentile(lat, 0.50),
        "p95_ms": percentile(lat, 0.95),
        "p99_ms": percentile(lat, 0.99),
        "throughput_rps": round(len(lat) / args.duration, 2),
        "pg_app_connections_peak": pg_peak if pg_available else None,
        "pg_sampling": "ok" if pg_available else "SKIPPED: psycopg/DSN 不可用",
        "pool_ceiling_assert": (pg_peak <= args.max_pg_connections) if pg_available else None,
        "no_linear_growth_assert": (pg_peak < 2 * level) if pg_available else None,
    }
    return row


def run(args: argparse.Namespace) -> int:
    r = LoadResult(execute=args.execute)
    print("=" * 72)
    print("批次4 PG 连接池压测骨架 | execute =", args.execute)
    print("并发梯度:", args.concurrency, "| 每级时长:", args.duration, "s")
    print("观测指标:", ", ".join(METRICS))
    print("=" * 72)
    if not args.execute:
        for c in args.concurrency:
            r.rows.append({"concurrency": c, "dry_run": True,
                           "would_assert": f"pg_app_connections_peak <= {args.max_pg_connections} 且 < 2*{c}"})
            print(f"  [dry-run] concurrency={c}: 登记压测级别，未发请求、未连 PG")
        r.dump(args.report)
        print("\n骨架自检完成；本机未做真实压测，无实测数值。加 --execute 才会真实打流。")
        return 0

    for c in args.concurrency:
        print(f"\n---- 压测级别 concurrency={c} ----")
        row = run_level(c, args)
        r.rows.append(row)
        print(" ", json.dumps(row, ensure_ascii=False))
    r.dump(args.report)
    bad = [x for x in r.rows if x.get("pool_ceiling_assert") is False
           or x.get("no_linear_growth_assert") is False or x["error_rate"] > 0.01]
    print(f"\n真实执行骨架结果：{len(r.rows) - len(bad)}/{len(r.rows)} 级别通过"
          f"（PG 采样不可用的级别需人工复核）")
    return 1 if bad else 0


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description="DocQA 批次4 PG 连接池压测骨架（默认 dry-run）")
    p.add_argument("--execute", action="store_true")
    p.add_argument("--ask-url", default="http://127.0.0.1:8000/agent/ask")
    p.add_argument("--dsn", default="")
    p.add_argument("--internal-token", default="")
    p.add_argument("--tenant", default="11111111-1111-1111-1111-111111111111")
    p.add_argument("--concurrency", type=int, nargs="+", default=DEFAULT_GRADIENT)
    p.add_argument("--duration", type=float, default=30.0)
    p.add_argument("--timeout", type=float, default=5.0)
    p.add_argument("--max-pg-connections", type=int, default=40)
    p.add_argument("--report", default="")
    return run(p.parse_args(argv))


if __name__ == "__main__":
    raise SystemExit(main())
