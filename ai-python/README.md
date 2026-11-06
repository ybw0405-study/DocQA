# ai-python

智能学习助手的 AI 数据面：`POST /agent/ask` 治理问答与文档解析 Worker。Java 侧只通过
`agent-gateway` 调用本服务，RAG/LLM/Embedding 只在 Python 侧实现。

## 组成

| 进程 | 入口 | 职责 |
| --- | --- | --- |
| API | `docqa-api` / `python -m docqa_ai.api.main` | 协议校验、PII 双层门禁、预算、Kill Switch、RAG、LangGraph、审计事件 |
| Parse Worker | `docqa-parse-worker` / `python -m docqa_ai.parse_worker.worker` | 消费 `parse-requested`，解析 PDF/DOCX/PPTX/TXT，写入 PGVector，发布 `parse-completed` / `parse-failed` |
| Vector Worker | `docqa-vector-worker` / `python -m docqa_ai.parse_worker.vector_worker` | 消费 `vector-change-requested`，按 `documentId`/`indexVersion` 删除 PGVector 作用域，回投 `vector-change-completed`（含 `remainingRows`） |

## 本地运行

```bash
cd ai-python
python -m venv .venv
.venv/Scripts/pip install -e ".[dev]"       # Windows
# .venv/bin/pip install -e ".[dev]"         # Linux/macOS

.venv/Scripts/python -m uvicorn docqa_ai.api.main:app --port 8000
.venv/Scripts/docqa-parse-worker
.venv/Scripts/docqa-vector-worker   # 两个 Worker 只能在 Linux 运行位执行（librocketmq.so）
```

API 未配置 `DOCQA_AI_PG_DSN` 时仍可启动，检索会 fail-closed 为 `AGT-UPSTREAM-001`。
两个 Worker 都没有离线消息通道：必须同时满足 `DOCQA_AI_MQ_ENABLED=true` 与
`DOCQA_AI_PG_DSN` 非空（Parse Worker 还要求 `DOCQA_AI_MINIO_ENABLED=true`），
否则进程以退出码 2 拒绝启动，避免把「未接线」误当成「已消费」。

Windows 侧 `pip` 能装上 `rocketmq-client-python`，但 `import` 即失败（其依赖的 `librocketmq.so`
仅提供 Linux 绑定），因此 Worker 必须跑在 WSL/Linux：`docs/00-infra/setup-parse-worker.sh start [parse|vector|all]`。

首次启动 Worker 前，在 PGVector 库**按序号**执行迁移（`001` 与 `002` 都要执行）：

```bash
psql "$DOCQA_AI_PG_DSN" -f ai-python/migrations/001_chunk_embedding_1024.sql
psql "$DOCQA_AI_PG_DSN" -f ai-python/migrations/002_chunk_lexical_index.sql
```

## 向量表与模型切换

向量空间由 **模型 + 预处理口径** 两件事定义。真实 embedding 与离线特征哈希伪向量
（`OfflineEmbeddingProvider`）不同源，而 HNSW 与列绑定、无法事后按 `embedding_model` 过滤——
混在一张表里的表现是相似度排序静默失真，不报错。因此换模型或改掩码口径都要**新建一张表**。

`003_chunk_embedding_1024_text_embedding_v4.sql` 即为此准备的迁移：切空间只需
`DOCQA_AI_PG_TABLE` 改值并执行 `003`，读写两侧零 SQL 改动（表名插值）。
`002` 只建一个表达式 GIN 索引，零新列、零数据改写、不动 HNSW，可重复执行。

## Provider 与额度

默认使用离线确定性 Provider（`DOCQA_AI_LLM_PROVIDER=offline`、
`DOCQA_AI_EMBEDDING_PROVIDER=offline`），不访问外网，可用于本地联调与测试。
生产环境通过环境变量切换到 OpenAI 兼容服务：

```bash
DOCQA_AI_LLM_PROVIDER=openai
DOCQA_AI_LLM_BASE_URL=https://llm.example.internal/v1
DOCQA_AI_LLM_API_KEY=...          # 仅从环境变量注入，不入库
DOCQA_AI_LLM_MODEL=...
DOCQA_AI_EMBEDDING_PROVIDER=openai
DOCQA_AI_EMBEDDING_BASE_URL=https://embedding.example.internal/v1
DOCQA_AI_EMBEDDING_API_KEY=...
DOCQA_AI_EMBEDDING_MODEL=...
DOCQA_AI_RERANK_PROVIDER=dashscope
DOCQA_AI_RERANK_BASE_URL=https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank
DOCQA_AI_RERANK_API_KEY=...       # 仅从环境变量注入，不入库
DOCQA_AI_RERANK_MODEL=gte-rerank-v2
DOCQA_AI_RERANK_TIMEOUT_MS=3000
DOCQA_AI_EXPERIMENT_BUDGET=200            # 真实调用次数上限，默认 0
DOCQA_AI_EXPERIMENT_TOKEN_BUDGET=200000   # 真实 token 上限，默认 0
```

额度是**运行时强制执行**的：四个外呼出口（检索 `_embed`、解析 worker `_embed`、planner 生成、
rerank）都在字节离开本机**之前** `authorize_call` 预订，返回后生成侧用真实 `usage` 结算。
任一 Provider 切到真实模式时，两个额度必须同时为正，否则**启动即失败**——配置遗漏不能表现为静默放行。

额度账本落在 Redis `agent:budget:session-tokens`（hash，字段 `callsUsed` / `tokensUsed`，不设过期；
启动时另写两个只读回显字段 `callLimit` / `tokenLimit` 供管理面展示）。

共享中间件地址同样只来自环境变量：

- `DOCQA_REDIS_URL`
- `DOCQA_AI_PG_DSN`（PGVector，表由 `DOCQA_AI_PG_TABLE` 指定，默认 `chunk_embedding_1024`）
- `DOCQA_ROCKETMQ_NAMESRV`、`DOCQA_AI_MQ_ENABLED=true`
- `DOCQA_MINIO_ENDPOINT` / `DOCQA_MINIO_ACCESS_KEY` / `DOCQA_MINIO_SECRET_KEY`

仓库中不保存任何真实凭据；`.env` 已被忽略。

## 配置项

全部配置使用 `DOCQA_AI_` 前缀（共享变量除外），见 `src/docqa_ai/config.py`。
关键项：

- `DOCQA_AI_LOW_CONFIDENCE_THRESHOLD`（默认 0.55）
- `DOCQA_AI_RETRIEVAL_TOP_K`、`DOCQA_AI_RETRIEVAL_MIN_SCORE`
- `DOCQA_AI_LLM_TIMEOUT_MS`、`DOCQA_AI_EMBEDDING_TIMEOUT_MS`
- `DOCQA_AI_RERANK_PROVIDER`、`DOCQA_AI_RERANK_BASE_URL`、`DOCQA_AI_RERANK_API_KEY`、
  `DOCQA_AI_RERANK_MODEL`、`DOCQA_AI_RERANK_TIMEOUT_MS`（默认 `offline` / 3000 ms）
- `DOCQA_AI_EXPERIMENT_BUDGET`、`DOCQA_AI_EXPERIMENT_TOKEN_BUDGET`（默认均为 0）：
  账本 `agent:budget:session-tokens` **只在任一 Provider 切真时才构造**，三个 Provider 全离线时
  `build_experiment_ledger()` 返回 `None`，该键一次都不读写
- `DOCQA_AI_PG_TABLE`（默认 `chunk_embedding_1024`）：向量空间的名字，读写两侧都从这里插值。
  取值受 `_TABLE_NAME` 白名单（`^[a-z][a-z0-9_]{0,63}$`）约束，PG 标识符上限 63 字符
- `DOCQA_AI_CHUNK_SIZE_CHARS`、`DOCQA_AI_CHUNK_OVERLAP_CHARS`
- `DOCQA_AI_EMBEDDING_BATCH_SIZE`（默认 32，允许 1–128）：解析 Worker 实际每批行数取
  `min(该值, Provider 自报的 max_batch_rows)`
- `DOCQA_AI_OTEL_ENABLED`、`DOCQA_AI_OTEL_ENDPOINT`

启动时会输出 `redacted_summary()`，不包含任何密钥、DSN 或提示词内容。

## RocketMQ 客户端与 Worker 幂等

`rocketmq-client-python` 依赖 C++ SDK，部分平台/Python 版本无法直接安装，因此作为可选
extra（`.[mq]`）提供。生产部署必须安装 `.[mq]`、设置真实 NameServer，并显式开启 MQ；
未安装、未开启或缺少 MinIO/PG 配置时 Worker 直接退出，不会退化为内存通道。

### 解析链路

- 消费：`parse-requested`，消费组 `ai-python-parse-worker`；`RECONSUME_LATER` 交给
  RocketMQ 默认重试与死信策略，非法事件 ACK 丢弃，避免毒消息无限阻塞。
- 生产：`parse-completed` / `parse-failed`，生产组 `ai-python-parse-producer`，消息键为
  `documentId`，属性携带 `traceId` 与 `traceparent`；`parse-completed` 的 `vectorRows`
  由同事务 `COUNT` 得出，供 Java 侧做行数对账。
- 幂等：Redis `mq:idem:parse-requested:{messageId}`，`processing` 900 秒、`done` 7 天；
  `BUSY` 返回重试，`DONE` 直接 ACK，发布失败释放键等待重投。
- 写入：单个 `(documentId, indexVersion)` 事务内 `DELETE` + 参数化 `INSERT`，
  `vector_id` 为 `documentId/indexVersion/chunkIndex` 的 UUIDv5，重试结果稳定。

### 向量变更链路

- 消费：`vector-change-requested`，消费组 `ai-python-vector-worker`（独立短超时 60 秒，
  避免卡住的解析消息阻塞删除）。
- 生产：`vector-change-completed`，生产组 `ai-python-vector-producer`，消息键为
  `documentId`；回投固定携带 `requestEventId`，载荷不含 `userId`。
- 幂等：Redis `mq:idem:vector-change-requested:{messageId}`，`processing` 120 秒、
  `done` 7 天，与解析链路命名空间隔离。
- 删除谓词只有 `documentId`（`DELETE_DOCUMENT` 全版本）与 `documentId + indexVersion`
  （`DELETE_VERSION`），`userId` 永不是谓词；作用域缺失或操作与谓词不匹配时回投
  `FAILED` + `COM-VALIDATION-001` 且**完全不碰 PG**，绝不退化成删整篇文档。
- `remainingRows` 与 `DELETE` 在同一事务内 `COUNT`，COMPLETED 必带数值、FAILED 必为
  `null` 并带错误码与文案（模型层校验）。数据库异常回投 `FAILED` + `COM-INTERNAL-001`，
  文案固定、不泄漏 DSN；靠 Java 补投扫描收敛，不做自动修复。

## 数据落地边界

- Python 只写 PGVector 的一张向量表（`DOCQA_AI_PG_TABLE`，默认 `chunk_embedding_1024`），
  不写 MySQL `document` / `chunk`。离线伪向量与真实模型向量分属两张表，不可互灌。
- **出网前掩码**：解析 Worker 的 `_embed` 唯一出口在把文本交给任何 embedding Provider 之前
  逐条 `mask_text`，与查询侧同源同规则（`pii.masker.VERSION = pii-v1`，与 Java 一致）。
  读侧同一条红线由 rerank 通道补齐：候选正文在**名次决定之前**逐篇 `mask_text`。两处都
  **只是改变离开本机的字节**，落库的 `content` 与 `content_hash` **仍是原文**。
- 审计事件随响应返回，由 Java `audit-service` 落库；仅在响应失败时走 `audit-event` 兜底。
- 日志与审计 payload 不包含问题、回答、提示词、文档正文等原文，PII 命中只记录类别与数量。

## 验证

```bash
.venv/Scripts/python -m pytest
.venv/Scripts/python -m ruff check src tests
.venv/Scripts/python -m ruff format --check src tests
```

测试不依赖真实 Redis/RocketMQ/PGVector/MinIO：Redis 使用 `fakeredis`，向量与对象存储使用内存
实现，模型默认离线。
