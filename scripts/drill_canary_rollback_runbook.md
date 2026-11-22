# 批次 4 / 灰度与回滚演练 runbook — DocQA（可执行步骤骨架）

> **诚实边界**：本机**没有真实生产环境、没有真实业务数据、没有真实凭据**，
> **未做真实灰度 / 真实回滚演练**，没有产生可归档的演练记录。本文件只交付
> 可执行的步骤骨架与判据模板；真跑需在预发/生产由有权限的人执行并填写第 7 节。
> 本文件为新增脚手架，不修改任何现有源码 / 脚本 / 配置 / `.github/**` / `ops/**`。

---

## 0. 前置条件（缺一不启动）

| # | 检查 | 命令 / 依据 |
| --- | --- | --- |
| P1 | 制品按 **digest** 可部署（G17），版本单一来源 | `docker inspect --format '{{index .RepoDigests 0}}' <image>`；`pom.xml`/`package.json`/`pyproject.toml` 版本与 CHANGELOG/tag 一致 |
| P2 | 本轮修复已按报告第 5 节顺序发布（**写侧强制 tenant_id 的代码先上线**） | `修复执行报告.md` 第 5 节 |
| P3 | 回填已完成且 `unresolvedDocuments` / `emptyTenantRows` 均为 0 | `python -m docqa_ai.maintenance.backfill_tenant_id --mapping ...`（回填后复跑） |
| P4 | `DOCQA_ENV=prod` + `DOCQA_AI_INTERNAL_TOKEN` 已注入，`api_host` / `/metrics` 暴露面符合拓扑 | 生产守卫 fail-fast；`CONTRIBUTING.md` |
| P5 | SLO 与告警规则已加载、错误预算基线已取 | 同目录 `batch4_slo_alerts.yaml` |
| P6 | 监控面板：`/health/ready`、ask 延迟/错误率、PG 连接池、MQ 堆积、Redis PING | — |
| P7 | 回滚窗口与审批人已就位；旧版本 digest 已记录 | 第 3 节 |

> **数据面红线**：`migrations/005_drop_tenant_default.sql` 是 **DDL 且回填已改写存量数据**。
> 它把两表 `tenant_id` 的 `DEFAULT ''` 删除，使漏传租户的 INSERT 直接报错。
> 因此**回滚不能简单 `DROP`/撤销 005**：见第 5 节「数据面回滚特别处理」。

---

## 1. 记录基线（灰度开始前，必须落盘）

```bash
# 当前稳定版本 digest 与版本
CURRENT_DIGEST=$(docker inspect --format '{{index .RepoDigests 0}}' docqa-ai:<current-tag>)
echo "$CURRENT_DIGEST" | tee /var/log/docqa/batch4-canary/digest-before.txt
# 待发布版本 digest
NEW_DIGEST=$(docker inspect --format '{{index .RepoDigests 0}}' docqa-ai:<new-tag>)
# 当前错误预算余量、QPS、p99、PG 连接峰值、MQ 堆积深度（从 Prometheus 取，记录时间戳）
```

---

## 2. 灰度步骤（按实例 / 流量比例逐级放量）

| 级别 | 动作 | 停留 | 观测 | 晋级判据 |
| --- | --- | --- | --- | --- |
| C0 5% | 按 digest 起 1 个新实例，LB 导入 5% 流量 | ≥ 15 min | 第 3 节全部指标 | 错误率 < SLO、p99 < 阈值、租户隔离断言 100%、无 `DOC-TENANT-001` 异常增量 |
| C1 25% | 扩到 25% | ≥ 30 min | 同上 | 同上，且 PG 连接峰值 <= 池上限、`/health/ready` 无抖动 |
| C2 50% | 扩到 50% | ≥ 30 min | 同上 + 跨月/高峰时段 | 同上 |
| C3 100% | 全量切到新 digest，旧实例保留 **热备 1 个** 至少 1 个回滚窗口 | ≥ 1 h | 同上 | 同上 |

每一级执行骨架（占位命令，按真实编排器替换）：

```bash
# 按 digest 拉起新版本实例（禁止用可移动 tag）
docker run -d --name docqa-ai-canary --env-file /etc/docqa/prod.env \
  "$NEW_DIGEST"  # 按 digest 部署
# LB 调整权重（占位）
# TODO: 按你的 LB（nginx / k8s ingress / 网关）设置 5% / 25% / 50% / 100%
curl -fsS http://127.0.0.1:8000/health/ready   # 必须 200 后才放量
```

---

## 3. 每级必看的观测与判据

- **可用性**：`/agent/ask` 5xx 率、`/health/ready` 200 比例（依赖 down 应为 503 并被摘除）。
- **延迟**：p50 / p95 / p99，与基线对比不得劣化超过阈值（见 `batch4_slo_alerts.yaml`）。
- **租户隔离**（本次 P0 修复的核心）：同租户引用命中、异租户 0 引用；`tenant_id=''` 行数必须为 0。
- **写侧 tenant 信封**：日志中 `DOC-TENANT-001` / `INVALID` 不应因正常流量新增；出现即说明写路径漏传。
- **连接池**：`pg_stat_activity` 应用连接峰值 <= `pool_max_size + 余量`，且不随 QPS 线性增长（B2 修复判据）。
- **MQ**：堆积深度回落、DLQ 无异常增量、无「FAILED 永久 ACK」。
- **错误预算**：任一级消耗超过预算的 X% 立即停止晋级并评估回滚。

---

## 4. 回滚触发条件（任一命中即回滚）

1. `/agent/ask` 错误率或 p99 突破 SLO 且持续超过 `for` 窗口；
2. 出现**跨租户数据可见**（异租户命中 != 0）——最高优先级，立即全量回滚；
3. 出现漏传 `tenant_id` 的写入失败（说明写侧代码与 005 不匹配）；
4. PG 连接数打满 `max_connections`；
5. MQ 堆积不回落 / DLQ 激增；
6. readiness 持续 503 且无法自愈。

---

## 5. 回滚步骤（按 digest）

```bash
# R1 立刻把流量切回旧 digest（热备实例还在）
# TODO: LB 权重 100% -> 上一版本实例组
# R2 按 digest 重新部署上一稳定版本（禁止 latest / 可移动 tag）
docker run -d --name docqa-ai-rollback --env-file /etc/docqa/prod.env "$CURRENT_DIGEST"
# R3 验证旧版本 readiness + 同租户问答 + 异租户 0 引用
curl -fsS http://127.0.0.1:8000/health/ready
# R4 下线新版本实例（保留日志与报表归档）
```

### 5.1 数据面回滚特别处理（**不要误操作**）

- 005 已删除两表 `tenant_id` 的默认值，且回填已把存量空串行改为真实租户。
- **不要**为了「回滚代码」而重新给列加 `DEFAULT ''`：那会重新打开「漏传租户静默写空串」的缺口，等于把 P0 放回来。
- 若确需回退到「写侧不带 tenant_id」的旧代码，必须**连数据面一起回退**：评估重建默认值 + 重新回填/重投索引，属于变更审批事项，不在自动回滚脚本内。
- 推荐回滚路径：**只回滚应用镜像到上一个「写侧强制 tenant_id」的兼容版本**，保留 005 的收口状态不动；这样新旧代码都带租户，数据面无需回退。
- 任何 DDL 回滚前先备份 `pg_dump` 两表结构与 `information_schema.columns.column_default`。

---

## 6. 灰度 / 回滚演练的机检产物（真跑时生成）

- Prometheus 快照（每级起止时间戳）；
- 租户隔离断言脚本输出（`batch4_e2e_crossprocess.py --execute --report ...`）；
- 连接池压测报表（`batch4_loadtest_pgpool.py --execute --report ...`）；
- 故障注入观测（`batch4_fault_injection.sh <case> --apply --target ...`）；
- 本 runbook 第 7 节填写的演练记录。

---

## 7. 演练记录模板（**本机未填写，未做演练**）

| 字段 | 值 |
| --- | --- |
| 演练日期 / 环境 | （未执行） |
| 当前 digest → 新 digest | （未执行） |
| 灰度级别与停留时长 | （未执行） |
| 每级 SLO 观测值 | （未执行） |
| 是否触发回滚 / 触发条件 | （未执行） |
| 回滚耗时 / 按 digest 验证结果 | （未执行） |
| 结论（成功 / 失败及原因） | （未执行） |
| 归档路径 | （未执行） |

---

## 8. 与报告第 5 节的关系

本 runbook 的发布顺序**必须**服从 `修复执行报告.md` 第 5 节：
先部署写侧强制 tenant_id 代码 → 导出映射 dry-run → `--apply` → 005 → 配置 prod/token → CI 真库契约 → 合并 PR。
灰度是「部署」这一段的放量策略，不改变、不能跳过上述顺序。
