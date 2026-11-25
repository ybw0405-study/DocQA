# 本地启动指南

本文说明如何在本机完整拉起整套服务（中间件 + Java 控制面 + Python 数据面 + 前端），供开发与联调使用。
所有脚本都按自身位置推导仓库根目录，**目录改名或移动不需要改任何命令**。

命名边界（哪些名字会随目录变、哪些绝对不能改）见 [naming.md](naming.md)。

---

## 一键启动（2026-11-19 起，推荐）

前置：安装并启动 **Docker Desktop**，确认 `docker info` 可正常返回（脚本不依赖任何固定安装路径）。
旧方案里的 WSL 中间件、Windows 原生 mysqld、开机自启、34 个全局环境变量**全部退役**，本文后半部分保留仅供回滚参考。

```powershell
# 在仓库根目录执行（路径任意；脚本按自身位置推导，目录改名或移动无需改命令）
# 仅首次：从模板生成项目内配置（gitignored，不写 Windows 全局作用域）
Copy-Item ops/env/local.env.example ops/env/local.env

.\scripts\start-dev.ps1          # compose up → 等健康 → 幂等授权 → 构建 → 起 5 个 Java 服务
.\scripts\stop-dev.ps1           # 只停应用进程
.\scripts\stop-dev.ps1 -Infra    # 连容器一起停（卷保留，数据不丢）
```

脚本选项：`-NoBuild` 跳过 `mvn package`（jar 已最新时）、`-InfraOnly` 只起中间件、`-SkipGrants` 跳过授权重放。

**这套方案的四个性质：**

1. **零全局环境变量**：所有配置在 `ops/env/local.env`（模板 `local.env.example`），由 `start-dev.ps1` 注入**进程作用域**，脚本退出即消失。Windows User / Machine 作用域里 `DOCQA_*` 应为 0 个（不与其他项目的环境变量约定混用）。
2. **零 IP 漂移**：中间件全在 Docker Desktop bridge，端点固定 `127.0.0.1:<专属端口>`，不再依赖 WSL `eth0` 地址，也就不需要开机刷环境变量。
3. **零开机自启**：计划任务 `DocQA Local Stack` 已 Disabled、WSL 三个 `docqa-*.service` 已 `disable`、原生 `MySQL84` 服务由管理员脚本改 Manual。用哪个项目就跑哪个项目的 `start-dev.ps1`。
4. **可与其他本地项目同时运行**：端口段完全错开，不再需要互斥切换。

### 端口表（唯一事实来源：`ops/env/local.env`）

| 服务 | 地址 | 服务 | 地址 |
|---|---|---|---|
| MySQL 8.4 | `127.0.0.1:13306` | RocketMQ NameServer | `127.0.0.1:19876` |
| PostgreSQL + pgvector | `127.0.0.1:15432` | RocketMQ Broker / VIP | `127.0.0.1:20911` / `20909` |
| Redis | `127.0.0.1:16380` | Python 数据面 API | `127.0.0.1:18000` |
| MinIO API / Console | `127.0.0.1:19100` / `19101` | Java 网关 / user / document / reminder | `18080` / `18081` / `18082` / `18083` |
| 前端 Vite | `127.0.0.1:5173` | LangFuse（可选，`-WithLangfuse`） | `127.0.0.1:13000` |

> RocketMQ 的 broker 会把 `brokerIP1:listenPort` 注册进 NameServer，客户端按此直连；
> `ops/infra/broker.conf` 显式写 `brokerIP1=127.0.0.1` / `listenPort=20911`，VIP 端口为 `listenPort-2=20909`，
> 三者都映射到宿主 `127.0.0.1`。**不要只改宿主映射端口而不改 listenPort**，客户端会连到旧端口。
> 同理 NameServer 端口由 `ops/infra/namesrv.properties` 的 `listenPort=19876` 指定。

### 数据库

首次数据搬迁时，既有 `docqa` 库（16 张表、含 Flyway V1–V10 与 `default` 租户行）经 `mysqldump`
导入 `docqa-mysql` 容器卷；备份统一放在 `ops/backup/`（gitignored，不入库）。
`start-dev.ps1` 每次启动会幂等重放 `ops/sql/least-privilege.sql.tmpl`（账号 host 为 `%`，因为
Windows 侧经端口映射连入时 MySQL 看到的是 bridge 网关地址）。迁移仍由 document-service 启动时跑 Flyway。

授权重放之后有一道对账：库里任何一张基表都必须出现在 `ss_app` 的授权面上，否则脚本直接拒启。
漏授权不会让任何进程起不来，它只在运行时以 `ERROR 1142 → SQLState 42000 → BadSqlGrammarException`
出现，日志里只剩一行异常类型名——`audit_relay_cursor` 就是这样让网关的 outbox relay 空转了近 750 次。
新表须同时补进 `ops/sql/least-privilege.sql.tmpl` 与 `ops/sql/apply-least-privilege.ps1` 的
`$businessTables`（后者把未纳管表视为硬失败，只有 `-DryRun` 放行）。

授权模板与新表存在先后关系：模板里指向**尚未创建**的表（例如 V13 的 `model_connection`）的 GRANT
会在步骤 4 被推迟，等服务起来、Flyway 建表之后由 `[6b]` 补齐并做上面那道覆盖率对账。
只跑 `-InfraOnly` 时服务不会启动，被推迟的授权也就不会下发，脚本会明确提示。

向量库结构由 `start-dev.ps1` 的 `[4.5/8]` 幂等重放 `ai-python/migrations` 的 001–004。
PG 侧没有 Flyway 账本，只能这样补：首次搬迁只导回了 MySQL，`docqa_vec` 是空库，
而 `/health/ready` 只做 `SELECT 1`，照报 `pg: ok`，故障要到问答检索撞上「表不存在」才现形。
005（`DROP` tenant 默认值）不在自动重放之列——它要求 `tenant_id` 回填已完成，需人工执行。

### Python 数据面与前端

`start-dev.ps1` 的第 7、8 步把这两项一起拉起，无需再手动启动：

- **Python 数据面 API**：用 `ai-python/.venv` 跑 `python -u -m docqa_ai.api.main`，监听
  `DOCQA_AI_PORT`（`18000`）。入口必须走 `main()` 而不是 `uvicorn ...:app`，前者会切
  `WindowsSelectorEventLoopPolicy`，否则 psycopg 异步连接在 Proactor 循环上被拒、检索恒定 503。
  脚本只给这个子进程声明 `DOCQA_AI_ENVIRONMENT=dev`，两端因此都不启用 `X-Internal-Token`；
  **生产部署必须另配 `DOCQA_AI_INTERNAL_TOKEN`**（网关与数据面两侧同值）。
  真模型 key 的接法与 AgentFlow 一致：`start-dev.ps1` 加载 `local.env` 后，若机器环境有
  `DASHSCOPE_API_KEY`，就在进程作用域派生 `DOCQA_AI_LLM_PROVIDER=openai`、`_API_KEY`、
  `_MODEL`（`offline-extractive` 这类占位名改为 `qwen-plus`）；密钥不写回 `local.env`、
  不入库、不回显，`-Containers` 模式同样生效。
  若既没有 `DASHSCOPE_API_KEY`、`DOCQA_AI_LLM_API_KEY` 又是 `CHANGE_ME*`，脚本会在启动期
  把数据面改派 `DOCQA_AI_LLM_PROVIDER=offline`（抽取式，不外呼），并在结尾标注实际生效模式。
  不这么做的话，占位符要一直到生成那一步才带 401 出去，而网关把上游异常统一塌成
  `AGT-UPSTREAM-001`，看不出是凭据问题。
- **解析 / 向量 Worker 由容器承载**：`rocketmq-client-python` 只有 Linux 的
  librocketmq C 绑定，Windows 进程起不来，因此 worker 跑在 Linux 容器里：
  `docker compose -f ops/docker-compose.infra.yml -f ops/docker-compose.app.yml up -d parse-worker vector-worker`
  （镜像复用 `ai-python` target，entrypoint 换 `docqa-parse-worker` / `docqa-vector-worker`）。
  只跑 `start-dev.ps1` 而不起这两个容器时，
  代价是**本机能跑起来，但新上传的文档不会写入向量**：`chunk` 正文只存在 PG，
  MySQL 侧只有 `chunk_index` / `content_hash` / `token_count` 清单，因此空库下检索恒定
  `chunkCount=0`，问答给出「现有资料不足」而不是报错。补齐索引需要 Linux worker 在线，
  再对每个文档调 `POST /api/v1/documents/{id}/reindex`（worker 从 MinIO 重新解析并写回 PG）。
- **前端**：直接 `node frontend-vue/node_modules/vite/bin/vite.js --host 127.0.0.1`，监听 `5173`。
  用 `127.0.0.1` 而不是 `localhost` 打开，避免解析到 IPv6 后白等一次重试。
  四组 `VITE_PROXY_*` 由脚本按 Java 服务同一张端口表注入，不依赖 `vite.config.ts` 里
  `808x` 的 WSL 旧兜底——漏一条前缀不会报错，而是静默降级成 SPA fallback 返回 HTML。

### 首次登录与账号

全新库（`docker volume` 重建后）默认**没有任何可登录账号**：种子账号与自助注册两处开关都是关的
（`DOCQA_DEMO_USER_ENABLED` 缺省 false、`DOCQA_SELF_REGISTRATION_ENABLED` 缺省 false）。
这是 SEC-5 的取值，不是遗漏。两条受支持的路：

- **种子演示账号**：在 `ops/env/local.env` 里打开 `DOCQA_DEMO_USER_ENABLED=true`，
  需要管理面再打开 `DOCQA_DEMO_ADMIN=true`；`DOCQA_DEMO_PASSWORD_HASH` 必须是 **cost ≥ 12**
  的真实 BCrypt 摘要（模板占位符 `CHANGE_ME_BCRYPT_HASH` 不算）。摘要由本机生成后填进 local.env，
  仓库里不出现可用口令或其散列。
- **自助注册**：`DOCQA_SELF_REGISTRATION_ENABLED=true`，走 `POST /api/v1/auth/register`，
  受租户 slug 白名单与每窗口注册数上限约束。

`start-dev.ps1` 在 `[1/8]` 之后校验第一种组合：开关打开但摘要形态不合法或 cost < 12 时直接拒启。
`DemoUserSeeder` 本来就会为此抛 `IllegalStateException`，但那要到 `[6/8]` 才发生，
现象是 user-service 起不来，看起来像构建坏了。
本机现在的库是从 WSL 搬迁带来的账号，不需要种子；`demo` 那行仍是迁移前的 cost=04 摘要，
`DOCQA_DEMO_USER_ENABLED` 因此不能打开——这也是把校验放在启动期的原因。

### 模型连接（在界面里换模型）

管理页 `/admin/model-connections`（导航「模型」，仅 ADMIN）：选厂商预设 → 填自己的 API Key →
测试连接 → 设为生效。凭据以 AES-256-GCM 密文落库并绑定到该连接与本租户，保存后不再回显，
页面只显示「已配置 / 未配置」。**切换生效连接不需要重启任何服务**：网关把密文发布到 Redis，
数据面在每次问答时解密并调用它。没有生效连接时，问答回退到 `DOCQA_AI_LLM_*` 配置的 provider。

- **主密钥**：`DOCQA_CRED_MASTER_KEY`（base64 的 32 字节）。`start-dev.ps1` 首次启动会自动生成一次
  并写回 gitignored 的 `ops/env/local.env`，**不回显、不入库、不写全局环境变量**；网关与数据面共用它。
  生产部署须自行注入高熵值。未配置时服务照常启动，只有「保存 API Key」与「测试连接」会被明确拒绝。
- **轮换**：旧主密钥放进 `DOCQA_CRED_MASTER_KEY_PREVIOUS`（逗号分隔），历史密文仍可解；
  每条连接重新录入 Key 之后才能移除旧密钥，否则那些连接会硬失败（不会静默降级）。
- **数据面开关**：`DOCQA_AI_MODEL_CONNECTIONS_ENABLED=true` 才会按请求解析界面连接；
  `start-dev.ps1` 的本地 dev 形态默认打开它（只注入该子进程），手动起数据面时才需自己设。
  打开时即使 env 三个 provider 全 offline，也要求 `DOCQA_AI_EXPERIMENT_BUDGET` 与
  `_TOKEN_BUDGET` 为正——界面激活的真实外呼必须受额度约束。
- **出网策略**：`DOCQA_LLM_ALLOWED_HOSTS`（主机后缀白名单，留空放行任意公网主机）与
  `DOCQA_LLM_ALLOW_PRIVATE`（默认 false）。本地 Ollama 这类 http + 回环端点必须显式打开后者，
  否则测试连接返回 `EGRESS_BLOCKED`。网关探测与数据面调用读同一组变量，判定一致。
- **既有限制**：未起 parse-worker / vector-worker 容器时没有文档向量，检索恒为 0 条，
  问答会降级为「现有资料不足」。换模型改变的是生成侧，不会凭空补出检索结果。

### LangFuse 追踪（可选，默认不启动）

```powershell
.\scripts\start-dev.ps1 -WithLangfuse
```

LangFuse v3 自托管追踪面由 compose 的 `langfuse` profile 承载（web / worker / ClickHouse /
队列专用 Redis 四个容器），**默认 `start-dev.ps1` 不启动它们**：ClickHouse + worker 约
**+2–3GB 内存**，只有显式 `-WithLangfuse` 才拉起。复用既有 `postgres-vector`（独立库
`langfuse`）与 MinIO（bucket `langfuse`），`[4.6/8]` 幂等建库建桶；队列 Redis 是
`noeviction` 的独立实例（共享 Redis 的 `allkeys-lru` 会逐出 BullMQ 任务键，不能复用）。

- **首启 bootstrap**：容器 env 里的 `LANGFUSE_INIT_*` 确定性建好组织 `DocQA`、项目
  `docqa-ai`、账号 `admin@docqa.local` 与一对项目 API 密钥。密钥对、`NEXTAUTH_SECRET`、
  `SALT`、`ENCRYPTION_KEY`（64 位 hex）及各口令由脚本首次 `-WithLangfuse` 时自动生成并写回
  gitignored 的 `ops/env/local.env`（不回显、不入库）；数据面导出用同一对 pk/sk。
- **数据面导出**：`-WithLangfuse` 时脚本给 Python 子进程注入
  `DOCQA_AI_LANGFUSE_TRACING_ENABLED=true`，在既有 OTLP 管道之后追加第二路
  `BatchSpanProcessor`（同 spans 同 sampler，既有管道不动）。span 属性只有计数 / model /
  outcome，问题与回答原文不进任何导出。开关默认 false，行为与改动前逐字节一致。
- **追踪在哪看**：问一题后打开管理页「审计」，traceId 单元格变成外链
  （`{base}/trace/{traceId}`，基地址由网关 `GET /api/v1/admin/observability` 运行时下发，
  空 = 未部署即纯文本）；点进去即是 LangFuse 里同 traceId 的完整 span 树。
  也可直接开 `http://127.0.0.1:13000`，用 `admin@docqa.local` + local.env 里的
  `DOCQA_LANGFUSE_INIT_USER_PASSWORD` 登录。

### 长期记忆（显式指令 + 管理页）

数据面开关 `DOCQA_AI_MEMORY_ENABLED=true` 才启用（默认关闭，行为与之前逐字节一致）；
存储复用 checkpoint 同一个 Postgres 库（`DOCQA_AI_MEMORY_DSN` 留空回退 `DOCQA_AI_PG_DSN`），
启用而无 DSN 时启动直接报错。

- **写入只认显式指令**：问题以「记住：/记住:/记住，/记住 」或 `remember:` 开头才写入，
  判定发生在 PII 校验与 kill switch 之后、planner 解析之前；命中即回固定确认，
  **零 LLM 调用、零检索**。事实会再按 v2 掩码复检，残留敏感信息一律拒写（422）。
- **上限与 TTL**：每用户至多 50 条（溢出淘汰最旧）、保留 90 天；按 `(tenantId, userId)`
  命名空间隔离，跨租户互不可见。
- **注入是不可信参考**：非指令问题最多带 5 条记忆、总量受 `DOCQA_AI_MEMORY_MAX_CHARS`
  （默认 800 字符）约束，经 `<memory>` 围栏 + 转义 + 注入前再掩码进入 prompt；
  记忆不改变可用工具与引用规则。
- **管理页** `/admin/memories`（导航「记忆」，仅 ADMIN）：按用户 ID 查询清单、两步确认删除；
  没有写入口。网关经内部端点代理数据面，未启用记忆时页面如实报 503 而不是空清单。

### 回滚

旧 WSL 链（`start-local.ps1` / `run-local.ps1` / `run-ai-local.ps1` / `prepare-local-stack.sh` /
`local-stack.compose.yaml` / `install-wsl-autostart.sh` / `wsl-boot.sh` / `setup-parse-worker.sh` /
`rocketmq-broker.conf`）已于 2026-11-24 **整体删除**：端口方案、凭据注入方式与 worker 运行位
均与现行链冲突，保留只会误导克隆者。确需回滚时从 git 历史取回删除前的版本。
数据库备份见 `ops/backup/`（gitignored）。

---

## 一键验证

```bash
./scripts/verify-all.sh --offline                 # Git Bash / Linux / CI
powershell -File scripts/verify-all.ps1 -Offline  # Windows PowerShell 5.1
```

脚本按序执行 Java `mvn clean verify`、Python `pytest + ruff`、前端 `typecheck + lint + test + build`，
任一失败即非零退出。脚本会先清掉进程里的 `DOCQA_*` / `SERVER__PORT` 等运行期变量，
保证离线门禁与 CI 环境一致（否则本机常配的审计守卫、实验预算会污染 Spring Boot 宽松绑定，
出现「CI 全绿、本机假红」）。

> **Windows 注意**：`mvn clean` 无法删除正被 JVM 占用（已启动服务）的 `target/*.jar`。
> 跑门禁前先执行 `.\scripts\stop-dev.ps1`，跑完再用 `.\scripts\start-dev.ps1` 拉起即可。

## 故障排查

| 现象 | 原因 | 处理 |
|---|---|---|
| 上传卡在 `PARSING` | parse-worker 容器未起，或 topic 未预建导致消费者静默空轮询 | `docker compose -f ops/docker-compose.infra.yml -f ops/docker-compose.app.yml up -d parse-worker vector-worker`；topic 由 `start-dev.ps1` `[3.5/8]` 或 compose 的 `mq-init` 一次性服务预建 |
| 服务启动报 `Migration checksum mismatch for migration version N` | 已应用的迁移文件被改过（哪怕只改注释） | 本地库执行 Flyway repair：把 `flyway_schema_history` 该版本的 `checksum` 更新为当前文件的 CRC32（对每行内容做 CRC32，首行去 BOM）。生产环境应**禁止**修改已应用的迁移 |
| `mvn clean verify` 删不掉 `target/*.jar` | jar 正被运行中的 JVM 占用 | 先 `.\scripts\stop-dev.ps1` |
| CI 全绿、本机门禁假红 | 本机 `DOCQA_*` 污染了 Spring Boot 宽松绑定 / pydantic-settings | 门禁脚本已自动清理这些变量，无需手工处理 |
| `git push` / `fetch` 报 `Recv failure: Connection was reset` 或 `Could not connect to server`，而 `gh` 命令正常 | `github.com` 解析到的官方 IP 在部分网络不可达；`api.github.com` 不受影响 | 临时绑定一个可达 IP（只写本仓库 `.git/config`，不会提交）：`git config --local http.curloptResolve github.com:443:<可达IP>`。**IP 会轮换，默认解析恢复可达后要撤销**：`git config --local --unset http.curloptResolve`（绑死已失效的 IP 会反过来连不上）。或把 `~/.ssh/id_ed25519.pub` 加到 GitHub 后把远程换成 SSH |

## 数据库最小权限

生产/半生产环境建议收敛数据库权限为最小集（审计只追加、迁移与运行时账号分离），
详见 `ops/sql/README.md`。
