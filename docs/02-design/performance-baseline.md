# 检索链路性能基线（本地单机）

> **口径声明**：以下是本机离线压测基线，**不是线上生产指标**。项目目前没有公网部署与真实流量，因此不提供也无法编造生产 QPS、SLA 或成本曲线。可对外展示的是「可复现的压测方法 + 实测数据 + 部署就绪检查」。

## 范围

只测「问句编码 → 与候选块余弦打分 → 排序取 top5」这段纯计算链路。不含 HTTP、数据库、LLM、rerank、网络 IO 与任何中间件。

- 候选块：8 个真实法规条文块（见 [retrieval-golden-set.md](retrieval-golden-set.md)）
- 查询池：黄金集 32 条正例，轮询取用
- 向量：离线确定性特征哈希，1024 维
- 脚本：[`scripts/benchmark_retrieval.py`](../../scripts/benchmark_retrieval.py)
- 原始数据：[`performance-baseline.json`](performance-baseline.json)

复现：

```bash
python scripts/benchmark_retrieval.py --iterations 2000 --warmup 100 --workers 1,4,8
```

## 环境（2026-11-25 实测）

| 项 | 值 |
|---|---|
| Python | 3.14.7 |
| 系统 | Windows 11 (10.0.26200) |
| CPU | Intel64 Family 6 Model 186 Stepping 2，16 逻辑核 |
| 预热 | 100 次 |
| 每档迭代 | 2000 次 |
| 错误数 | 0（三档均为 0） |

## 结果

| 并发 | QPS | 平均 (ms) | p50 (ms) | p95 (ms) | p99 (ms) | 最大 (ms) |
|---|---|---|---|---|---|---|
| 1 | **817.3** | 1.222 | 1.143 | 1.764 | **2.005** | 3.758 |
| 4 | 653.2 | 5.852 | 1.516 | 32.056 | 125.345 | 295.329 |
| 8 | 699.9 | 10.825 | 1.417 | 48.023 | 297.814 | 526.256 |

## 结论

1. **单线程即可稳定在亚毫秒级 p50 与 2ms 以内 p99**，说明离线检索算法本身不是瓶颈。
2. **加线程反而更慢**：4/8 线程 QPS 分别降到 653 / 700，p99 恶化到 125ms / 298ms。原因是这段链路是纯 Python CPU 密集计算，受 GIL 限制，多线程只增加上下文切换与调度抖动，尾延迟被显著放大。
3. **扩展方向应是多进程或把打分下沉到向量数据库**，而不是在请求线程里加并发。若未来切真 embedding（外部 API），瓶颈会整体转移到网络与 provider 限流，本基线不再适用，需按同一脚本重测。
4. 本结果只覆盖 8 个候选块；块数增长后打分是线性扫描，需按量级重新评估是否引入 ANN 索引。

## 部署就绪检查（非生产数据）

| 检查 | 状态 | 证据 |
|---|---|---|
| 全栈编排语法与环境变量插值 | 通过 | `docker compose -f ops/docker-compose.infra.yml -f ops/docker-compose.app.yml config -q` |
| Java 控制面测试 | 1418 项通过 | `mvn verify` / CI `Java 17 / mvn verify` |
| Python 数据面测试 | 896 项通过（5 跳过） | `pytest` / CI `Python / pytest + ruff` |
| 前端类型、规范、单测与构建 | 696 项通过 | `npm run typecheck && lint && test && build` |
| 跨语言契约与真库门禁 | 通过 | CI `跨服务边界 e2e（三条真实边界）` |
| 双租户 HTTP 全链演练 | 见专项报告 | [dual-tenant-e2e-drill.md](../00-infra/dual-tenant-e2e-drill.md) |
| 检索质量与回归基线 | 见专项报告 | [retrieval-eval.md](retrieval-eval.md) / [retrieval-golden-set.md](retrieval-golden-set.md) |

**未覆盖**：公网部署、真实并发流量、长时间稳定性、跨机房故障、实际模型成本。这些需要真实运行环境才能产出，不应以本地数字替代。
