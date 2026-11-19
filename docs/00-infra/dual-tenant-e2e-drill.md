# 本地双租户 HTTP 演练报告

> 执行日期：2026-11-19 · 状态：**已完成，脚本可复现（22 步断言全绿）**
> 复现脚本：[`scripts/e2e-dual-tenant-drill.sh`](../../scripts/e2e-dual-tenant-drill.sh)
> 关联决策：[`saas-tenant-decisions.md`](../02-design/saas-tenant-decisions.md) D4

## 0. 边界声明（先读这一段）

这份报告记录的是**在真实启动的全套服务上，用真实 HTTP 做的双租户演练**。它不是生产验证，也不改变此前的拍板结论：

- **不是生产环境验证**。所有服务跑在本机 Docker Desktop + Windows 原生 JVM，租户由运维端点临时供给，没有真实部署、真实用户、真实云环境。因此「无真实多租户生产验证」这一事实**仍然存在**。
- **不进 CI**。D4 的「CI 内全链 HTTP e2e 暂不引入」维持不变；本演练是人工触发的本地档，不增加 CI 时长，不引入测试框架依赖。
- **不改产品版本**。产品版本仍为 `2.1.0`，OpenAPI 契约仍为 `2.6.0`，不打 tag、不发 Release。
- **ADMIN 演练身份的由来**：注册账号默认只有 `USER` 角色，而管理面需要 `ADMIN`。演练用本地 `DOCQA_JWT_SECRET` 签发了两个租户各一个 ADMIN JWT。请求经过的是**真实的验签链路**（网关 `JwtAuthenticator` 校验签名 / issuer / audience / jti / `tenant_id`），没有伪造响应、没有绕过服务端校验；但这不是生产登录路径，报告不把它表述为「真实管理员登录」。
- **RAG 解析链路未覆盖**：Parse / Vector Worker 依赖 Linux 的 `librocketmq.so`，Windows 侧 `import rocketmq` 即失败，文档无法从 `UPLOADED` 推进到 `INDEXED`。因此问答在网关侧被 `AGT-VALIDATION-002`（文档未索引）挡下，未走完「检索 → 生成 → 引用」。这一层边界如实保留。

## 1. 环境

| 组件 | 实况 |
|---|---|
| Docker | Desktop 29.8.0，容器 `mysql:8.4` / `pgvector:pg16` / `redis:7` / MinIO / RocketMQ 5.3.1 |
| Java | 5 个服务 `2.1.0` jar：`user-service :18081`、`document-service :18082`、`reminder-service :18083`、`agent-gateway :18080`、`audit-service`（无 Web 端口） |
| Python | `uvicorn docqa_ai.api.main:app --port 18000`，`/health` 200 |
| 前端 | Vite `:5173`，代理统一指向网关 `18080` |
| 配置 | `ops/env/local.env`（gitignored），含 `DOCQA_TENANT_BOOTSTRAP_TOKEN` 与 `DOCQA_JWT_SECRET` |

启动：`scripts/start-dev.ps1 -NoBuild` 起中间件与 5 个 Java 服务；Python 与 Vite 单独启动。

## 2. 复现

```bash
# 前置：Docker Desktop 已运行，local.env 已填好且设置了 bootstrap token
# 起全套服务后，从仓库根目录：
bash scripts/e2e-dual-tenant-drill.sh
```

脚本用时间戳后缀生成租户 slug 与用户名，可重复运行；响应证据写入 `.artifacts/dual-tenant-evidence/`（gitignored），任何一步状态码或断言不符即以非零码退出。注册端点有「同 IP 5 次 / 120 秒」限流，连续重跑请间隔 2 分钟。

## 3. 证据矩阵

| # | 请求 | 期望 | 实测 | 结论 |
|---|---|---|---|---|
| 1 | `POST /admin/tenants` 无 token | 403 | 403 `USR-FORBIDDEN-001` | 供给端点默认安全，不存在「部署即开放」窗口 |
| 2 | 同上，错误 token | 403 | 403 | 常量时间比较生效 |
| 3 | 同上，正确 token | 201 | 201，返回 `id / slug / ACTIVE` | 第二租户真的落库 |
| 4 | 同 slug 重放 | 201 且同 id | 201 且同 id | `INSERT IGNORE` 幂等，运维重试不会 500 |
| 5–6 | 两租户分别注册 | 201 | 201 | `tenantSlug` 决定账号归属 |
| 7 | 解码两个 JWT | `tenant_id` 不同 | `11111111-…-1111` vs bootstrap 返回的 id | JWT claim 与租户目录一致，不是默认租户兜底 |
| 8 | A 上传文档 `upload/init` | 201 | 201 | 预签名 URL 路径含 `documents/{tenantId}/{userId}/{documentId}`，**对象键租户化**在真实响应里可见 |
| 9 | A 查自己 `status` | 200 | 200 | 属主可读 |
| 10 | B 查 A 的 `documentId/status` | 404 | 404 `DOC-NOT_FOUND-001` | 跨租户不泄露「文档存在」 |
| 11–12 | A / B 文档列表 | A 含该文档、B 为空 | 符合 | 列表谓词带租户过滤 |
| 13 | B 用**相同 md5** 上传 | 201 且不同 id | 201 且不同 id | 同内容不跨租户串号；`(user_id, md5)` 去重不越界 |
| 14 | A 打开本租户熔断 | 200 `active=true` | 200 | D3 租户键写入生效 |
| 15 | B 读本租户熔断 | `active=false` | `false` | **租户级状态互不影响**，OR 判读按租户键隔离 |
| 16–17 | A / B 用户列表 | 互不可见 | A 列表无 B 用户，B 列表无 A 用户 | 管理面也有租户谓词 |
| 18 | `/agent/ask` 缺 `tenantId` | 422 | 422 `AGT-UPSTREAM-004`，`errors: tenantId Field required` | 跨语言契约必填字段在运行时被强制 |
| 19 | `/agent/ask` 多一个字段 | 422 | 422 `Extra inputs are not permitted` | `extra="forbid"` 确实会拒绝字段漂移 |
| 20 | `/agent/ask` 合法请求（无向量数据） | 503 | 503 `AGT-UPSTREAM-001` | **fail-closed**，不静默放行 |
| 21 | 关闭 A 的租户熔断 | 200 | 200 `active=false` | 环境复位 |

另有一组界面截图（真实登录态，非静态 mock）：[登录](demo/login.png) · [首页](demo/home.png) · [文档列表](demo/documents.png) · [问答](demo/ask.png) · [本租户 Kill Switch](demo/tenant-controls.png)。文档列表页显示 `tenant-a-handbook.pdf / 2.0 KiB / 已上传 / 索引版本 v1`；管理页显示有效状态、最近写入时刻与写入者。截图由 headless Chrome 经 CDP 真实登录并逐页导航生成。

## 4. 关键结论

1. **租户解析 → JWT → 业务谓词**这条链在真实 HTTP 上是闭合的：bootstrap 供给的 id 原样进入 JWT `tenant_id`，再原样成为文档 / 用户查询的过滤条件。
2. **跨租户访问一律 404**，不是 403，不泄露资源是否存在；同 md5 的文档在两个租户下是两条独立记录。
3. **MinIO 对象键租户化**不是纸面设计：上传响应里的预签名 URL 直接带 `tenantId` 路径段。
4. **D3 租户级熔断在真实 Redis 键上按租户隔离**：A 打开后 B 读回仍是 `false`，全局键未被误写。
5. **跨语言契约漂移防护有运行时证据**：缺字段、多字段都在 Python 入口被 422 拒绝，合法请求在无上游数据时 503 fail-closed。
6. **运维供给链路安全且幂等**：无 token / 错 token 403，重放同 id。

## 5. 演练中发现的非阻断问题（如实记录）

- `GET /api/v1/documents/{documentId}` 返回 **500** 而非 405。契约里该路径**只登记 PATCH 与 DELETE，没有 GET**（详情查询应走 `/{id}/status`，该端点已验证 200/404）；Spring 抛 `HttpRequestMethodNotSupportedException` 是正确行为，但 `GlobalExceptionHandler` 的通用兜底把它映射成了 500 `COM-INTERNAL-001`，语义上更合适的是 405。这是**异常映射瑕疵，不是隔离缺陷，也不是契约漂移**——A 查自己的文档同样 500。本演练未修改它（超出本次范围），如需修正应在 `GlobalExceptionHandler` 增加该异常类型的显式映射并补契约说明。
- 演练在本地库留下了租户、用户、文档、角色变更与熔断写入记录（熔断已复位为关闭）。这些是预期副作用，清库或重建容器即可清除。
- 问答链路的 `AGT-VALIDATION-002` 与 Python 侧 `AGT-UPSTREAM-001` 都是**正确的 fail-closed 行为**，不是故障；但也因此没有产生「带引用的问答结果」，RAG 全链仍需 Linux worker 环境。

## 6. 与 D4 决策的关系

D4 原本拍板 A 档「暂不引入全链 e2e」，理由是现有三层守护（契约测试 / 真库仓储验收 / 单测）已覆盖最易静默失效的点，全链维护成本高、CI 慢。本次演练**没有推翻这个结论**：它以一次性本地脚本的形式补上了「HTTP 级、跨进程、双租户」的证据与演示素材，但不进入 CI、不增加维护面。需要长期守护时，再按 D4 的 B 档（单服务 `SpringBootTest(RANDOM_PORT)`）评估。
