# ops/sql

数据库侧的最小权限收敛与取证。这一组文件只改**授权**，不改表结构、不写业务数据。

## 为什么需要它

应用默认以 `root@localhost` 连库，`SHOW GRANTS` 含 `UPDATE` / `DELETE`。
「审计只追加」若只由「代码里没写 UPDATE/DELETE」保证——一次注入、一次救火式
`GRANT ALL`、一次手工改行，历史就没了，而且症状不会出现在任何日志里。

这里把同一件事换成授权层事实：`audit_event` 对运行时账号只有 `SELECT` / `INSERT`，
`UPDATE` / `DELETE` 在数据库里**不存在**。

## 三个账号

| 账号 | 能力 | 谁用 |
|---|---|---|
| `ss_migrator` | schema 级 DDL（`CREATE/ALTER/DROP/INDEX/REFERENCES/CREATE VIEW/CREATE ROUTINE/ALTER ROUTINE/EXECUTE/TRIGGER`）+ `flyway_schema_history` 的 DML | `document-service` 的 Flyway（`spring.flyway.user/password`） |
| `ss_app` | 14 张业务表的 `SELECT/INSERT/UPDATE/DELETE`；`audit_event` 只有 `SELECT/INSERT`；`tenant` 只有 `SELECT/INSERT/UPDATE`（注销是软删，不给 `DELETE`） | user / document / reminder / agent-gateway |
| `ss_audit` | 只有 `audit_event` 的 `SELECT/INSERT` | audit-service |

`ss_migrator` 故意**不拿业务表 DML**：现有 V1–V10 迁移是纯 DDL（0 条 `INSERT/UPDATE/DELETE`），
所以「迁移不需要写业务数据」这条约束今天成立。将来要回填数据，另开一次性 DML 授权，
不要把 DML 常驻给 migrator。

这条约束由 `MigrationV10ContractTest.migrationStaysPureDdl` 强制：迁移文件里只要出现
`INSERT` / `UPDATE` / `DELETE` / `REPLACE` 开头的语句就红。V10 初版曾在迁移中间种一行
默认租户，本机 MySQL 8.4 实测报 `INSERT command denied to user 'ss_migrator'`，
且 DDL 不参与事务，迁移停在「表已建、其余未跑」的半迁移状态——种子数据现在由
`document-service` 启动期的 `DefaultTenantInitializer` 用 app 账号幂等写入。

## 怎么用

```powershell
# 1) 先看会执行什么（只读现网、渲染、脱敏输出，不建号）
pwsh -File ops/sql/apply-least-privilege.ps1 -DryRun

# 2) 真正执行：建号 + 授到目标态 + 对账撤多余 + 口令写入用户级环境变量
pwsh -File ops/sql/apply-least-privilege.ps1

# 3) 行为级取证：让每个账号自己去执行动作，看数据库拦不拦
pwsh -File ops/sql/verify-least-privilege.ps1

# 4) 重启服务，然后打开强校验（违规即拒绝启动）
pwsh -File scripts/stop-dev.ps1
setx DOCQA_AUDIT_APPEND_ONLY_GUARD enforce
pwsh -File scripts/start-dev.ps1
```

需要管理员凭据 `DOCQA_DB_USERNAME` / `DOCQA_DB_PASSWORD`（默认 root）。

## 环境变量阶梯

每个变量都向左回退，所以**不配任何新变量时行为与改造前完全一致**：

```
DOCQA_AUDIT_DB_*    → DOCQA_APP_DB_*    → DOCQA_DB_*（root）
DOCQA_MIGRATOR_DB_* → DOCQA_DB_*（root）
```

想退回 root：清空那六个 `DOCQA_*_DB_*` 变量即可。

## 两条操作纪律

1. **先撤后授**。`apply` 的顺序是「对账撤多余 → 建号授权」。中途失败时账号落在
   **权限不足**一侧（服务立刻报错），而不是**权限过多**一侧（静默越过红线）。
2. **新增表必须同步授权**。逐表授权的代价就在这里：`least-privilege.sql.tmpl` 与
   `$businessTables` 都要加。`verify-least-privilege.ps1` 会用「表清单 vs 已授权表」
   的差集报 `GRANT_MISSING`，把「忘了加」变成一次明确的 FAIL。

## 已知不覆盖

- 只按 `'ss_*'@'localhost'` 建号（MySQL 侧 `skip_name_resolve=0`，`127.0.0.1` 会被解析成 `localhost`）。
  跨机部署要 `-AccountHost '%'`，并自行评估该主机段的含义。
- 不设 `FAILED_LOGIN_ATTEMPTS` / `PASSWORD_LOCK_TIME`：本机反复重配会把操作者锁在库外，
  且锁号与口令错、网络不通同形。
- `ss_migrator` 仍持有 `DROP/ALTER`：它只是**不在运行期加载**，不是不可怕。
  想更严，需要把迁移从启动路径里彻底拆出去（独立的 migrate 步骤），属部署形态变更。
- 口令经 MySQL 客户端 `MYSQL_PWD` 传递，本机回环明文。生产需 TLS（`REQUIRE SSL`），本轮未做。
