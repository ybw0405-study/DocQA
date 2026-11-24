# 变更日志

本文件格式遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

## [2.2.0] - 2026-11-24

### 新增：人在回路审批（LangGraph interrupt）

- 高风险工具（`DOCQA_AI_HIGH_RISK_TOOLS`，精确名或前缀通配）执行前 `interrupt` 挂起，checkpoint 保留恢复态；
  ask 请求新增可选 `resumeApproved` 批准/拒绝续跑（拒绝则工具不执行、观测留痕后继续规划）。
- 响应新增 `pendingApproval` 与 `PENDING_APPROVAL` 预算态；网关透传并校验配对关系；
  `agent_execution.budget_status` 值域经 V14 迁移扩展；挂起执行记 RUNNING、不写结果缓存。
- 前端问答页审批卡片（批准并继续 / 拒绝）；契约 yaml 与类型镜像同步；4 条 interrupt 单元回归。
- 开启审批强制 checkpoint_enabled=true（启动期 fail-fast）；默认关闭，行为与旧版逐字节一致。

### 修复：深度体检第一轮（2026-11-24）

- **启动链收敛**：删除旧 WSL 链全部 9 个文件（compose / 启动 / 自启 / worker 脚本 / broker 模板）；解析与向量 worker 改由容器承载；`start-dev.ps1 -Containers` 与新增 `ops/docker-compose.app.yml`（含 `mq-init` topic 预建与 `grants-finalize` 建表后补授权）一条命令起全栈；getting-started 改写为单启动路径。
- **Docker 修复**：前端镜像 COPY nginx 样例配置到正确落点（SPA fallback + `/api` 反代 + 安全响应头），构建期固定绝对 base 修复深链接 assets 解析；audit-service 改用 `java-service-nonweb` target（无 HTTP 探针）；compose 凭据改进程环境透传，规避 env_file 二次插值吞掉 bcrypt 哈希的 `$` 段。
- **真实模型评测**：nightly secrets 配置后全绿；标注集 17→21 查询（新增同法条细粒度排序压力对），本地真实复现 recall@1 0.976 / recall@3,5 1.0 / MRR 1.0 / nDCG@5 0.996，数字入 README 与 retrieval-eval.md；离线基线同步重录。
- **Agent 工具协议**：新增 `DOCQA_AI_TOOL_PROTOCOL=json|native`（默认 json 行为不变）；native 走 OpenAI 兼容 function tools，流式按 index 累积 tool_calls 片段，终答仍走 JSON 保留引用治理；新增 8 条回归用例。
- **文档纠偏**：README 环境变量名纠正为 `DOCQA_AI_*` 并说明服务 / 评测 / nightly 三套变量面；仓库 description 与 topics 改为 RAG Agent 主线；README 重构一屏化，旧全文归档 `docs/product-guide.md`；悬空阶段代号改写，迁移注释代号新增索引文档。
- **CI**：ci 工作流新增 `compose-config` job 校验叠加编排。

### Fixed（第二轮）

- **P0：真实 PG 上的租户检索恒失败**。`PgVectorRepository.search()` 把 `tenant_value` 放在绑定参数
  首位，而 SQL 第一个 `%s` 是相似度向量；带租户的查询在 PostgreSQL 上报
  `InvalidTextRepresentation`。736 个用内存替身的 Green 测试从未触发。已按占位符顺序重排并新增
  不依赖 PG 的 `test_pg_parameter_alignment.py` 护栏；本机 `pgvector/pgvector:pg16` 容器真跑
  `test_pg_contract.py` → 3 passed。
- **DQ-1 持久化对账**：新增 `V11__vector_cleanup_outbox.sql` 与 `VectorCleanupTaskRepository`，
  旧版本向量删除在发布失败 / FAILED / 重启 / 消息丢失四种情况下不再永久残留；只有成功确认才出队。
- **CI 真库门禁**：`ci.yml` 接 pgvector service + junit 断言（3 条用例必须是 passed 而非 skipped），
  不再让 P0 回归穿过全绿 CI。
- **G6**：workflow action 固定到联网核实的 40 位 SHA；修掉不存在的
  `dependency-review-action@v4` 引用。远端 secret scanning / push protection / Dependabot 安全更新
  已启用；PR #26 打 `do-not-merge` 标注。
- Windows 开发机在 `tests/conftest.py` 切 SelectorEventLoop，使 psycopg 异步真库用例可复现。

### Added

- **本地双租户 HTTP 演练**：新增 `scripts/e2e-dual-tenant-drill.sh`（22 步硬断言、时间戳后缀可重复运行）
  与 `docs/00-infra/dual-tenant-e2e-drill.md` 报告。在真实启动的全套服务上验证 bootstrap 供给防护与幂等、
  JWT `tenant_id` 归属、跨租户文档 404、租户级熔断隔离、用户列表隔离、跨语言契约 `extra="forbid"`；
  附 5 张真实登录态界面截图于 `docs/00-infra/demo/`。演练**不进 CI、不改产品版本**；
  RAG 解析链路受 Windows 无 `librocketmq.so` 限制未覆盖，边界在报告中如实标注。

### Changed

- 仓库转为 **public**，`main` 启用 Branch Protection（必须走 PR、required status checks 指向三端 CI、
  `enforce_admins`、禁 force push / 删除、线性历史）。`SECURITY.md` 与 `git-workflow.md` 中
  「私有仓库免费套餐 403 无法启用」的过时表述已同步更正。
- `docs/00-infra/getting-started.md` 去除写死的本机用户名、绝对路径、历史备份文件名与内网 VM 地址，
  改为路径无关表述；公开仓库不再暴露本机环境细节，指南可按任意克隆位置执行。
- 清理对外仓库中的本机 / 内部研发代称：脚本、配置模板、compose 注释与设计文档排障记录里
  对另一本地项目的具名引用统一泛化为「其他本地项目」，移除已失联历史 VM 的具体 IP、
  内部事故日期与压测编号；端口错开、进程归属判定、专属 Redis 等技术结论原样保留。
- **历史背景如实说明**：本仓库首个提交是一次性导入的完整成品（840 文件 / 约 11.5 万行），
  其后若干提交用于对外发布前的改名与内部研发文档清理。因此 `git log` 不能作为团队渐进迭代史采信；
  分支保护与 PR 流程自公开起生效，此后所有变更以 PR 为最小评审单元。

- **工程化 / 治理文件校准（本轮，仅治理文件，未 bump 版本，仍 `2.1.0`）**：
  - `.github/dependabot.yml` 补 `docker` 生态（仓库暂无 Dockerfile，以 `/` 占位并注明）；
  - 新增 `.github/workflows/security.yml`（G9）：`dependency-review-action`、
    `npm audit --omit=dev --audit-level=high`（`frontend-vue/`）、CodeQL
    `java-kotlin` / `javascript-typescript` / `python` matrix；`ci.yml` / `release.yml`
    最小 `permissions` 与 `timeout-minutes`（G7）；
  - `release.yml`（G14）新增 `concurrency`、严格 tag 格式 / 存在性校验、
    `git merge-base --is-ancestor` 判定 tag 必须是 `main` 祖先、`workflow_dispatch` 输入校验；
  - `CONTRIBUTING.md` 新增第 10 节「治理现状与评审边界」，如实写明分支保护已启用但
    `required_approving_review_count=0` / `require_code_owner_reviews=false`、CODEOWNERS 不强制、
    单人仓库无法第二人复核；`SECURITY.md` 更正 Code Scanning 现状；`SUPPORT.md` 补单人维护说明；
  - `ops/env/local.env.example`（G11）补 7 个缺失键（`DOCQA_REDIS_DATABASE` /
    `DOCQA_ROCKETMQ_ENABLED` / `DOCQA_AUDIT_ENABLED` / `DOCQA_DEMO_USERNAME` /
    `DOCQA_DEMO_USER_ID` / `DOCQA_SELF_REGISTRATION_ENABLED` / `DOCQA_MINIO_ENABLED`），
    并新增 `DOCQA_ENV`（生产判据，默认 prod）、`DOCQA_AI_ENVIRONMENT`、
    `DOCQA_AI_INTERNAL_TOKEN`（明显占位符）；
  - 新增 `scripts/check_env_contract.py`（G11）：断言 `application*.yml` 的 `${VAR}` ⊆
    env.example 键集合，显式区分 `${PID:- }` 等框架占位符，缺键即非零退出。
- **版本号未 bump**：本轮不改动 `2.1.0`（`backend-java/pom.xml` / `frontend-vue/package.json`），
  是否并入 `2.1.1` / `2.2.0` 待维护者决策，避免与现有 tag 不一致。

### Fixed

- 审计页事件类型与写入端 / 来源端文字重叠：事件类型独立成行，审计表列宽重分配并对长标识启用换行。
- 问答页右栏大片留白：两栏容器用 `.ask__stream` 约束最大宽度，并在窄屏断点收敛布局。

## [2.1.0] - 2026-11-19

> 2.0.0 之后的工程闭环批次（租户控制面与前端对齐）。
> **无 BREAKING 变更**，从 2.0.0 直接升级即可，无需迁移步骤。
> 落地记录见 [saas-kernel-upgrade.md](docs/02-design/saas-kernel-upgrade.md) §5.22。

### Added

- **前端消费 D3 租户级熔断**：新增 `/admin/ai/controls/tenant` 的 API、独立 store 与管理页
  「本租户 Kill Switch」（导航新增「本租户」入口）。契约把权限语义钉死为「`tenantId` 只取自
  已验签 JWT、需要 ADMIN 角色、只影响当前登录租户」，前端不提供选择租户的入口。
  界面如实呈现**有效状态**（平台级全局键 OR 本租户键）：全局开关仍开启时关闭本租户键，
  回显依旧是 `active=true`，页面明确提示「平台级一键全停仍在生效、本页无法关闭它」，
  不谎称服务已恢复；元数据只记录本租户键的写入，缺失不改变状态判读。
- **bootstrap 供给的真库验收**：`MigrationOnRealMysqlTest` 新增用例，在真实 MySQL 8.4 上验证
  `INSERT IGNORE INTO tenant` 同 id 重放幂等、`uk_tenant_slug` 拦住不同 id 的同 slug、
  以及写入形态为 `ACTIVE / owner_user_id=NULL`。与既有 HTTP 形态、服务编排、SQL 文本断言
  合成完整验收链；按 D4 仍不引入全链 HTTP e2e。
- **release 自动化**：新增 `.github/workflows/release.yml`，推送 `vX.Y.Z` tag 时从 CHANGELOG
  提取对应版本段落自动创建/更新 GitHub Release；缺少该段落则拒绝发布空 Release。
  另支持 `workflow_dispatch` 手动补发。

### Changed

- 修正 `ci.yml` 头部注释与实现矛盾：CI 并非「全部离线」——`MigrationOnRealMysqlTest` 在
  runner 有 Docker 时通过 Testcontainers 自动执行 V1→V10 迁移与仓储谓词验收；
  注释改为如实描述该边界，并写明 `DOCQA_DB_IT=off` 可在无 Docker 环境跳过。
- 前端测试桩区分平台级键与本租户键、如实模拟 OR 叠加与「元数据只来自租户键」，
  新增 24 个前端用例（API / store / view / a11y 播报区对账）。
- 管理面描述口径由「六个操作 / 契约 2.5.0」校准为「八个操作 / 契约 2.6.0」。

### Decisions

- D2 域名路由、D5 Prometheus tenant 标签、D6 OPA ABAC 维持「维持或暂缓」，本批不改动。
- D4 全链 HTTP e2e 仍不引入；P2 计费 / P3 身份 / P4 可证删除与 §6 商业层 4 条仍属路线图与业务拍板范围。

## [2.0.0] - 2026-11-19

> 本轮为 SaaS 多租户内核升级（分支 `feat/saas-tenant-foundation`，2026-11-18～19）。
> 完整落地记录见 [saas-kernel-upgrade.md](docs/02-design/saas-kernel-upgrade.md)，
> 产品决策见 [saas-tenant-decisions.md](docs/02-design/saas-tenant-decisions.md)。
> **含 2 处 BREAKING 变更，从 1.0.1 升级请先阅读下方「升级指引」。**

### Added

- **租户地基**：新增 `tenant` 表与 Flyway V10 迁移，全部业务表加 `tenant_id`；JWT 增加
  `tenant_id` claim 且 4 处验签强制校验；17 个 Jdbc 仓库全部按租户谓词强过滤。
- **P1-B 租户隔离**：登录 / 注册按请求体 `tenantSlug` 解析租户（`TenantResolver`），
  刷新令牌家族携带 tenantId；管理面、MQ、定时任务、文档解析链路全部收口到真实租户。
- **Java ↔ Python 协议租户上下文**：问答请求双向必填 `tenantId`，
  `ProtocolTenantContractTest` 锁定字段集合；parse_worker MQ 消息契约测试锁定 6 个字段。
- **MinIO 对象键租户化**：新键为 `documents/{tenantId}/{userId}/{documentId}/source`，
  存量旧键按落库 `object_key` 继续兼容读取，无需搬对象。
- **可观测**：Java 侧 `TenantContext` 镜像 `tenantId` 到 MDC，三个服务日志渲染
  `traceId` / `tenantId` 标签。
- **D1 租户供给**：配置保护的 bootstrap 端点 `POST /api/v1/admin/tenants`
  （`X-Bootstrap-Token`；令牌默认空即禁用、常量时间比较、`INSERT IGNORE` 幂等）；
  out-of-band 运维入口，不经网关、不进对外契约。
- **D3 租户级 AI Kill Switch**：保留平台级全局熔断，新增
  `agent:kill-switch:tenant:{tenantId}` 及 `:meta`，全局 OR 租户；Java 管理面新增
  `GET/PUT /api/v1/admin/ai/controls/tenant`（tenantId 取自已验签 JWT），Python
  `RedisKillSwitch.is_active(tenant_id)` 在唯一执行点消费，双端同批发布。
- **验收**：`MigrationOnRealMysqlTest` 在 Testcontainers MySQL 8.4 上验证 V1→V10 迁移
  与真实第二租户的仓储级隔离；另补 MQ 消息跨语言字段契约测试。

### Changed

- **BREAKING**：`app_user.username` 唯一键由全局改为 `(tenant_id, username)`，存量数据
  回填默认租户；V1–V9 已应用且不可修改，新增迁移只能 V10+。
- **BREAKING**：Java ↔ Python 问答协议与 MQ 治理键需双端同批发布
  （Python `StrictModel` 为 `extra="forbid"`）。
- `openapi-java.yaml` 升至 **2.6.0**：登记租户级 kill switch 路径与
  `KillSwitchStateEnvelope`；前端契约漂移测试同步裁决（schema 98→99）。
- 移除 `JwtService` 可省略 tenantId 的签发重载，签发必须显式传租户，并加反射守卫测试。

### Decisions

- 明确**暂不引入**域名路由（D2）、全链 HTTP e2e（D4）、Prometheus tenant 标签（D5）、
  OPA ABAC（D6）；触发条件与影响见决策清单。这是拍板结论，不是遗漏。
- P2 计量计费 / P3 身份 / P4 可证删除仍属产品路线图，不在本轮范围。

### 升级指引（1.0.1 → 2.0.0）

本次含 2 处 BREAKING，按以下顺序发布，任一步跳过都可能导致数据错属或请求被拒：

1. **备份**：先全量备份业务库。`V10` 是不可改的已应用迁移，新建 `tenant` 表、14 张业务表
   加 `tenant_id NOT NULL DEFAULT '<默认租户>'`（MySQL 8 instant DDL，存量原地回填）、
   4 张表唯一键与 7 个外键改为 `(tenant_id, ...)` 复合形式；升级前留一份可回退快照。
2. **双端同批发布**：Java 三服务与 Python 数据面必须在同一发布窗口内上线。问答协议新增
   双向必填 `tenantId`，Python 侧 `StrictModel` 为 `extra="forbid"`，任一端口先单独上线
   都会因字段缺失 / 多余而拒绝请求；不存在兼容中间态。
3. **迁移脚本**：Flyway 由 `document-service` 单归主执行（`validate-on-migrate: true`），
   随应用启动自动应用 `V10`，**不要**手工执行 DDL、不要修改 `V1`–`V9`。
4. **会话失效（需通知用户）**：JWT 新增 `tenant_id` claim，4 处验签强制解析且缺失/非 UUID
   即拒。升级后所有存量 access / refresh token 立即失效，用户须重新登录。登录 / 注册请求体
   的 `tenantSlug` 为**可选**，缺省 / `default` 回落默认租户，存量客户端无需改造。
5. **对象键**：MinIO 新对象写入 `documents/{tenantId}/{userId}/{documentId}/source`，
   存量旧对象按逐行落库的 `object_key` 继续读取，**无需搬对象、无需回填脚本**。
6. **可选配置**：`DOCQA_TENANT_BOOTSTRAP_TOKEN` 默认空（供给端点禁用）；需要供给第二租户时
   由运维注入高熵值，用 `X-Bootstrap-Token` 调 `POST /api/v1/admin/tenants`（`INSERT IGNORE`
   幂等）。这是 out-of-band 运维入口，不经网关、不进对外契约。
7. **回滚**：以 revert MR 为主，不直接 `reset main`。`V10` 的数据库变更不随应用回滚自动
   撤销——已写入的 `tenant_id` 列对旧版代码是不可见的新列（有默认值，前向兼容），但唯一键
   语义变化无法自动还原；如需彻底回退须按备份恢复，不能依赖应用降级。

发布后按 `git-workflow.md` §5 观察错误率、问答 P99、MQ 消费延迟与审计写入延迟再关闭变更单。

## [1.0.1] - 2026-11-08

### Security

- Python 依赖升级到无已知漏洞版本：LangGraph 1.2、LangChain Core 1.7、LangSmith 0.14、
  langgraph-checkpoint 4.3、langgraph-sdk 0.4，修复 checkpoint 反序列化、缓存 pickle
  fallback、prompt pull 与 tracing 文件读取等 28 项已知漏洞
- MinIO Java 客户端 8.5.17 → 8.6.0，修复 CVE-2025-59952（XML 标签值替换）
- 前端通过 npm overrides 固定 `minimatch` 11.0.5 / `glob` 14.0.4，修复
  CVE-2026-118764 / CVE-2026-118773（仅影响开发工具链）

### Fixed

- 修复 README Mermaid 架构图在 GitHub 上渲染失败的问题
- 关闭 Dependabot 初次启用时产生的一次性批量 PR，并清理 10 个残留分支

### Changed

- Dependabot 改为按月分组升级、忽略主版本，每个生态最多 2 个 PR
- 版本号统一到 1.0.1

## [1.0.0] - 2026-11-08

首个对外发布版本。

### Added

- **文档智能问答**：PDF / Word / 文本上传，异步解析、分块与向量化，RAG 问答（同步 + SSE 流式），回答附带引用溯源
- **认证与 RBAC**：JWT 无状态认证、刷新令牌轮换、USER / ADMIN 角色
- **文档管理**：分片上传、MD5 秒传、重命名、标签、软删除、失败重试、重建索引
- **课表与提醒**：ICS 日历导入、作业截止提醒、自定义提醒、站内通知
- **治理与可观测**：AI 调用开关、按用户配额、审计事件、用量统计、Prometheus 指标
- **工程化**：Maven 多模块 + ArchUnit + Checkstyle，Ruff + Pytest，ESLint + vue-tsc + Vitest，三端 CI 门禁
- **本地运行**：WSL2 中间件编排、Windows 一键启动、systemd 开机自启
- **开源社区文件**：CONTRIBUTING、CODE_OF_CONDUCT、SECURITY、SUPPORT、CHANGELOG、
  Issue/PR 模板、CODEOWNERS、Dependabot、`.editorconfig`
- **README 重设计**：徽章、目录、Mermaid 架构图、功能矩阵与文档索引

### Changed

- 项目与代码标识统一为 **DocQA**：Java 包 `com.docqa`、Maven 坐标 `com.docqa:docqa`、
  Python 包 `docqa_ai`、环境变量 `DOCQA_*`、MySQL 库 `docqa`、PostgreSQL 库 `docqa_vec`、
  MinIO 桶 `docqa-documents`
- CI Action 升级到 Node 24 版本（`checkout@v5`、`setup-java@v5`、`setup-python@v6`、
  `setup-node@v5`、`upload-artifact@v5`）
- 各端版本号统一为 `1.0.0`

### Fixed

- 修正改名后遗留的文档路径、许可证元数据、Docker/数据库/桶名不一致
- 修正 ArchUnit 单独执行时 `mq-support` 无匹配测试导致的失败
- 修正 `start-local.ps1` 只回写 User 作用域导致 Machine 变量携带旧 WSL IP 的问题

### Security

- 数据库最小权限：`audit_event` 对运行时账号只允许 `SELECT` / `INSERT`
- 审计只追加守卫支持 `off` / `warn` / `enforce` 三种模式

[Unreleased]: https://github.com/ybw0405-study/DocQA/compare/v2.1.0...HEAD
[2.1.0]: https://github.com/ybw0405-study/DocQA/compare/v2.0.0...v2.1.0
[2.0.0]: https://github.com/ybw0405-study/DocQA/compare/v1.0.1...v2.0.0
[1.0.1]: https://github.com/ybw0405-study/DocQA/releases/tag/v1.0.1
[1.0.0]: https://github.com/ybw0405-study/DocQA/releases/tag/v1.0.0
