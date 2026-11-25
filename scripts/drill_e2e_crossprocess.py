#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""批次 4 / 跨进程全链 e2e 脚本骨架 — DocQA。

目标链路（真实环境才有意义）：
  Java 控制面上传文档 -> RocketMQ parse-requested(带 tenantId 信封) ->
  Python parse_worker 写 PG 向量表(tenant_id 必填) -> /agent/ask 检索 ->
  断言：同租户引用命中、异租户 0 引用、缺 tenant 信封的写路径被拒收。

【脚手架定位 / 诚实边界】
  * 这是可执行骨架，不是已跑通的 e2e。本机没有真实生产环境、真实业务数据、
    真实凭据，也未启动 Java/Python/MQ 服务，因此**没有产生任何 e2e 记录**。
  * 默认 dry-run：只打印阶段计划与断言清单，不发起任何网络请求、不碰数据库。
  * 仅显式 --execute 且提供真实端点/令牌才发请求；各阶段 URL 为占位常量，
    需按 docs/02-design/openapi-java.yaml 与 ai-python 实际路由校准（见 TODO）。
  * 本文件为新增脚手架，不修改任何现有源码 / 脚本 / 配置。

运行：
  python drill_e2e_crossprocess.py # dry-run（默认，安全）
  python drill_e2e_crossprocess.py --execute \
      --java-base-url http://127.0.0.1:8080 --ai-base-url http://127.0.0.1:8000 \
      --internal-token <token> --tenant-a <uuid> --tenant-b <uuid> --report out.json
"""
from __future__ import annotations

import argparse
import json
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field

# TODO(校准)：以下路径按真实 OpenAPI / 路由替换；骨架不假设具体版本。
PATH_UPLOAD = "/api/documents" # Java 控制面：上传并触发 parse-requested
PATH_ASK = "/agent/ask" # ai-python：RAG 问答
PATH_READY = "/health/ready" # readiness 探针（探 PG + Redis）

STAGES = [
    "preflight", # 端点 / 令牌 / 两租户可达性
    "upload_document", # 控制面上传，触发带 tenantId 的 parse-requested
    "wait_indexed", # 等 PG 向量行出现且 tenant_id 非空（真实库查询）
    "ask_same_tenant", # 同租户问答，断言引用命中
    "ask_cross_tenant", # 异租户问答，断言 0 引用
    "missing_tenant_gate", # 缺 tenantId 信封的写路径被边界拒收
    "report",
]

ASSERTIONS = [
    "A1 上传返回 2xx 且 documentId 非空",
    "A2 MQ 事件携带 tenantId 信封（J1：缺字段立即拒收，不静默降级）",
    "A3 PG 向量行 tenant_id == 真实租户、无空串（J2：NOT NULL 无默认）",
    "A4 同租户 /agent/ask 引用数 >= 1",
    "A5 异租户 /agent/ask 引用数 == 0",
    "A6 构造缺 tenantId 的消息/请求被拒（不落库、不进写路径）",
    "A7 全链 traceId 贯通 Java->MQ->Python（OTel，可选）",
]


@dataclass
class Result:
    execute: bool
    checks: list[dict] = field(default_factory=list)
    started: float = field(default_factory=time.time)

    def add(self, stage: str, ok: bool, detail: str) -> None:
        self.checks.append({"stage": stage, "ok": ok, "detail": detail})
        print(f" [{'PASS' if ok else 'FAIL'}] {stage}: {detail}")

    def dump(self, path: str | None) -> dict:
        passed = sum(1 for c in self.checks if c["ok"])
        report = {
            "kind": "drill-crossprocess-e2e",
            "executed": self.execute,
            "note": "骨架自检；本机未接真实服务，未产生真实 e2e 记录" if not self.execute
                    else "真实执行；端点/凭据由调用方提供",
            "passed": passed,
            "total": len(self.checks),
            "checks": self.checks,
            "elapsed_s": round(time.time() - self.started, 3),
        }
        if path:
            with open(path, "w", encoding="utf-8") as fh:
                json.dump(report, fh, ensure_ascii=False, indent=2)
        return report


def http_json(method: str, url: str, body: dict | None, headers: dict, timeout: float) -> tuple[int, dict]:
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read().decode("utf-8", "replace")
            return resp.status, (json.loads(raw) if raw.strip().startswith(("{", "[")) else {"raw": raw})
    except urllib.error.HTTPError as exc:
        return exc.code, {"error": exc.read().decode("utf-8", "replace")}
    except Exception as exc: # noqa: BLE001 - 骨架需把网络异常转成失败项
        return -1, {"error": repr(exc)}


def run(args: argparse.Namespace) -> int:
    r = Result(execute=args.execute)
    print("=" * 72)
    print("批次4 跨进程全链 e2e 骨架 | execute =", args.execute)
    if not args.execute:
        print("DRY-RUN：仅列出阶段与断言，不发起任何请求。加 --execute 才会连真实服务。")
    print("=" * 72)
    print("阶段:", " -> ".join(STAGES))
    print("断言:")
    for a in ASSERTIONS:
        print(" -", a)

    if not args.execute:
        for s in STAGES:
            r.add(s, True, "dry-run：阶段已登记，未执行")
        rep = r.dump(args.report)
        print(f"\n骨架自检完成：{rep['passed']}/{rep['total']}（均为 dry-run 登记，非真实 e2e 结果）")
        return 0

    # ---- 真实执行骨架（需调用方提供端点与令牌）----
    hdr = {"Content-Type": "application/json", "X-Internal-Token": args.internal_token}
    code, _ = http_json("GET", args.ai_base_url + PATH_READY, None, hdr, args.timeout)
    r.add("preflight", code == 200, f"ai-python readiness HTTP {code}")
    code, doc = http_json("POST", args.java_base_url + PATH_UPLOAD,
                          {"tenantId": args.tenant_a, "fileName": "drill-e2e.txt",
                           "content": "drill cross-process probe"}, hdr, args.timeout)
    doc_id = str(doc.get("documentId", "")) if isinstance(doc, dict) else ""
    r.add("upload_document", 200 <= code < 300 and bool(doc_id), f"HTTP {code}, documentId={doc_id!r}")

    # TODO(wait_indexed)：真实实现应查询 PG（DOCQA_AI_PG_DSN），断言
    # SELECT count(*) FROM chunk_embedding_1024 WHERE document_id=%s AND tenant_id=%s
    # 且 tenant_id <> ''。骨架不内嵌 DB 依赖，标记为待接线。
    r.add("wait_indexed", False, "TODO: 接真实 PG 查询/轮询，本骨架不内嵌 DB 客户端")

    ask_body = {"tenantId": args.tenant_a, "question": "drill probe?", "documentScopes": [doc_id] if doc_id else []}
    code, same = http_json("POST", args.ai_base_url + PATH_ASK, ask_body, hdr, args.timeout)
    cites_same = len(same.get("references", same.get("citations", []))) if isinstance(same, dict) else 0
    r.add("ask_same_tenant", code == 200 and cites_same >= 1, f"HTTP {code}, references={cites_same}")

    cross = dict(ask_body, tenantId=args.tenant_b)
    code, other = http_json("POST", args.ai_base_url + PATH_ASK, cross, hdr, args.timeout)
    cites_cross = len(other.get("references", other.get("citations", []))) if isinstance(other, dict) else 0
    r.add("ask_cross_tenant", code == 200 and cites_cross == 0, f"HTTP {code}, references={cites_cross}")

    # TODO(missing_tenant_gate)：向 MQ 投一条不带 tenantId 的 parse-requested，
    # 断言 worker 落 DOC-TENANT-001 / INVALID 且 PG 零行写入。需 MQ admin 端点。
    r.add("missing_tenant_gate", False, "TODO: 接真实 MQ 投递与 PG 零行断言")

    rep = r.dump(args.report)
    print(f"\n真实执行骨架结果：{rep['passed']}/{rep['total']}（TODO 项按 FAIL 计，接线后重跑）")
    return 0 if rep["passed"] == rep["total"] else 1


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description="DocQA 批次4 跨进程全链 e2e 骨架（默认 dry-run）")
    p.add_argument("--execute", action="store_true", help="真正连服务；默认 dry-run")
    p.add_argument("--java-base-url", default="http://127.0.0.1:8080")
    p.add_argument("--ai-base-url", default="http://127.0.0.1:8000")
    p.add_argument("--internal-token", default="")
    p.add_argument("--tenant-a", default="11111111-1111-1111-1111-111111111111")
    p.add_argument("--tenant-b", default="22222222-2222-2222-2222-222222222222")
    p.add_argument("--timeout", type=float, default=5.0)
    p.add_argument("--report", default="")
    return run(p.parse_args(argv))


if __name__ == "__main__":
    raise SystemExit(main())
