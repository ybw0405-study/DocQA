# 变更日志

本文件格式遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

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

[Unreleased]: https://github.com/ybw0405-study/DocQA/compare/v2.0.0...HEAD
[2.0.0]: https://github.com/ybw0405-study/DocQA/compare/v1.0.1...v2.0.0
[1.0.1]: https://github.com/ybw0405-study/DocQA/releases/tag/v1.0.1
[1.0.0]: https://github.com/ybw0405-study/DocQA/releases/tag/v1.0.0
