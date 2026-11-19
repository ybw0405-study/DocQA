# DocQA 2.0 — SaaS 内核升级方案

> 版本 v0.1 · 日期 2026-11-18 · 状态：已落地并随 2.0.0 发布（2026-11-19）· 基线：v1.0.1 (commit 2b10513)
>
> 本方案基于对现有代码的实测勘察，替代早期基于错误假设（PostgreSQL RLS）的通用草案。
> 所有"现状"陈述均已核对源码，文件为工作区内真实路径。

---

## 0. 结论摘要

**现状一句话**：`userId` 是系统唯一的归属与隔离边界，全项目不存在任何 tenant / organization / workspace 维度。

| 维度 | 现状实测 |
|---|---|
| MySQL 业务库 | 14 张表，共享 `docqa` 库，**无 `tenant_id` 列**；`app_user.username` 全局唯一 |
| JWT | claims 仅 `sub/iss/aud/iat/exp/jti/roles`，**无 tenant claim**；4 处独立验签 |
| Java 服务间 | Gateway MVC 反向代理，Bearer 原样透传，下游各自验签；无 OpenFeign |
| pgvector | 2 张向量表，**明确设计红线"没有任何按用户过滤的列"**（D-87） |
| MinIO | key = `documents/{userId}/{documentId}/source`，有 user 无 tenant |
| Redis | `agent:*` / `auth:*` 全部以 userId 或 traceId 开头 |
| Java↔Python | `PythonAskRequest` 15 字段 / `AgentAskRequest` strict + `extra="forbid"`，无 tenant |
| 计量 | `agent_execution` 是不可变事实源（已有 token_used），但只有 userId |
| 配额 | MySQL `ai_quota` 存上限 + Redis `agent:quota:usage:{userId}:{日期}` 存当日计数 |

**与早期草案的关键差异（必须纠正）**：

1. 业务库是 **MySQL 8，没有原生 RLS**。三级隔离要重新定义为「共享库 + 应用层强制过滤 / schema-per-tenant（MySQL database）/ 独立实例」，不能照搬 PostgreSQL `current_setting('app.tenant_id')` 那套。
2. 已有 `agent_execution` 是不可变计量事实源，**不需要从零建 Outbox + Kafka**，应在此基础上扩展 meter 维度。
3. 已有 JdbcTemplate 仓库层与 Flyway 单一 owner（document-service），**新增迁移必须放 V10+**，且禁止修改 V1–V9（`validate-on-migrate: true`）。

---

## 1. 六项能力的落地映射

### 1.1 租户隔离（三级，MySQL 版本）

**数据模型**（MySQL，`V10` 起）：

```sql
CREATE TABLE tenant (
  id           CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  slug         VARCHAR(64)  NOT NULL,
  name         VARCHAR(120) NOT NULL,
  tier         VARCHAR(24)  NOT NULL,        -- FREE/STANDARD/PRO/ENTERPRISE
  isolation    VARCHAR(16)  NOT NULL,        -- SHARED/SCHEMA/DEDICATED
  status       VARCHAR(16)  NOT NULL,        -- TRIAL/ACTIVE/PAST_DUE/SUSPENDED/DELETED
  owner_user_id CHAR(36)    NOT NULL,
  created_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id), UNIQUE KEY uk_tenant_slug (slug)
);
```

所有业务表新增 `tenant_id CHAR(36) NOT NULL`，并把唯一键改为含租户维度：

| 表 | 现有唯一键 | 改造后 | 必须改的仓库方法 |
|---|---|---|---|
| `app_user` | `uk_app_user_username(username)` | `(tenant_id, username)` | `JdbcUserRepository.existsByUsername()` / `findByUsername()` / `insert()` |
| `document` | `(user_id, md5)` | `(tenant_id, user_id, md5)` | document-service 各 `Jdbc*Repository` |
| `document_tag` | `(user_id, normalized_name)` | `(tenant_id, user_id, normalized_name)` | 同上 |
| `reminder` | `(user_id, idempotency_key)` | `(tenant_id, user_id, idempotency_key)` | reminder-service |
| `ai_quota` | PK `(user_id)` | `(tenant_id, user_id)` | `JdbcAiQuotaRepository` upsert |

**三级隔离的实现方式**：

- **L1 SHARED（共享库）**：MySQL 无 RLS，改为「视图 + 强制过滤」。`Jdbc*Repository` 的每条 SQL 必须带 `tenant_id = ?`，且不提供任何不带该谓词的查询入口；用 ArchUnit 测试禁止裸 `SELECT * FROM <table>`。管理员跨租户查询走独立 repository，返回脱敏视图。
- **L2 SCHEMA（schema-per-tenant）**：MySQL 的 `database` 等价于 schema。连接 URL 按租户路由（`jdbc:mysql://.../docqa_<slug>`），迁移工具遍历所有 database。Flyway 单一 owner 的现有约定要扩展为「控制面库 + 每租户库」两组。
- **L3 DEDICATED（独立实例）**：动态数据源 + 凭据进 KMS，独立备份与升级窗口。

**跨级迁移工作流**：双写 → 回填 → 逐表 count/hash 校验 → 灰度读切流 → 全量切流 → 观察期 → 回收旧层。

**旁路存储**：

- pgvector 2 张表加 `tenant_id` 并建复合索引。注意 HNSW 后置过滤会破坏 top-k，需用分区表或按 tenant 分 collection，不能简单加 WHERE。
- MinIO key 改为 `documents/{tenantId}/{userId}/{documentId}/source`，唯一构造点 `DocumentService.java:566`。
- Redis 键全部加租户段：`agent:quota:usage:{tenantId}:{userId}:{date}` 等，需同步改 `RedisQuotaUsageStore`、`RedisBudgetStore`、Python `governance/*` 的键构造（**Java 与 Python 必须同批发布**）。

### 1.2 OPA 做 ABAC

- **判决点**：
  - 粗粒度：现有 `JwtAuthenticationFilter.doFilterInternal()`，已有 `ADMIN_PATH_PREFIX` 检查可替换为 OPA。
  - 细粒度：`AskService.prepare()`（同步/流式共用），在 `DocumentScopeService.resolve()` 之前插入 OPA 判决，OPA 结果作为文档 scope 的上限。
  - 最终兜底：`JdbcDocumentScopeRepository` 的 SQL 加 `tenant_id = ?`，保持"OPA 挂了也串不了租户"。
- **Input 契约**：`user { id, tenantId, roles, groups }`，`resource { type, tenantId, ownerId, classification }`，`action`，`context`。tenantId 必须来自验签 JWT，不接受 Header/Body。
- **策略治理**：Git 存 Rego → CI `opa test` → bundle；短 TTL 缓存，key 含 policy version；fail-closed + 最小 fallback。
- **角色映射**：现有 `app_user_role` 只有 `USER`/`ADMIN`，SCIM 组映射需要扩展角色枚举或新增 `tenant_role` 表。

### 1.3 计量 → 聚合 → 账单

**复用优先，避免重造**：

- `agent_execution` 已是不可变事实源，含 `token_used / tool_calls_made / rounds_used / started_at`，直接扩展 `tenant_id` 和 `meter` 维度即可。
- 新增 `usage_event` 表记录非问答类计量（文档页数、存储字节·时、API 调用），字段：`event_id, tenant_id, user_id, meter, quantity, resource_ref, idempotency_key, occurred_at`。
- 新增 `price_version` / `invoice` / `invoice_line` 三表；定价版本永不修改。
- Rating engine 写成纯函数 `(usage_agg, price_version) → line_items`，用现有 JUnit 体系做重放测试。
- 支付层接 Stripe，账单与支付解耦。

**关账**：复用现有 UTC 日桶约定（Hikari `connection-init-sql: SET time_zone='+00:00'`），宽限期 T+1h 后冻结 `usage_agg`。

### 1.4 订阅生命周期

- `tenant.status` 状态机：`TRIAL → ACTIVE → PAST_DUE → SUSPENDED → DELETED`，旁路 `PAUSED`。
- 现有 `ai_quota` 是用户级上限，新增 `tenant_quota`（租户级）作为上限，用户配额不得越过租户配额。
- entitlements（`tier → features/quotas/isolation`）与账单状态解耦，可供 PEP/OPA 消费。
- 隔离级别变更触发 1.1 的迁移工作流。
- Webhook 验签 + 幂等（复用现有 `IdempotencyGuard` 模式）。

### 1.5 SSO / SCIM

- JWT 签发方 `JwtService.issue()` 加 `tenant_id` claim；4 处验签同步解析：`user-service/AccessTokenAuthenticator`、`agent-gateway/JwtAuthenticator`、`document-service/JwtAuthenticator`、`reminder-service/JwtAuthenticator`。
- 引入 OIDC/SAML broker（Keycloak / WorkOS），业务代码不感知每客户 IdP 差异。
- 域名验证 → 邮箱域名路由到租户 IdP；JIT 开通复用 `AuthService.createUser()`。
- SCIM 2.0 端点 `/scim/v2/Users`、`/scim/v2/Groups`，需新增 Maven 模块或在 user-service 内实现；组变更驱动 OPA 属性缓存失效。
- `external_id` 映射 + ETag 并发控制；离职 DELETE → 软禁用（`app_user.status=DISABLED`）→ 保留期后进入删除流程。

### 1.6 注销后 30 秒可证删除

**措辞必须精确**：30 秒内可兑现的是「全链路不可读 + KMS 签名删除证书」，物理清除异步完成。备份与 WORM 日志无法在 30 秒内改写。

- **数据地图**（前置条件）：MySQL 14 表、pgvector 2 表、MinIO、Redis、RocketMQ topic、Prometheus、第三方 LLM 厂商，逐一登记删除适配器与 SLA。
- **DEK 体系**：每租户一把 DEK，KMS 主密钥包装；文档对象、embedding、缓存值、日志 PII 全部纳入加密。撤销 DEK 即全链路不可读。
- **两级执行**：T+0~30s 并行（撤销 DEK → 吊销会话 → tombstone → 快删系统清除），生成签名回执；T+30s 后异步物理清除，逐系统写 hash-chain 审计。
- **证书**：`scope_hash` = 数据地图 Merkle root，逐系统标注 `shred/delete/unreadable`，KMS 签名，可独立验证。

---

## 2. 分期实施计划

| 期 | 交付 | 涉及关键位置 | 验收标准 |
|---|---|---|---|
| **P0 地基** | `tenant` 表 + 全表 `tenant_id` + JWT claim + 4 处验签 + 强制过滤仓库 | V10 migration、storage.md、4 个 Authenticator、所有 Jdbc 仓库 | 跨租户访问测试 100% 拒绝；现有测试全绿 |
| **P1 隔离 + ABAC** | L1 强制过滤视图、L2/L3 路由骨架、OPA sidecar + 策略 CI | JwtAuthenticationFilter、AskService.prepare、ArchUnit 规则 | 策略变更可回滚；OPA 故障时 fail-closed |
| **P2 计量计费** | usage_event、price_version、invoice、Rating 纯函数、Stripe | agent_execution 扩展、新增 repository/service | 同一组事件重放得到同一张账单 |
| **P3 身份** | OIDC broker、域名路由、SCIM 端点 | JwtService、user-service、新增模块 | IdP 增删用户/组双向同步无漂移 |
| **P4 可证删除** | 数据地图、DEK、删除编排器、签名证书 | 全存储层 + KMS + 编排器 | 30s 达成率 ≥99.9%，证书可独立验证 |

**依赖**：P4 依赖 P0 的 DEK 埋点（加密必须最先做，后补代价极大）；P2 依赖 P0 的租户维度；P3 依赖 P0 的 JWT claim。

---

## 3. 破坏性变更清单（必须同批发布，否则系统不可用）

1. **Java↔Python 协议** — Python `StrictModel` 是 `extra="forbid"`，Java 加字段而 Python 未加会直接 422；反之亦然。两侧必须同批。
2. **JWT 验签** — 4 处独立验签实现，漏改一处则下游服务拒绝所有新令牌。
3. **Flyway** — V1–V9 已应用且 `validate-on-migrate: true`，绝不可修改；新增只能 V10+，且必须同步更新 `storage.md` 对应小节，否则 `MigrationV9ContractTest` 的逐字比对会失败。
4. **唯一键** — `app_user.username` 从全局唯一改为 `(tenant_id, username)`，涉及 DDL 与 3 个查询方法；存量数据需回填默认租户。
5. **Redis 键** — Java 与 Python 共用一批治理键，改键名必须同批，否则治理参数失效（配额、kill switch、预算全部失灵）。
6. **MinIO key** — 改命名后存量对象需迁移，或保留旧路径兼容读取。
7. **Prometheus 标签** — 加 tenant 标签会翻倍时间序列，需评估 cardinality。

---

## 4. 测试策略

- 沿用现有三端门禁：`./scripts/verify-all.sh --offline`。
- 新增契约测试：
  - `TenantIsolationContractTest`：所有业务 repository 的查询 SQL 必须含 `tenant_id` 谓词。
  - `JwtTenantClaimContractTest`：4 个验签实现均能解析 tenant claim，缺失时拒绝。
  - `ProtocolTenantContractTest`：Java DTO 与 Python `AgentAskRequest` 字段集合一致。
  - `MigrationV10ContractTest`：参照 `MigrationV9ContractTest`，比对 V10 SQL 与 `storage.md`。
  - `BillingReplayTest`：同一用量 + 同一价格版本 → 相同账单。
- 架构测试：现有 8 个 `*ArchitectureTest` 需同步更新依赖规则。

---

## 5. P0 实际落地记录（2026-11-18）

> 本节记录代码已实现的状态，供后续 P1 接手时对照。分支 `feat/saas-tenant-foundation`。

### 5.1 已交付

| 项 | 实现 |
|---|---|
| 数据库 | `V10__tenant_foundation.sql`：`tenant` 表 + 默认租户（全 1 UUID）+ 14 张业务表加 `tenant_id`；唯一键租户化；直接引用 `app_user` 的表升级为复合外键 `(tenant_id, user_id)`。`storage.md` §2.18 同步，`MigrationV10ContractTest` 逐字对账 |
| 租户上下文 | `common` 模块新增 `TenantContext`（ThreadLocal，未设置时 `require()` 抛错，fail-closed），`TenantContextTest` 钉住该不变量 |
| JWT | `JwtService.issue()` 加 `tenant_id` claim（重载保留 2 参数版本，P0 用默认租户）；`JwtIdentity` 加 `tenantId()`；四端验签（user / gateway / document / reminder）全部强制解析，缺失即拒绝 |
| 过滤器 | 三个服务的 `JwtAuthenticationFilter` 写 request attribute `docqa.tenantId` + `TenantContext`，finally 清理 |
| 仓库层 | 五个服务共 17 个 `Jdbc*Repository`：接口方法首参统一 `UUID tenantId`，SELECT/UPDATE/DELETE 带 `tenant_id = ?`（首个 WHERE 条件），INSERT 写 `tenant_id` 列，JOIN / EXISTS 补租户关联；`TenantContextTest`、各仓库契约测试同步 |
| 调用方 | HTTP 路径 `TenantContext.require()`；登录/注册/种子用户、MQ 消费者、定时任务、管理面统一显式传 `DEFAULT_TENANT_ID` 并标注 P0/P1 |

### 5.2 P0 明确的临时口径（P1 必须替换）

> 第 1～5 条已由 §5.9 / §5.10 / §5.11 / §5.12 / §5.13 替换；第 6 条已评估闭环：MySQL 见 §5.1，
> MinIO 由 §5.14 收口，pgvector 与 Redis 由 §5.16 / §5.14 论证「维持现状即可，不加租户维度」。

1. **只有一个租户生效**。V10 把全部存量数据回填到默认租户，登录/注册尚无租户解析入口，因此所有请求都拿到默认租户 ID。租户隔离的**结构与约束已就位，但隔离效果要到 P1 接入多租户解析后才可观测**。
2. **登录/注册用 `DEFAULT_TENANT_ID`**：`AuthService`、`RefreshTokenService`、`DemoUserSeeder` 内有 `tenantId()` 帮助方法，P1 改为按邮箱域名路由解析。
3. **管理面聚合只看默认租户**：`AdminQuotaService` / `AdminAiUsageService` / `AdminAiControlsService` / `AdminAuditQueryService` / `AdminUserService` 传默认租户，P1 改为按管理员可见租户集过滤。
4. **MQ / 定时任务**：`AuditOutboxRelay`、`AuditEventService`、`ReminderDueService`、`ReminderTriggerIngestService`、`ParseResultHandler` 等传默认租户，P1 随消息体携带 `tenant_id` 或遍历所有 ACTIVE 租户。（已由 §5.11 / §5.12 全部收口）
5. **Java↔Python 协议未加 tenant 字段**：Python `AgentAskRequest` 是 `extra="forbid"`，双向必须同批发布，因此放在 P1 与 OPA 一起做。P0 的租户过滤在 Java 侧生效后再把 `documentScopes` 传给 Python。（已由 §5.13 替换）
6. **pgvector / MinIO / Redis 键未动**：V10 只覆盖 MySQL 业务库。这三者加租户维度是 P1 范围（见 §1.1）；
   评估结论见 §5.14（MinIO 已加）、§5.16（pgvector / Redis 维持现状）。

### 5.3 实际落地的契约测试

§4 规划了 5 个契约测试，P0 落地了 3 个（其余属于 P1/P2 范围）：

| 测试 | 位置 | 钉住的不变量 |
|---|---|---|
| `MigrationV10ContractTest` | `document-service/src/test/.../migration/` | V10 SQL 与 `storage.md` §2.18 逐字一致；14 张表都加 `tenant_id`；默认租户常量存在；`uk_app_user_tenant_username (tenant_id, username)` 存在且旧的全局唯一键已删；迁移里没有破坏性语句（只允许 drop 约束） |
| `TenantIsolationContractTest` | 同上 | 扫描 5 个模块的全部 `Jdbc*Repository.java`，断言 **恰好 17 个**且每个都含 `tenant_id`；4 个验签实现都含 `TenantContext.CLAIM_NAME` 与 `parseTenantId`；14 张表都出现在 V10 里 |
| `TenantContextTest` | `common/src/test/.../tenant/` | `require()` 未设置时抛 `IllegalStateException`；`DEFAULT_TENANT_ID` 字面量固定；`clear()` 后 `currentOrNull()` 为 null |

> `JwtTenantClaimContractTest` 的三个用例（显式租户签发 / 缺 claim 拒绝 / 非 UUID 拒绝）
> 直接写进了各服务已有的 `JwtServiceTest`、`JwtAuthenticatorTest`，没有单开一个类。
> `ProtocolTenantContractTest`、`BillingReplayTest` 分别属于 P1（协议）与 P2（计费），P0 未创建。

### 5.4 已知环境问题（非代码缺陷）

三端门禁本机跑不全，原因都在环境，与本次改动无关：

1. **`DOCQA_AUDIT_APPEND_ONLY_GUARD=enforce`**（宿主机环境变量）
   → `common` 模块 `AuditAppendOnlyAutoConfigurationTest.disabledWithoutProperty` 必失败。
   该测试断言「没有配置项时不装配」，而 `@ConditionalOnProperty` 只判断属性存在与否，
   环境变量让属性恒存在。**基线（未改任何代码）同样失败**，已用 `git stash` 验证。
   跑门禁前临时 `unset DOCQA_AUDIT_APPEND_ONLY_GUARD`，或改测试屏蔽环境属性。

2. **`mvn verify` 在 spring-boot repackage 阶段失败**
   → `Unable to rename 'user-service-1.0.1.jar' to '...jar.original'`，Windows 文件锁
   （jar 被上一轮构建或 IDE 占用）。与代码无关。替代做法：`mvn -o test` +
   单独 `mvn -o checkstyle:check`，两者都通过即等价于 `verify` 的校验部分。

3. **Python `pytest` 在本机无法启动**
   → `psycopg_binary._psycopg` DLL 被宿主应用管控策略拦截；改 `PSYCOPG_IMPL=python` 后
   又报 `ImportError: libpq library not found`（本机未装 `libpq.dll`）；
   `PSYCOPG_IMPL=pure` 在该 psycopg3 构建里不是合法值。
   P0 **零 Python 改动**，故不影响本次验收；但 P1 动协议之前必须先解决这个，
   否则 Python 侧没有可用的测试反馈回路。

### 5.5 P0 实际验证结果（2026-11-18）

```
mvn -o test        → BUILD SUCCESS，1218 tests，0 failures / 0 errors
                     common 131 · mq 4 · user 154 · document 196 · gateway 366 · reminder 314 · audit 53
mvn -o checkstyle:check → exit 0，无违规
frontend-vue: eslint . → 0；vitest run → 52 files 全过；vite build → 成功（306.69 kB / gzip 105.24 kB）
ai-python / frontend-vue → 本轮零代码改动，无需验收
```

### 5.6 迁移在真实 MySQL 8.4 上的两次失败（P0 收尾修正）

**这是 P0 最值钱的一条教训**：五道契约测试全部通过、1218 个单测全绿，V10 仍然在真库上
连续失败两次。静态测试比对的是文本，比对不了 SQL 语义。

**失败一：`INSERT command denied to user 'ss_migrator'`**

- 现象：V10 在 `CREATE TABLE tenant` 之后插一行默认租户。
- 根因：`ss_migrator` 只有 schema 级 DDL + `flyway_schema_history` 的 DML，业务表
  写权限为 0（`ops/sql/README.md` 明确规定，V1–V9 全是纯 DDL）。**V10 是第一个含 DML 的迁移**。
- 后果：MySQL 的 DDL 不参与事务，崩溃点之前的语句已提交 → 库停在「`tenant` 表已建、
  14 张业务表一条没动」的半迁移态，且 `flyway_schema_history` 留下 `success=0`。
- 修法：
  1. V10 改为**纯 DDL**，默认租户行移到 `document-service` 启动期的
     `DefaultTenantInitializer`，用 app 账号 `INSERT IGNORE` 幂等写入。
  2. 新增断言 `MigrationV10ContractTest.migrationStaysPureDdl`：迁移里出现
     `INSERT/UPDATE/DELETE/REPLACE` 就红。把 `ops/sql` 里那句「迁移不需要写业务数据」
     从口头约定变成会失败的测试。
  3. `least-privilege.sql.tmpl` 给 `ss_app` 补 `tenant` 的 `SELECT/INSERT/UPDATE`
     ——**故意不给 `DELETE`**：注销租户走 `status='DELETED'` 软删，`chk_tenant_status`
     约束里已经有这个状态，硬删会打断所有存量行的归属。

**失败二：`ERROR 1826 Duplicate foreign key constraint name`**

- 现象：`ALTER TABLE app_user_role DROP FOREIGN KEY fk_app_user_role_user,
  ADD CONSTRAINT fk_app_user_role_user FOREIGN KEY (tenant_id, user_id) ...` 被拒。
- 根因：MySQL 在一条 `ALTER` 内**先校验约束名唯一性，再应用 DROP**，所以同名重建必冲突。
- 修法：7 个复合外键改为 `fk_<表>_tenant_<目标>`。语义上也该换名——约束已从单列外键
  变成带租户维度的复合外键，沿用旧名会让排障时误解结构。

**半迁移的手工回滚（两次都用到了）**

```sql
DROP TABLE IF EXISTS tenant;
ALTER TABLE app_user
  DROP INDEX uk_app_user_tenant_username,
  DROP INDEX uk_app_user_tenant_id,
  DROP INDEX idx_app_user_tenant_created,
  DROP COLUMN tenant_id,
  ADD UNIQUE KEY uk_app_user_username (username);
DELETE FROM flyway_schema_history WHERE version = '10' AND success = 0;
```

**由此得出的 P1 硬要求**：必须引入 **Testcontainers 集成测试**（本机已有 `mysql:8.0` /
`mysql:8.4` 镜像且 Docker 可用，可离线跑），把「V1→V10 全链路能在真 MySQL 上跑通」
变成 CI 的一部分。P0 的三道契约测试补不上这个洞——它们只证明文本一致，不证明能执行。

### 5.7 P1-A：真库迁移集成测试（已落地，2026-11-18）

`document-service/src/test/java/com/docqa/document/migration/MigrationOnRealMysqlTest.java`。
起一个 `mysql:8.4` 容器，**逐字复刻 `ops/sql/least-privilege.sql.tmpl` 的授权**后跑 V1→V10，
六个用例钉住六件事：

| 用例 | 钉住什么 |
|---|---|
| `appliesAllMigrationsOnRealMysql` | 10 份脚本全部执行、账本无 `success=0`、版本序列 1→10 完整 |
| `everyBusinessTableCarriesTenantId` | 14 张表都有 `tenant_id`，且 `NOT NULL`、默认值指向默认租户 |
| `migrationStaysPureDdlOnRealMysql` | 迁移后 `tenant` 表为空；**用 migrator 账号真发一条 INSERT 验证被拒**（1142） |
| `rebuildsKeysAndForeignKeysPerTenant` | 10 个租户化唯一键就位、7 个旧单列外键全部消失、复合外键恰好 7 个 |
| `seedsDefaultTenantIdempotently` | 走**生产代码** `DefaultTenantInitializer` + `JdbcTenantBootstrapRepository`（不是测试里另写 SQL），重复执行两次只有一行 |
| `rejectsCrossTenantReferences` | 复合外键真的拦截跨租户写入：租户 B 的 chunk/app_user_role 挂到租户 A 的 document/app_user 上报 1452，且报错里能看到 `fk_*_tenant_*` 约束名；同租户写入必须照常通过 |

它补上了 §5.6 暴露的全部缺口：权限模型（migrator 能不能写业务表）不再靠正则猜，
跨租户隔离第一次有了**行为级**证据。无 Docker 的机器整类跳过，需要强制关闭设
`DOCQA_DB_IT=off`；`mvn test` 与 `scripts/verify-all.sh` 都会自动带上它。

**排障记录（值得留档，别的地方还会踩）**

1. **Docker Desktop 的 Windows 管道拒绝老版本 API 路径**。testcontainers/docker-java
   默认从 `GET /v1.32/info` 起步协商，而 Docker Desktop 的管道代理对 `/v1.32/*` 直接回
   `400 Bad Request`（无版本的 `/info` 却是 200）。表现为 `Could not find a valid Docker
   environment`，且整类被**静默跳过、构建仍绿**——最危险的形状。诊断方法：把
   `DOCKER_HOST` 指向自建 HTTP 服务器与字节级 TCP→命名管道桥，抓到实际请求。
   修法：测试类的 static 块把 `api.version` 系统属性设为 `1.44`（尊重已显式指定的
   `API_VERSION` 环境变量）；同时移除了 `~/.testcontainers.properties` 里过期的
   `docker.client.strategy=NpipeSocketClientProviderStrategy` 钉死（该策略不读
   `api.version`，备份在 `~/.testcontainers.properties.bak`）。
2. **`MySQLContainer.configure()` 会覆盖 `MYSQL_ROOT_PASSWORD`** 为 `withPassword` 的值，
   `withEnv` 设不进去；且 1.20.4 不再代设 `MYSQL_ROOT_HOST`，缺它则 root 只授权给容器内
   localhost，宿主机 JDBC 报 `Access denied for 'root'@'172.17.0.1'`。两者都已在测试里注释说明。
3. **依赖钉版**：Boot 3.3.5 BOM 导入 testcontainers-bom 1.19.8，其 jar 不在离线仓库，
   且 BOM import 的属性不可被本 POM 覆盖——根 pom 里逐条直接声明 1.20.4
   （`testcontainers/jdbc/database-commons/mysql` 四条缺一不可，只钉第一条时传递依赖
   仍会落回 1.19.8）。

### 5.8 授权落地与运行时环境事故排障（2026-11-18 晚）

**租户授权落地**：`ops/sql/apply-least-privilege.ps1` 幂等执行后，`ss_app@localhost`
获得 `tenant` 表 SELECT/INSERT/UPDATE（无 DELETE，软删走 `status='DELETED'`）。
`apply-least-privilege.ps1` 的 `$businessTables` 对账清单补入 `tenant`
（verify 脚本从 `information_schema` 动态读表，无需改）。
`document-service` 启动日志证实端到端打通：
`DefaultTenantInitializer: Seeded default tenant slug=default` +
`AuditAppendOnlyGuard: verdict=OK`。

**排障记录（三连撞车，都是"两个项目共享一个内核"的病）**：

1. **RocketMQ 9876 端口撞车**。恢复 agentflow 时 `docker start` 拉起的
   `agentflow-rmq-namesrv`（Docker Desktop）与 WSL 发行版里 host 网络模式的
   `docqa-rmq-namesrv` 争 9876——WSL2 NAT 模式下 Docker Desktop VM 与发行版
   共享端口面，后者报 `BindException: Address already in use` 无限崩溃
   （`Restarting (255)`），`docqa-infra.service` 卡 `activating`。孤儿 socket
   （`ss -tlnpe` 无进程属主、cgroup 空）是共享端口面的指纹。处置：停
   agentflow RMQ 两容器后 9876 立即释放，docqa namesrv/broker 自愈。
   **两个项目的 RMQ 不能同时跑**，除非给 agentflow 换端口。
2. **Redis 6379 同病**。WSL 里 `*:6379` 的监听者其实是 Docker Desktop 的
   `agentflow-redis`（停掉即消失）；DOCQA 设计上中间件栈没有 Redis，
   `DOCQA_REDIS_HOST=192.168.40.128` 是已失联的历史 VM 地址。解法不是蹭
   agentflow 的 Redis（无密码 + 数据混用，双重不可接受），而是给 docqa-infra
   加**专属 Redis**：`local-stack.compose.yaml` 新增 `docqa-redis`
   （host 网络、**16379 避让**、`--requirepass` 从 `/opt/docqa-infra/.env` 注入）。
   Windows 侧同步：`DOCQA_REDIS_PORT=16379`、`DOCQA_REDIS_HOST=<当前 WSL IP>`、
   `DOCQA_AI_REDIS_URL=redis://:<密码>@<WSL IP>:16379/0`。
3. **Machine 作用域残留变量**。`DOCQA_REDIS_PASSWORD`/`DOCQA_DB_PASSWORD` 在
   Machine 作用域有一份旧值，User 清掉后 Machine 仍随新进程注入（清 Machine
   需管理员，本次未清）；`run-local.ps1` 导入顺序 Machine→User，User 有值时覆盖
   Machine，因此 User 侧写对即可生效。
4. **沙箱回收 Java 子进程**。工具调用内经 `Start-Process` 拉起的 jar 会在
   调用结束后被回收（此前已知），成功启动的 document-service 也一样。
   稳定做法：后台任务里 `source` 环境变量导出文件后直接 `java -jar` 前台跑，
   由后台任务本身锚定进程树。

### 5.9 P1-B：租户解析入口与跨租户拒绝验收（2026-11-19）

替换 §5.2 的第 1、2 条临时口径，并顺带收口第 3 条里的 `AdminUserService`。

**入口口径（与 P0 草案的偏差）**：原计划按邮箱域名路由，但当前没有域名归属表、也没有 IdP，
因此改为登录/注册请求体里**可选** `tenantSlug`：

- 缺省 / 空白 / `default` → 默认租户，P0 存量客户端无需改动；
- 显式 slug 经 `trim + lowercase` 后由 `TenantResolver` 查 `tenant` 表裁决；
- 只允许 `ACTIVE` / `TRIAL`，`PAST_DUE` / `SUSPENDED` / `DELETED` 与未知 slug 一律返回
  与口令错误相同的 `USR-AUTH-001`，避免租户清单被枚举；
- 该字段**只在尚无 JWT 的登录/注册入口被信任**；此后一切 tenantId 取自已验签 JWT 的
  `tenant_id` claim 或从刷新令牌摘要反查，请求体/Header 里的租户字段不参与授权。

**代码落点**：

| 组件 | 作用 |
|---|---|
| `TenantRepository` / `JdbcTenantRepository` | 按 slug 查控制面 `tenant` 表；刻意不带 `tenant_id` 谓词，契约测试显式豁免（扫描数 18→19） |
| `TenantResolver` | slug 归一化、状态白名单、缺省回落，是「哪些租户可登录」的单一事实来源 |
| `AuthService` | 登录/注册先解析租户；登录限流键、注册幂等 scope/指纹都带 tenantId；JWT 与刷新令牌家族绑定该租户 |
| `RefreshTokenService` | refresh / logout 无 JWT，先用 `findTenantIdByHash` 从凭据摘要反查租户，后续所有读写仍带 `tenant_id` 谓词 |
| `AdminUserService` + `UserController` | 管理面首参 tenantId 来自调用者 JWT，不再回落默认租户；幂等 scope 与指纹同样带租户 |

**验收（行为级，不只看 SQL 文本）**：`AuthServiceTest` 钉住显式 slug、未知/停用租户拒绝、
同名账号跨租户不共享失败预算、刷新令牌不出租户；`AuthControllerTest` 钉住 HTTP 层 slug
进入 JWT claim；`UserControllerTest` 钉住别的租户的管理员令牌拿不到本租户用户（404）；
`AdminUserServiceTest` 钉住跨租户目标不可见与幂等键按租户隔离；
`TenantIsolationContractTest` 的仓库扫描数改为 19 且仅剩两个有据可查的豁免。

验证：`mvn -o test` → 1240 tests / 0 failures / 0 errors；`mvn -o checkstyle:check` → 0 违规。

**尚未完成（不要误读为 P1 整体交付）**：§5.2 第 3 条由 §5.10 继续收口；第 4 条 MQ/定时任务、
第 5 条 Java↔Python 协议、第 6 条 pgvector/MinIO/Redis 当时仍在待办（此后第 5 条由 §5.13 完成，
第 6 条由 §5.14 MinIO + §5.16 pgvector/Redis 评估闭环）；没有创建真实第二租户的
管理端点（HTTP 级跨租户验收目前靠带租户 JWT + 仓储打桩），域名路由、OPA ABAC 也未开始。
§8 的 `tenant_id` 可观测标签当时尚未落到日志/指标（日志侧已由 §5.13 + §5.15 补齐，指标仍待评估），本阶段只是让隔离效果**在测试里可观测**。

### 5.10 管理面租户收口（2026-11-19，§5.2 第 3 条完成）

四个 agent-gateway 管理面服务不再回落到默认租户，tenantId 由控制器从
`@RequestAttribute(TenantContext.REQUEST_ATTRIBUTE)` 取得——该属性由 `JwtAuthenticationFilter`
从已验签 JWT 写入，请求参数无法伪造：

| 服务 | 变化 |
|---|---|
| `AdminQuotaService` | 配额分页 / 改写 / 删除与审计条目都落在调用者租户；跨租户目标按 404 处理 |
| `AdminAiUsageService` | `totals` / `byDay` / `byStatus` / `topUsers` 四组聚合只统计调用者租户 |
| `AdminAiControlsService` | Kill Switch 仍是全局运行时开关，但管理审计写入调用者租户，避免串账 |
| `AdminAuditQueryService` | 审计分页查询限定调用者租户，filter/cursor 参数不改变租户边界 |

服务测试统一改用非默认租户常量，新增跨租户目标不可见、聚合只查本租户、审计限定租户等用例；
控制器测试为每个请求显式注入租户属性并验证调用签名。

验证：`mvn -o test` → 1243 tests / 0 failures / 0 errors；`mvn -o checkstyle:check` → 0 违规。

### 5.11 MQ / 定时任务的租户传播（2026-11-19，§5.2 第 4 条主体完成）

把「无 HTTP 行上下文」的两条链路从固定默认租户改为**租户随行、随消息传播**，
选择消息体携带 `tenant_id` + 定时任务跨租户扫描，而不是遍历 ACTIVE 租户清单：
后者需要引入跨服务租户目录与每租户游标，改动面与失败模式都更大；审计事件不允许丢失，
租户状态治理放在登录/网关入口，而不是靠定时任务丢弃事件来实现。

| 链路 | 生产侧 | 扫描侧 | 消费侧 |
|---|---|---|---|
| 审计 | `AuditEvent` 加 `tenantId`；工厂生成时为 null，不读 HTTP 上下文 | `AuditOutboxRelay` 用单一全局游标跨租户扫 outbox，`findOutboxBatch`/`outboxBacklog` 去 tenantId 参数；`OutboxExecution` 带回行的 `tenant_id` | relay 投递前用 `event.withTenant(row.tenantId)` stamp；`AuditMessageHandler` 反序列化后 `AuditEventService.ingest` 按消息 tenantId 落库 |
| 提醒 | `ReminderDueEvent` 加 `tenantId` | `findDue` 跨租户扫描，返回 `DueReminder(tenantId, row)` 而非 `List<ReminderRow>`；`ReminderDueService` 用每行 tenantId 做 stamp 与 CAS 写入 | `ReminderDueMessageHandler` 反序列化后 `ReminderTriggerIngestService.ingest` 按消息 tenantId 落库 |

**兼容策略**：两个消费端对 `tenantId == null` 的在途旧消息按默认租户收敛并打 WARN
（P0 存量数据全在默认租户），新消息必须携带。租户字段加在消息体尾部，
`FAIL_ON_UNKNOWN_PROPERTIES` 不会因新增字段拒绝旧消费者；但 agent-gateway /
reminder-service / document-service 的生产配置是 `true`，**本批改动要求相关服务同批发布**，
否则老消费者读到带 `tenantId` 的新消息会拒收。Java↔Python 协议未改（仍属第 5 条）。

**边界**：`findOutboxBatch` / `findDue` 是有意的跨租户扫描，请求参数不再带 tenantId；
写侧 `insert` / `advance` / `complete` 仍逐行带 `tenant_id` 谓词，租户边界在写侧保证。
`TenantIsolationContractTest` 是文件级判定，这两个文件因写侧仍含 `tenant_id`，无需新增豁免，
已在测试内注释说明。`AuditEventService.findByTraceId` 目前无 HTTP 入口（审计服务是纯消费者），
暂保留默认租户，待管理面暴露查询时一并收口。

**尚未完成**：`ParseResultHandler`（Python 解析回写）由 §5.12 以「按 documentId 反查」收口，
不走消息体携带；§5.2 第 5 条协议已由 §5.13 完成，第 6 条 pgvector/Redis 已由 §5.16、MinIO 由
§5.14 评估/收口；域名路由、OPA ABAC 仍未开始。

验证：`mvn -o test` → 全绿；`mvn -o checkstyle:check` → 0 违规。

### 5.12 文档解析链路的租户归属（2026-11-19，§5.2 第 4 条收口）

`ParseResultHandler` / `VectorChangeResultHandler` / `DocumentReconciliationJob` 原来都是
`private static final TENANT_ID = DEFAULT_TENANT_ID`。这批改为**以持久化的 document 行为事实来源**：

| 路径 | 原口径 | 新口径 |
|---|---|---|
| `parse.completed` / `parse.failed` 消费 | `findById(DEFAULT, documentId)`，只认默认租户 | `findTenantDocumentById(documentId)` 一次查询返回 `TenantDocument(tenantId, row)`，后续 chunk 写入、markIndexed / markFailed / abortReindex / markDead、重试发布全部用该 tenantId |
| `vector-change-completed` 消费 | 同上 | 同上，`recordVectorConfirmation` 用行上的 tenantId |
| `findStaleParsing` / `findStaleReindex` / `findUnconfirmedDeletions` | 只扫默认租户 | 跨租户扫描，返回 `TenantDocument`；对账写侧 `abortReindex` / `incrementRetryAndMarkParsing` / `markDead` 按行上 tenantId 执行 |

**为什么不改消息体**：Java→Python 的 `ParseRequestedEvent` / `VectorChangeRequestedEvent` 被
Python 侧 Pydantic 模型以 `extra="forbid"` 校验，加字段必须双端同批发布；而 documentId 是全局唯一
UUID，按它反查归属租户既不会串账，也天然覆盖存量在途消息。这样 Java↔Python 消息契约一个字节
都不用改，§5.11 的「同批发布」约束不扩散到 Python 侧。

**边界**：`findTenantDocumentById` 与三个 `findStale*` 是有意的跨租户读，查询不带 tenant_id 谓词，
但 SELECT 带出行的 tenant_id；所有写侧仍逐行带 tenant_id 谓词。这些读只服务于内部可信的 MQ/定时
任务，documentId 不是用户可控的跨租户入参；若行不存在则消息被丢弃并标记已处理。租户边界由
documentId 的全局唯一性与写侧谓词共同保证。`TenantIsolationContractTest`
已在注释中记录这四个方法的有意跨租户性质，文件级判定因写侧仍含 tenant_id 而无需新增豁免。

**评估结论**：Python 侧的 pgvector 写入按 documentId 而非 tenant_id 分片，经 §5.16 确认为
符合 D-87 红线的有意设计（靠授权 scope 隔离），不视为缺陷；Java→Python 的 `AgentAskRequest`
已由 §5.13 带上 `tenantId`（第 5 条完成）；第 6 条 MinIO 由 §5.14、pgvector/Redis 由 §5.16
收口；域名路由、OPA ABAC 仍未开始。

验证：`mvn -o test` → 全绿；`mvn -o checkstyle:check` → 0 违规。

### 5.13 Java↔Python 协议租户上下文（2026-11-19，§5.2 第 5 条完成）

`PythonAskRequest` 与 Python `AgentAskRequest` **同批**新增必填 `tenantId`（camelCase alias），
值取自网关 `TenantContext.require()` 解析出的 `prepared.tenantId()`。协议边界不再只传 `userId`，
Python 侧从此能直接以租户维度做可观测与后续 ABAC 判决。

| 项 | 实现 |
|---|---|
| Java DTO | `PythonAskRequest` 增加 `UUID tenantId`；`AskService.pythonRequest()` 用 `prepared.tenantId()` 填充，同步与 SSE 两条路径共用同一构造函数 |
| Python 协议 | `AgentAskRequest` 增加 `tenant_id: uuid.UUID = Field(alias="tenantId")`，必填；`StrictModel(extra="forbid")` 保证任一侧字段漂移直接 422 |
| 契约测试 | 新增 `ProtocolTenantContractTest`：反射读 Java record 组件、按源码解析 Python `Field(alias=...)`，断言双端字段集合逐字一致（15 个）且 `tenantId` 必填；直接跑在 `mvn -o test` 内，不增加门禁步骤 |
| 可观测 | Python `AgentAskService` 在 `agent.graph` span 写 `tenant.id`（同步/流式双路径）；`logging_setup.py` 日志白名单加入 `tenantId` |
| 测试同步 | Python `conftest` 定义 `TENANT_ID` 并注入默认 payload；协议模型测试字段数 14→15，新增 `tenantId` 缺字段/非法 UUID 用例；Java 两个 `PythonAskRequest` 构造点同步更新 |

**边界**：本批只把租户上下文送过协议边界并使之可观测；Python 侧检索仍以
`documentScopes` 的全局唯一 `documentId` 为边界（Java 侧 `JdbcDocumentScopeRepository`
已带 `tenant_id` 谓词）。pgvector 按租户分片仍属 §5.2 第 6 条，届时可直接使用
`request.tenant_id` 作路由键，无需再改协议字段。若未来真要分 collection，协议字段已就位；
但经 §5.16 评估，维持 D-87 红线、靠授权 scope 隔离即可，当前不分片。

验证：`mvn -o test` → 全绿；Python 全量测试 → 全绿；`mvn -o checkstyle:check` → 0 违规。

### 5.14 MinIO 对象键租户化（2026-11-19，§5.2 第 6 条部分完成）

唯一构造点 `DocumentService.createNewDocument()` 原为 `documents/{userId}/{documentId}/source`，
改为 `documents/{tenantId}/{userId}/{documentId}/source`，收敛到 `ObjectKeys.source()`。
新增 `ObjectKeysTest` 锁定「租户为首段、不同租户不共用键」。

**为何不做存量迁移**：`object_key` 逐行落在 `document` 表，整条上传续传 / 完成 / 解析回写 /
删除链路都从 `document.objectKey()` 读取，从不重新构造。因此历史对象继续读旧键、新对象写新键，
两种格式共存，符合 §3 第 6 条「保留旧路径兼容读取」，无需搬对象。Python parse worker 对
`objectKey` 只校验「相对路径 + 无空段/穿越」，多一段 UUID 不影响校验，故 Java↔Python 消息契约零改动。

**§5.2 第 6 条剩余**：pgvector 与 Redis 的租户维度均已评估为「维持现状」——pgvector 见 §5.16
（D-87 红线 + 授权 scope 已隔离，加列反而触发 HNSW 后置过滤）；Redis 键**不盲目加段**：
`userId` / `traceId` 是全局唯一 UUID 天然不串租户；
`agent:kill-switch` 是平台级全局熔断（见 `AdminAiControlsService` 注释），改成按租户会破坏
「一键全停」语义，当时属于产品决策、留待确认。
> 状态更新（2026-11-19）：该决策已由 **D3** 拍板落地，见 §5.20 —— 平台级全局键
> **保留不变**，另新增 `agent:kill-switch:tenant:{tenantId}`，两者 OR 叠加：既守住
> 「一键全停」，又支持只停单个租户。本节「不盲目给所有 Redis 键加租户段」的结论仍成立
> （`userId` / `traceId` 依旧不加段），只是 kill switch 从「单一全局键」扩为「全局 + 租户两级」。

验证：`mvn -o test -pl document-service -am` → 131 + 214 tests 全绿；全量门禁见本批提交。

### 5.15 Java 侧 tenant_id 可观测标签（2026-11-19，§8 日志侧完成）

Python 侧已在 §5.13 把 `tenantId` 加入日志字段白名单并在 `agent.graph` span 写 `tenant.id`；
本批补齐 Java 侧对称能力，使 `tenant_id` 真正落在日志行而不只是存在于 `TenantContext`。

| 项 | 实现 |
|---|---|
| MDC 镜像 | `TenantContext.set(UUID)` 在写入 ThreadLocal 的同时 `MDC.put("tenantId", tenantId.toString())`；`clear()` 同步 `MDC.remove`。与 `TraceIdContext` 用 MDC 承载 `traceId` 完全对称 |
| 日志模式 | 实际设置租户上下文的三个服务（agent-gateway / document-service / reminder-service）在 `logging.pattern.console` 渲染 `traceId=%X{traceId:-} tenantId=%X{tenantId:-}`，保留原时间/级别/PID/线程/logger 字段；未设置时渲染为空 |
| 覆盖面 | 三个 `JwtAuthenticationFilter` 无需改动即可自动获得 MDC；任何今后设置 `TenantContext` 的 MQ 消费者、定时任务也自动获得，无需逐处打点 |
| 测试 | `TenantContextTest` 新增 set 写 MDC / clear 清 MDC 两例；`JwtAuthenticationFilterTest` 在 MockFilterChain 内断言链路执行期间 MDC 可见且出口已清理 |

**边界**：本批只做日志标签（§8 的可观测部分），**不动 Prometheus 指标**。按 §3 第 7 条，给指标加
tenant 标签会放大时间序列基数，需先评估租户规模（见 §6 第 1 条）再决定，避免一次改出高基数事故。
跨租户扫描类后台任务（AuditOutboxRelay / DocumentReconciliationJob / ReminderDueService）逐行携带
`tenantId` 写库但不设置 `TenantContext`，故这些任务的日志不带租户标签——它们在单条日志里也不对应
唯一租户，保持现状比强行逐行 set/clear 更不容易误导排障。

验证：`mvn -o test` 全绿；`mvn -o checkstyle:check` 0 违规。

### 5.16 pgvector 不加 tenant_id 列的判断（2026-11-19，§5.2 第 6 条评估闭环）

§1.1 D-87 设计红线明确「pgvector 两张向量表没有任何按用户过滤的列」。复核后确认这不是待补的
缺口，而是**有意且与多租户兼容的边界设计**，本批决定**不**给向量表加 `tenant_id`，理由如下。

**检索隔离已经由上游完整保证，且不依赖向量表内存租户列**：

| 环节 | 保证 | 测试守护 |
|---|---|---|
| 用户输入 documentId → 授权 scope | `JdbcDocumentScopeRepository.findByUserAndIds` 的 `WHERE tenant_id = ? AND user_id = ? AND id IN (...)`；跨租户/他人 id 在此被过滤掉，根本进不了 scope | `JdbcDocumentScopeRepositoryTest.softDeletedDocumentsAreOutsideTheScope` |
| scope → 向量检索 | `PgVectorRepository.search` 只按 `(document_id, index_version) IN scopes` 查询，没有 scope 的文档不可达 | `test_retrieval_only_returns_authorized_document_scopes` / `test_retrieval_empty_scopes_short_circuits` |
| documentId 唯一性 | 全局唯一 UUID，不存在跨租户撞 id；写入侧由 document-service 的 tenant 谓词收口（§5.12） | `ObjectKeysTest` / §5.12 契约测试 |

**为何不加反而更安全**：§1.1 已记录「HNSW 后置过滤会破坏 top-k，必须用分区表或按 tenant 分
collection，不能简单加 WHERE」。给两张表加列还要：双端破坏性发布（Python `StrictModel
extra="forbid"`，向量变更消息字段集合必须同步）、存量向量回填默认租户（两张表、两万个 chunk 量级）、
以及重建 HNSW 索引。收益仅仅是纵深防御——而它守护的越权路径在上游已经被两层谓词 + 全局唯一 id
封死。投入产出比与风险都指向「不做」。

**结论**：§5.2 第 6 条的 pgvector 部分以「维持 D-87 红线、靠授权 scope 隔离」收口，不新增列。
与 §5.14 Redis 的判断一致：先确认隔离语义是否已被上游保证，再决定是否加租户段，避免把「看起来
该加」误当成「隔离漏洞」。真正需要租户维度的是 MySQL 业务表（已在 V10 完成）与 MinIO 对象键
（§5.14 完成）；Redis 全局熔断键保持平台级语义不变。

验证：`JdbcDocumentScopeRepositoryTest` 与 Python `test_rag.py` 相关用例已在门禁中全绿；本批仅文档判断，无代码改动。

### 5.17 parse_worker MQ 消息跨语言契约测试（2026-11-19）

`ProtocolTenantContractTest` 只锁住问答协议 `AgentAskRequest`，但 document-service ↔ Python
parse_worker 之间还有 6 个 MQ 消息同样受 `extra="forbid"` 约束：任一侧增删字段，消息就解析失败
落入 DLQ，且因为是异步链路不会立即暴露。新增 `ParseWorkerProtocolContractTest`（在
document-service，复用源码解析范式）一次性钉死双端字段集合：

- 覆盖 `ParseRequestedEvent` / `ParsedChunk` / `ParseCompletedEvent` / `ParseFailedEvent` /
  `VectorChangeRequestedEvent` / `VectorChangeCompletedEvent` 共 6 个消息；
- 逐类断言 Java record 组件名集合 == Python `Field(alias=...)` 线上名集合 == 固定期望表；
- 另断言 6 个消息**均不带 tenantId**，把 §5.12「靠 documentId 全局唯一 + 消费侧反查归属租户、
  消息体不携带租户字段」的设计决策钉死——防止有人以「补租户维度」为名加字段触发双端破坏性发布。

本批核对结果：双端字段当前完全一致，无漂移；纯增量守护，未改任何生产代码或消息结构。

验证：`mvn -o test -pl document-service -am -Dtest=ParseWorkerProtocolContractTest` → 2 tests 全绿。

### 5.18 真实第二租户仓储级验收（2026-11-19，收官批次）

此前所有隔离测试都是单服务 mock：仓库契约测试只比对 SQL **文本**，
`TenantIsolationContractTest` 也只检查「源码里出现 tenant_id 字符串」。文本对了不代表 MySQL
真的按谓词拒绝跨租户读——这正是 `MigrationOnRealMysqlTest` 类注释里记录过的教训（V10 初版
五个契约测试全绿却在真库连续失败两次）。本批把验收推进到**真实 MySQL + 真实仓储代码**这一层。

复用已有的 Testcontainers MySQL 8.4 基建（起容器 → V1→V10 迁移 → app 最小权限账号），
在 `MigrationOnRealMysqlTest` 中新增一例 `documentRepositoryIsolatesReadsByTenantOnRealMysql`：

| 断言 | 意义 |
|---|---|
| 租户 A 按 `findById(tenantA, docA)` 命中自己的文档 | 同租户正常路径没被写宽 |
| `findById(tenantB, docA)` / `findByIdAndUser(...)` 均空 | Java 仓储的 `tenant_id` 谓词在真库上真实拒绝跨租户读，不是 mock |
| `findTenantDocumentById(docA)` 带出 `tenantId = tenantA` | §5.12 有意跨租户反查路径可用，MQ/定时任务能按全局唯一 documentId 定归属租户 |

用独立 UUID / slug 构造数据，与既有 `rejectsCrossTenantReferences` 的构造解耦，测试顺序无关。
Docker 不可用时整类仍由 `Assumptions` 优雅跳过，不污染无容器环境。

**边界**：这是**仓储级**真库验收，不是跨进程 HTTP 端到端。当前三个服务各自验签、无统一
e2e 编排，HTTP 级全链验收仍需产品侧确认是否引入 e2e 测试服务；本批以零生产改动的增量
把最容易静默失效的「谓词文本对但语义错」补上真库证据。管理员谁能创建租户（平台管理员
vs 租户内 ADMIN）仍未定义，租户供给端点因此未动，见 §6。

验证：`mvn -o test -pl document-service -am -Dtest=MigrationOnRealMysqlTest` → 7 tests 全绿
（含 Docker 真跑）；全量门禁见本批提交。

**后续**：§5.18 记的「租户供给端点未动」由 §5.19 收口；HTTP 级全链验收按 D4 结论暂不引入。

---

### 5.19 D1：配置保护的 bootstrap 租户供给（2026-11-19，决策清单 D1-B 落地）

`tenant` 表、`TenantResolver.resolve(slug)` 与 17 个仓库的 `tenant_id` 谓词都已就绪，但此前
**没有任何创建租户的入口**——这是 §5.18 无法做真实第二租户 HTTP 验收的根因。本批按决策清单
D1-B 补上，仍不引入平台管理员角色。

| 新增 | 作用 |
|---|---|
| `TenantBootstrapProperties` | `docqa.tenant-bootstrap.token`，默认空串；`isEnabled()` 为 false 时端点整体禁用 |
| `TenantAdminController` | `POST /api/v1/admin/tenants`，鉴权头 `X-Bootstrap-Token` |
| `CreateTenantRequest` / `TenantCreatedData` | 请求 `slug/name/tier/isolation`；响应只回 `id/slug/status` |
| `TenantProvisioningService` | 常量时间令牌比较、slug 归一化、`INSERT IGNORE` 幂等 |
| `TenantRepository.insert` / `JdbcTenantRepository` | 写通道，依赖 V10 的 `uk_tenant_slug` 唯一键 |

几处刻意的取舍：

- **共享令牌而非 JWT**：引导期还没有任何租户/管理员，用 JWT 守卫会先要求一个不存在的身份；
  引入 `PLATFORM_ADMIN` 又要改角色枚举、claim 与三服务守卫，超出引导入口的必要范围。
  比较用 `MessageDigest.isEqual` 而非 `String.equals`，避免按响应耗时逐字符猜解令牌。
- **默认安全**：`application.yml` 里 token 默认空，未注入时端点返回 403，不存在「部署即开放」窗口。
- **幂等**：`INSERT IGNORE` 返回 0 时按 slug 回读既有租户返回 201 语义（不泄露 slug/id/status
  之外的目录信息），运维重试不会撞唯一键拿 500。
- **白名单在 DTO 层**：`tier` / `isolation` 用 `@Pattern` 锁死 V10 CHECK 约束的枚举全集，
  非法值在 400 阶段被挡下，不打到数据库触发 500。
- **只创建 tenant 行**：不定义平台管理员、首管理员绑定、`owner_user_id` 归属等运营模型；
  那些是后续产品决策，不是引导第一个租户的必要条件。

**边界（重要）**：该端点是 **out-of-band 运维入口，不经网关、不进 `openapi-java.yaml`**。
网关把 `/api/v1/admin/**` 视作本地控制面前缀（`GatewayRouteContractTest.LOCAL_PREFIXES`），
且免 Bearer 端点被 `PublicPathsContractTest` 限定在 `/api/v1/auth/` 前缀内；把它登记进对外
契约会同时踩中这两条守护。运维直连 user-service 端口调用，令牌由部署侧注入。因此
`PublicPaths` 与网关路由均未改动。

验证：`TenantProvisioningServiceTest`（禁用/错令牌/幂等/归一化）、`TenantAdminControllerTest`
（201/403/校验）、`JdbcTenantRepositoryTest`（真库 `INSERT IGNORE` 返回 1/0）；全量门禁见本批提交。

---

### 5.20 D3：租户级 AI Kill Switch（2026-11-19，决策清单 D3-B 落地）

决策清单 D3 指出：管理面已按 `tenantId` 调用，但底层 Redis 键仍是全局，租户隔离在该处
是假的。本批按 D3-B 保留全局一键全停，新增按租户隔离的键，两者 **OR 叠加**。

**跨语言键约定**（Java 写、Python 读，必须同批发布）：

| 键 | 语义 |
|---|---|
| `agent:kill-switch` | 平台级全局熔断，原样保留 |
| `agent:kill-switch:tenant:{tenantId}` | 单租户开关 |
| `agent:kill-switch:tenant:{tenantId}:meta` | 该租户开关的 `updatedAt` / `updatedBy` |

Java 侧：`KillSwitch` 增 `TENANT_KEY_PREFIX` / `tenantKey` / `tenantMetaKey` /
`isActiveForTenant`（全局 OR 租户，租户键直读以便写入后立即回显）；`AdminControlsStore` 与
`RedisAdminControlsStore` 抽 `readMeta` / `writeMeta` 复用并补租户级读写；
`AdminAiControlsService` 补 `getTenantKillSwitch` / `updateTenantKillSwitch` 与两条租户级审计
事件；`AdminAiControlsController` 增 `GET/PUT /api/v1/admin/ai/controls/tenant`，
`tenantId` 一律取自 `TenantContext.REQUEST_ATTRIBUTE`（已验签 JWT），**不接受请求体传参**。

Python 侧：`RedisKillSwitch.is_active(tenant_id)` 加可选参数，全局键优先、命中即返回，
否则读租户键；缓存改为按**完整 Redis 键**分别存放，全局与租户开关互不污染；
`KillSwitch` Protocol 与 `StaticKillSwitch` 同步签名；唯一执行点
`AgentAskService._prepare` 传入 `request.tenant_id`（`ask` / `ask_stream` 共享该前段，
因此流式路径一并生效）。`is_active()` 无参调用仍表示「只看全局」，向后兼容。

**契约同步**：`openapi-java.yaml` 升 2.6.0，登记 `/api/v1/admin/ai/controls/tenant` 的
GET/PUT、新增 `KillSwitchStateEnvelope`、`UpdateAiControlsRequest` 描述泛化为覆盖两类端点；
`frontend-vue/tests/contract-drift.spec.ts` 同步裁决（schema 计数 98→99、envelope 名单、
admin 参数表加两个新操作）。这一步是 S9-6 对账守护的预期行为：**网关本地新增映射必须
同时登记契约**，否则 `GatewayRouteContractTest` 判为「契约写 200、线上 404」。

验证：Java `KillSwitchTest` / `RedisAdminControlsStoreTest` / `AdminAiControlsServiceTest` /
`AdminAiControlsControllerTest`（4 个租户级用例组），Python `test_kill_switch.py`
（键命名空间、只停自己租户、全局停所有、两端键独立缓存）；全量门禁见本批提交。

**边界**：`isActiveForTenant` 是管理面展示用的 Java 侧视图，真正的拦截执行点在 Python agent；
双端真值集合沿用既有全局键约定（Java 写 `"1"`、Python 认 `1/true/on/yes`），本批未扩展。
D2 / D4 / D5 / D6 按决策清单维持现状或暂缓，未改动。

---

### 5.21 P1-B 收官清理与项目终态（2026-11-19）

至此 §5.2 的 6 条 P0 临时口径全部替换或评估闭环，D1 / D3 按决策清单落地，
D2 / D4 / D5 / D6 为「明确不做」的拍板结论。本批**不新增能力**，只做一次收官核对：
清掉生产代码里仍停留在 P0 口径的残留、复核签发链路，并跑一次不跳过 Java 的完整三端门禁。

| 清理项 | 原状（P0 口径） | 收官处理 |
| --- | --- | --- |
| `JwtService.issue(userId, roles)` | 便利重载静默把用户签进 `DEFAULT_TENANT_ID`；生产零调用、仅测试使用 | **删除重载**，签发统一走三参 `issue(userId, tenantId, roles)`；相关测试改为显式传租户；新增反射守卫测试 `doesNotExposeIssueOverloadWithoutTenantId`，钉死「签发入口必须显式带 tenantId」，防止重载被重新加回 |
| `JwtService` 三参 javadoc | 「P0 阶段全部用户归属默认租户，P1 改由调用方传入」 | 改为 P1 事实：登录 / 注册取 `TenantResolver.resolve`，刷新取 refresh token 家族 tenantId |
| `UserRepository` 类注释 | 「登录 / 注册由调用方显式传 DEFAULT_TENANT_ID（P0 迁移期唯一合法租户）」 | 改为按 tenantSlug 解析 / 家族反查的事实 |
| `AuditEventService.findByTraceId` 注释 | 「P0: MQ 消息尚未携带 tenant_id」 | 改为 §5.11 已记录的有意边界：纯消费者无 HTTP 入口，待暴露查询时随租户上下文收口 |

**核实过、确认不再改动的「默认租户」残留**（避免被误读为遗漏）：

| 位置 | 为何保留 |
| --- | --- |
| `AuditEventService.ingest` / `ReminderTriggerIngestService.ingest` 的 `tenantId == null` 分支 | 部署窗口内兼容不带 tenantId 的在途旧消息并打 WARN，新消息必须携带；§5.11 兼容策略 |
| `AuditEventService.findByTraceId` 的默认租户视图 | 见上表；audit-service 是纯 MQ 消费者，该方法零生产调用，改了等于凭空变更语义 |
| `JdbcTenantBootstrapRepository` / `DefaultTenantInitializer` / `DemoUserSeeder` | 启动播种默认租户与 demo 账号，本就该用固定 ID |
| `TenantResolver` 的 slug 缺省回落 | D2 拍板「维持请求体 slug」，缺省回落以保证 P0 存量客户端兼容 |

**签发链路复核**：登录 / 注册 / 刷新三个入口全部调用三参 `issue`，
`RefreshTokenService` 全链携带 tenantId，不存在「解析出第二租户却签成默认租户」的串扰；
被删重载生产零调用，本次行为零变化。

**项目终态**：本次 SaaS 多租户改造（P0 地基 + P1-B 租户隔离落地）技术边界内的工作全部完成，
无剩余待办。§2 的 P2 计量计费 / P3 身份 / P4 可证删除与 §6 的 4 条属产品路线图与商业 /
合同层事项，不在本次改造范围；D2 / D4 / D5 / D6 是「明确不做」的决策，不是遗漏，
触发条件与启动依据见 `saas-tenant-decisions.md` 各小节。

验证：`scripts/verify-all.sh --offline`（**不跳过 Java**）一次性跑全 `mvn clean verify`
（全模块 + Testcontainers 真库 + checkstyle）、Python pytest / ruff ×2、前端
typecheck / lint / test / build → 全部 PASS、JSON `failed:false`；`git diff --check` → 0。

### 5.22 2.1.0 工程闭环补齐（2026-11-19）

§5.21 的「技术边界内无剩余待办」指的是**已拍板的 P0 地基 + P1-B 改造范围**。随后一次全面
交付审计发现若干「后端已落地但链条未走完 / 工程基建缺失」的项，本批按用户裁决
「D2 / D5 / D6 维持不动、其余按推荐补齐」收口，发 **2.1.0**（新增功能、无 BREAKING）。

**前端消费 D3，`tenantId` 语义已在契约中钉死、无需再拍板。** 2.0.0 落地的
`GET/PUT /api/v1/admin/ai/controls/tenant` 此前只有后端与契约，前端 `admin.ts` 未接。
本批新增两个 API 函数、独立 store `tenantControls.ts`、管理页「本租户 Kill Switch」
（路由 `/admin/tenant-controls`、导航「本租户」）。契约要求 `tenantId` **只取自已验签 JWT**、
需要 ADMIN 角色、不接受请求体或 Header 传参，因此前端不提供选择租户的入口——产品语义
没有留下新的决策空间，界面只需如实呈现。

**最容易撒谎的一处语义：有效状态是 OR 叠加。** Java `AdminAiControlsService.getTenantKillSwitch`
返回 `killSwitch.isActiveForTenant(tenantId)`，即「平台级全局键 OR 本租户键」，而
`updatedAt` / `updatedBy` 只来自**租户键**的 meta。于是：平台级一键全停仍开启时，
运维关闭本租户键后回显依旧是 `active=true`，且元数据为 null。本批的桩、store 与界面
都如实模拟并说明这一形态——页面明确写「本页无法判断当前是哪个键在生效」「关闭本租户键后
若仍显示已开启，说明平台级开关仍开着」「本页无法关闭它」，绝不把该回显写成
「服务已恢复」或「写入失败」。这与 S9 以来「不伪造治理事实」的纪律一致。

**bootstrap 供给的真库验收（按 D4 边界，不引入全链 e2e）。** 此前 D1 端点只有
`TenantAdminControllerTest`（MockMvc standalone）、`TenantProvisioningServiceTest`（fake 仓储）、
`JdbcTenantRepositoryTest`（mock JdbcTemplate 验 SQL 文本）——缺真库语义一层。本批复用的
`MigrationOnRealMysqlTest`（全仓唯一真库基建，已跑 V1→V10、有 `ss_app` 最小权限账号）
新增一个用例，在真实 MySQL 8.4 上断言：`INSERT IGNORE INTO tenant` 同 id 重放返回 0 行、
不同 id 同 slug 被 `uk_tenant_slug` 忽略、换成普通 INSERT 真的抛 `DuplicateKeyException`、
写入形态为 `ACTIVE / owner_user_id=NULL`（不预设平台管理员）。四层断言合成完整验收链，
零新增依赖/容器。

**CI 注释与实现矛盾（PR 检查清单明令禁止项）。** `ci.yml` 头部原写「全部离线、真基础设施
e2e 不属 CI」，但 `mvn verify` 实际会跑 `MigrationOnRealMysqlTest`：GitHub runner 自带 Docker，
`integrationEnabled()` 的 `DockerClientFactory.isDockerAvailable()` 为真，日志可见
`mysql:8.4` 容器启动与 7（本批后 8）个测试真跑。本批把注释改为如实描述该边界，写明
「无需外部预置 MySQL/Redis/RocketMQ/MinIO/PGVector；真库迁移与仓储验收由 Testcontainers
在 Docker 可用时自动执行；`DOCQA_DB_IT=off` 可跳过；全链 HTTP e2e 按 D4 不引入」。
**这一项纠正了此前「CI 不跑真基础设施」的误判。**

**release 自动化。** 2.0.0 的 Release 是手工建的；本批新增 `.github/workflows/release.yml`，
推送 `vX.Y.Z` tag 时从 CHANGELOG 提取对应版本段落自动创建/更新 Release，缺段落则拒绝
发布空 Release，另支持 `workflow_dispatch` 手动补发。

**维持不动的项**：D2 域名路由、D5 Prometheus tenant 标签、D6 OPA ABAC 按决策清单维持或暂缓；
D4 全链 HTTP e2e 不引入；P2 / P3 / P4 与 §6 商业层 4 条仍属路线图与业务拍板。
Branch Protection 因私有仓库免费套餐 403 无法启用，非代码缺口。

> **状态更新（2026-11-19 后续批次）**：仓库已转 **public** 并启用 Branch Protection
> （PR + 三端 CI required checks + `enforce_admins`），上述 403 限制已消解；另补做了
> 本地全套双租户 HTTP 演练（不进 CI、不改产品版本），报告见
> `docs/00-infra/dual-tenant-e2e-drill.md`。本节保留 2.1.0 当时的原始判断，不改写历史叙述。

验证：前端 typecheck / lint / 全量 vitest（新增 24 个用例，含 a11y 播报区对账）、
真库 `MigrationOnRealMysqlTest` 8 tests 全绿（MySQL 8.4 容器真跑）；
最终以 `scripts/verify-all.sh --offline` 全量门禁为准（见本批提交）。

---

## 6. 待确认事项

> 技术/语义层面的遗留决策已逐项拍板，见 `saas-tenant-decisions.md`：**D1（租户供给）与
> D3（租户级 kill switch）已按推荐落地**，记录见 §5.19 / §5.20；D2 短期维持请求体 slug、
> D4 暂不引入 CI 内全链 HTTP e2e（另以一次性本地演练补证据，见
> `docs/00-infra/dual-tenant-e2e-drill.md`）、D5 不加 Prometheus tenant 标签、D6 暂缓 OPA ABAC。
> 技术侧已由 §5.21 收官、§5.22 补齐 2.1.0 工程闭环，无剩余待办；仓库亦已转 public 并启用
> Branch Protection。以下保留原始商业 / 合同层事项，需业务侧拍板，不阻塞本次改造交付。

1. 租户规模与数据量：决定是否真的需要 L2/L3，还是 L1 + 大客户独立库即可。
2. 30 秒删除是合同硬承诺还是内部目标？若硬承诺，第三方 LLM 厂商的 DPA 需重新谈。
3. 计费模型：按席位、按用量，还是混合？
4. 是否已有 IdP（企业微信/飞书/Okta/Azure AD）？
5. P1 何时开始：需要确认租户解析入口（域名路由 / 子域名 / 邀请码）与首个真实租户。
   > 状态更新（2026-11-19）：解析入口已按 D2 定为「短期维持请求体 slug」；首个真实租户
   > 已可由 §5.19 的 bootstrap 端点引导创建。本条剩余待确认的是品牌化域名路由的时点，
   > 不再是 P1 的启动前置。
