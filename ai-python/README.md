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
仅提供 Linux 绑定），因此 Worker 必须跑在 Linux 容器里：
`docker compose -f ops/docker-compose.infra.yml -f ops/docker-compose.app.yml up -d parse-worker vector-worker`
（镜像复用 `ai-python` target，entrypoint 分别为 `docqa-parse-worker` / `docqa-vector-worker`）。

首次启动 Worker 前，在 PGVector 库**按序号**执行迁移（`001`–`004` 都要执行；
`004` 之后读侧按 `tenant_id` 过滤，缺它检索查不到任何行）：

```bash
# 以下命令在 ai-python/ 目录内执行
psql "$DOCQA_AI_PG_DSN" -f migrations/001_chunk_embedding_1024.sql
psql "$DOCQA_AI_PG_DSN" -f migrations/002_chunk_lexical_index.sql
psql "$DOCQA_AI_PG_DSN" -f migrations/003_chunk_embedding_1024_text_embedding_v4.sql
psql "$DOCQA_AI_PG_DSN" -f migrations/004_add_tenant_id.sql
```

`scripts/start-dev.ps1` 的 `[4.5/8]` 已把这四步做成幂等重放，Docker 本地链路不必手工执行。
`005_drop_tenant_default.sql` 不在自动重放之列：它要求 `tenant_id` 回填已完成，只能人工确认后执行。
结构在位但零向量不是故障态：检索返回 `chunkCount=0`，问答给出「现有资料不足」而不是报错，
`/health/ready` 也仍报 `pg: ok`（它只做 `SELECT 1`）。

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

### 界面配置的模型连接

管理员可以在 `/admin/model-connections` 里选厂商预设、填自己的 API Key、测试后设为生效，
**不必改上面的 env 再重启**。数据面侧的口径：

```bash
DOCQA_AI_MODEL_CONNECTIONS_ENABLED=true   # 打开后才按请求解析界面连接；默认 false
DOCQA_CRED_MASTER_KEY=...                 # base64 的 32 字节，与网关共用同一把
DOCQA_CRED_MASTER_KEY_PREVIOUS=...        # 轮换期的旧主密钥，逗号分隔
DOCQA_LLM_ALLOWED_HOSTS=...               # 主机后缀白名单，逗号分隔；留空放行任意公网主机
DOCQA_LLM_ALLOW_PRIVATE=false             # 本地 Ollama 等 http+回环端点需显式打开
```

生效连接由网关发布到 Redis `agent:model-connection:tenant:{tenantId}`，载荷里**只有 AES-256-GCM
密文**（AAD 绑定 `connectionId|tenantId`）与非密文字段；数据面用同一把主密钥在本地解封后构造
planner。明文 Key 既不过内部 ask HTTP（dev 形态下那条通道没有内部鉴权），也不以明文进 Redis。

三条语义刻意不同，不要混：

- **键不存在** → 回退 env 配置的 provider（离线演示与占位符降级继续成立）；
- **Redis 不可达** → 与 kill switch 同一 fail-closed 哲学，拒绝执行而不是猜一个 provider；
- **密文解不开 / 端点不合规** → 硬失败（`AGT-UPSTREAM-001`），**绝不静默退回离线**。

planner 按 `(connectionId, revision)` 缓存，改配置会换 revision 并新建 httpx client，
旧 client 在宽限期后才关闭，避免掐断在途的流式响应。出网判定复用
`tools/web/urls.py::validate_url`，与网关 `LlmEgressPolicy` 同一套规则、同一组环境变量。

开关打开时即使 env 三个 provider 全 offline，两个实验额度也必须为正：界面激活的真实外呼
同样受额度约束，账本因此一定会构造（`build_experiment_ledger` 的谓词包含该开关）。
审计与 `PlanContext.model` 都报**本次请求实际使用的模型**，不再读启动期 settings。

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
  逐条 `mask_text`，与查询侧同源同规则（`pii.masker.VERSION = pii-v2`，与 Java 一致；v1 仅三类，v2 另含银行卡/护照/社保号）。
  读侧同一条红线由 rerank 通道补齐：候选正文在**名次决定之前**逐篇 `mask_text`。两处都
  **只是改变离开本机的字节**，落库的 `content` 与 `content_hash` **仍是原文**。
- 审计事件随响应返回，由 Java `audit-service` 落库；仅在响应失败时走 `audit-event` 兜底。
- 日志与审计 payload 不包含问题、回答、提示词、文档正文等原文，PII 命中只记录类别与数量。

## 评测（eval）

两个离线确定性套件 + nightly 真模型门禁：

- `--suite retrieval`：Recall@k / MRR / nDCG@k（10 文档 17 查询，离线特征哈希 embedding）。
- `--suite agent`：治理不变量回归——引用 ⊆ 授权 scope 且 ≤3、小预算 → `BUDGET_EXHAUSTED`
  兜底答、PII 硬拦、轮次/循环兜底、kill switch、审计事件（`AGENT_COMPLETED` / `TOOL_EXECUTED`）。
  planner 是脚本替身，Redis 用 fakeredis，复用生产 `AgentAskService` / `AgentGraph` 构造路径。
- 基线提交在 `src/docqa_ai/eval/baselines/*.json`。有意变更语料/指标/治理行为后重录并随代码
  一起评审提交：

  ```bash
  .venv/Scripts/python -m docqa_ai.eval --suite all --update-baseline
  ```

  CI 每个 PR 跑 `python -m docqa_ai.eval --suite all --check-baseline`：指标回归、用例失败
  或用例集漂移即红。
- nightly（`.github/workflows/eval-nightly.yml`，每日 18:00 UTC + 手动 dispatch）：真实
  embedding/rerank 检索 eval + 自建 LLM judge（`eval/judge.py`）逐回答评 faithfulness，
  均值 ≥ 0.8 才通过，JSON 报告上传 artifact。需要仓库 secret `DASHSCOPE_API_KEY`；可选
  `DOCQA_EMBEDDING_BASE_URL` / `DOCQA_EMBEDDING_API_KEY` / `DOCQA_EMBEDDING_MODEL` 覆盖
  默认 DashScope 端点。
- 刻意不引 RAGAS：它会把 langchain-community/datasets/nest-asyncio 拖进 `--require-hashes`
  依赖锁，哈希面与版本 churn 太大；自建 judge 复用 providers 的 OpenAI 兼容 HTTP 形态，
  可审计且零新依赖。

## 验证

```bash
.venv/Scripts/python -m pytest
.venv/Scripts/python -m ruff check src tests
.venv/Scripts/python -m ruff format --check src tests
```

测试不依赖真实 Redis/RocketMQ/PGVector/MinIO：Redis 使用 `fakeredis`，向量与对象存储使用内存
实现，模型默认离线。
