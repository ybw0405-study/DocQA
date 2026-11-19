# SaaS 多租户改造：决策清单（已拍板并执行）

> 状态：**已拍板并按推荐执行（2026-11-19）**——D1 / D3 已落地，D2 / D4 / D5 / D6 按推荐维持或暂缓。
> 适用分支：`feat/saas-tenant-foundation`
> 背景设计：`saas-kernel-upgrade.md`（§5.9～§5.21 为已落地记录，§6 为原始待确认事项）

本清单只收录**需要产品/语义取舍**的遗留项。纯技术边界内可自主推进的部分已完成（见
`saas-kernel-upgrade.md` §5.13～§5.18：协议租户上下文、MinIO 对象键、MDC 日志标签、
pgvector 评估、跨语言契约、真库仓储验收）。拍板结论与落地记录如下。

## 决策项一览

| 编号 | 决策项 | 推荐 | 依赖 | 推荐优先级 | 拍板结论 |
| --- | --- | --- | --- | --- | --- |
| D1 | 租户供给入口与权限模型 | 配置保护的 bootstrap 端点，暂不引入平台管理员角色 | 无（其余多项的前置） | 1 | **已落地**，见 §5.19 |
| D2 | 租户解析入口（域名路由） | 短期维持请求体 slug；品牌化时走边缘网关 Header 注入 | D1 | 3 | 维持请求体 slug，暂不改动 |
| D3 | Redis kill switch 语义 | 保留全局熔断 + 新增租户级开关（OR 叠加） | 无 | 2 | **已落地**，见 §5.20 |
| D4 | HTTP 级端到端验收 | 暂不引入全链 e2e；需要时做单服务 e2e | D1 | 4 | 暂不引入 |
| D5 | Prometheus tenant 标签 | 不加，租户级统计走已有计量→账单链路 | 无 | 5 | 不加 |
| D6 | OPA ABAC | 暂缓，待角色模型复杂化再评估 | 无 | 6 | 暂缓 |

D1 是 D2 / D4 的前置；D3 / D5 / D6 相互独立，可任意顺序插入。

### 本批落地与维持记录（2026-11-19）

- **D1-B 已实现**：`TenantBootstrapProperties` + `TenantAdminController` +
  `TenantProvisioningService` + `TenantRepository.insert`，共享令牌默认空即禁用、
  `MessageDigest.isEqual` 常量时间比较、`INSERT IGNORE` 幂等。刻意不引入平台管理员角色，
  不定义首管理员运营模型。端点为 out-of-band 运维入口，不经网关、不进对外契约（详见 §5.19）。
- **D3-B 已实现**：Java `KillSwitch.tenantKey` / `isActiveForTenant` /
  `AdminControlsStore` 租户级读写 / `AdminAiControlsController` 的 `/tenant` GET-PUT，
  Python `RedisKillSwitch.is_active(tenant_id)` 与 `AgentAskService._prepare` 唯一执行点；
  全局与租户键 OR 叠加、按完整 Redis 键独立缓存。`openapi-java.yaml` 升 2.6.0 登记新路径
  与 `KillSwitchStateEnvelope`，Java + Python **同批发布**（详见 §5.20）。
- **D2 / D4 / D5 / D6 维持推荐结论**：不加域名路由、不引入全链 e2e、不加指标 tenant 标签、
  不引入 OPA。这四项是「明确不做」的决策，不是遗漏；触发条件与影响仍以各自小节所述为准。

---

## D1 — 租户供给入口与权限模型

**现状事实（已核实代码）**
- `tenant` 表已存在（`V10__tenant_foundation.sql`），`TenantResolver.resolve(slug)` 已支持按
  slug 解析第二租户，仅允许 `ACTIVE` / `TRIAL` 状态登录。
- **没有任何创建租户的 HTTP 端点或 CLI**，这是"无法做真实第二租户验收"的根因。
- 角色模型只有租户内 `USER` / `ADMIN`（`UserRole` 枚举、`AuthenticatedUser.ADMIN`；
  网关/用户服务据此做管理面守卫）。全局搜索无 `PLATFORM_ADMIN` / `SUPER_ADMIN` /
  `isPlatform` 等平台级身份概念，JWT 也没有平台级 scope claim。
- 即：当前代码里"管理员"默认是**租户内管理员**，跨租户创建租户在语义上是越权。

**要决策的问题**
1. 谁有权创建新租户？
2. 创建入口是什么形态（HTTP / CLI / 运维 seed）？

**选项与推荐**
- A. 新增 `PLATFORM_ADMIN` 角色 + 受其保护的 HTTP 端点：语义最完整，但要扩展角色枚举、
  JWT claim 与三服务的守卫逻辑，改动面最大。
- **B.（推荐）配置保护的 bootstrap 端点**：新增一个由环境变量（如
  `DOCQA_TENANT_BOOTSTRAP_TOKEN`）启用、用独立共享令牌保护的创建端点，不引入平台角色、
  不改 JWT claim，改动集中在 user-service。它同时满足 §6 第 5 条"首个真实租户"的引导需求。
- C. 仅靠 migration seed：最简单，但无法在运行期供给租户，不适合验收与运营。

**影响 / 前置**：推荐 B 只新增一个受保护端点和一个配置项；后续若出现多租户运营需求，
可平滑演进到 A（bootstrap 令牌签发的首个管理员升级为 `PLATFORM_ADMIN`）。

---

## D2 — 租户解析入口（域名路由）

**现状事实**
- `tenantSlug` 只能从 `/auth/login`、`/auth/register` 的**请求体**传入（`LoginRequest` /
  `RegisterRequest`），缺省回落默认租户。
- 无域名/子域名解析，无网关 Header 注入；一旦 JWT 签发，后续租户身份一律取
  `tenant_id` claim，`TenantResolver` 明确禁止 Header / Body 再参与授权判断。

**要决策的问题**：租户身份如何在"尚无 JWT 的登录入口"确定？

**选项与推荐**
- **A.（推荐，短期）维持请求体 slug**：已实现、兼容存量客户端、无伪造面。
- B. 边缘网关注入 `X-Tenant-Slug`：前端无需改登录表单，但必须由网关**剥离客户端自报的
  同名 Header**，且仅在无 JWT 的登录入口接受。
- C. 子域名 `acme.docqa.com`：品牌化好，但存在保留字冲突与证书/网关配置复杂度。
- D. 自定义域名 → slug 映射表：最灵活，需新增映射表与解析链路。

**影响 / 前置**：若有品牌化诉求，推荐走 D（网关按域名查映射后注入 slug），
不要在应用层用子域名正则硬解。依赖 D1 提供租户供给能力。

---

## D3 — Redis kill switch 语义

**现状事实**
- `agent:kill-switch` 是**平台级全局熔断**（`AdminAiControlsService` 注释明确），
  一租户关停会影响全部租户。
- 上一批管理面已按 `tenantId` 调用 kill switch / 预算，但底层 Redis 键仍全局，
  即"租户隔离"在该处目前是假的。
- Python 侧 `kill_switch.is_active()` 同样读全局键；双端需同批发布。

**要决策的问题**：是否需要租户级 kill switch？全局开关是否保留？

**选项与推荐**
- A. 保持纯全局：不做，接受"关停即全停"。
- **B.（推荐）保留全局熔断 + 新增租户级独立键，两者 OR 叠加**：平台级用于事故一键兜底，
  租户级用于单租户治理，互不破坏。
- C. 把全局键直接改造为按租户：破坏"一键全停"语义，不推荐。

**影响 / 前置**：B 需要 Java 管理端点 + Python 消费端**同批发布**（跨语言字段/键约定），
是本次清单里唯一涉及破坏性发布的项。

---

## D4 — HTTP 级端到端验收

**现状事实**
- 已有三层守护：跨语言契约测试（问答协议 + 6 个 MQ 消息）、真库仓储级验收
  （`MigrationOnRealMysqlTest`，Testcontainers MySQL 8.4）、各服务单测。
- 三个服务各自验签，**没有统一的 e2e 编排**；无跨进程 HTTP 全链测试。

**要决策的问题**：是否值得引入全链 e2e 测试服务？

**选项与推荐**
- **A.（推荐）暂不引入**：现有三层已覆盖最易静默失效的点（谓词语义、字段漂移），
  全链 e2e 维护成本高、CI 慢，收益递减。
- B. 单服务 e2e：user-service 内"登录签发 → 带 JWT 调管理端点"，复用真库，成本可控；
  若确需 HTTP 级证据，推荐此档。
- C. 全链路 e2e（三服务 + 真库 + MQ）：最完整但最重，暂不考虑。

**影响 / 前置**：依赖 D1 先能供给第二租户。

---

## D5 — Prometheus tenant 标签

**现状事实**
- 日志侧已做：`TenantContext` 镜像 `tenantId` 到 SLF4J MDC，三个服务日志模式渲染
  `traceId` / `tenantId`（§5.15）。
- 指标侧**未加** tenant 标签；设计文档 §3 已注明 cardinality 需先评估租户规模。

**要决策的问题**：是否给 Prometheus 指标加 tenant 标签？

**选项与推荐**
- **A.（推荐）不加**：租户级用量统计走已有"计量 → 聚合 → 账单"链路（DB 维度），
  避免高基数污染 TSDB；可观测的 tenant 定位由日志 MDC 承担。
- B. 仅白名单大客户加标签：需设租户数上限与告警，控制基数。
- C. 全量加：cardinality 爆炸，不推荐。

**影响 / 前置**：无代码前置；若选 B，需先定"白名单来源"（配置项 or DB 标记）。

---

## D6 — OPA ABAC

**现状事实**
- 角色硬编码为 `USER` / `ADMIN`，权限判断散落在控制器/服务内的 `isAdmin()` /
  `hasRole(ADMIN)`；无策略引擎，无运行期策略变更能力。
- 设计文档 §1.2 将 OPA 列为规划能力，尚未落地。

**要决策的问题**：是否引入 OPA？何时？判决点放在哪？

**选项与推荐**
- **A.（推荐）暂缓**：当前仅 2 类角色、无资源级细粒度权限，引入 OPA 的运维与架构成本
  高于收益。建议设置触发条件后再评估。
- B. 引入 OPA：需先定义角色清单、策略语言（Rego）、判决点（PEP）位置与策略分发机制。

**影响 / 前置**：建议触发条件——出现 ≥3 类角色、资源级权限、或运行期策略变更需求中的
任意一项时再启动。属架构级决策，独立于上述各项。

---

## 原始商业/合同层事项（非本清单技术决策）

以下为 `saas-kernel-upgrade.md` §6 原有条目，需业务侧拍板，不阻塞上述技术实现：

1. 租户规模与数据量：决定是否真的需要 L2/L3，还是 L1 + 大客户独立库即可。
2. 30 秒删除是合同硬承诺还是内部目标？若硬承诺，第三方 LLM 厂商 DPA 需重谈。
3. 计费模型：按席位、按用量，还是混合？
4. 是否已有 IdP（企业微信/飞书/Okta/Azure AD）？

---

## 执行结论

按表中"推荐"执行，优先顺序 D1 → D3 → D2 → D4 → D5 → D6：

- **D1-B / D3-B 已完成并提交**（§5.19 / §5.20），全量门禁全绿（Java test + checkstyle、
  Python pytest / ruff、前端 typecheck / lint / test / build）。
- **D2 / D4 / D5 / D6 按推荐维持或暂缓**，未产生代码改动。
- **P1-B 已收官**（§5.21）：删除 P0 遗留的默认租户签发便利重载并补反射守卫、清理 3 处
  过时 P0 注释、复核登录 / 注册 / 刷新签发链路无租户串扰；不跳过 Java 的完整三端门禁全绿。
  技术边界内无剩余待办，本次改造交付完成。

若后续要重新打开 D2 / D4 / D5 / D6，各小节的"影响 / 前置"与触发条件即为启动依据；
其中 D4-B（user-service 单服务 HTTP e2e）与 D2 的域名映射均已在 D1 提供租户供给能力后
具备前置条件，可随时按产品优先级插入。
