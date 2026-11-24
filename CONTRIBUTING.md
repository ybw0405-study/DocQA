# 贡献指南

感谢你愿意为 DocQA 做出贡献。本文说明开发环境、分支与提交约定、代码规范和 PR 流程。
提交任何改动前，请先阅读 [行为准则](CODE_OF_CONDUCT.md) 与 [安全策略](SECURITY.md)。

## 目录

- [1. 开始之前](#1-开始之前)
- [2. 开发环境](#2-开发环境)
- [3. 分支与提交](#3-分支与提交)
- [4. 代码规范](#4-代码规范)
- [5. 测试要求](#5-测试要求)
- [6. 数据库变更](#6-数据库变更)
- [7. 文档要求](#7-文档要求)
- [8. Pull Request 清单](#8-pull-request-清单)
- [9. 安全问题](#9-安全问题)
- [10. 治理现状与评审边界](#10-治理现状与评审边界)

## 1. 开始之前

- 提交前先搜索现有 [Issue](https://github.com/ybw0405-study/DocQA/issues) 与 Pull Request，避免重复工作。
- 缺陷修复、文档修订、小型重构可以直接提 PR；**新增功能、接口变更、数据库变更**请先开 Issue 对齐范围。
- 一个 PR 只做一件事。格式化、重命名与业务改动分开提交。

## 2. 开发环境

完整依赖、端口与环境变量见 [docs/00-infra/getting-started.md](docs/00-infra/getting-started.md)。

| 组件 | 版本要求 |
|---|---|
| JDK | 17 |
| Maven | 3.9+（父 POM 的 enforcer 会拒绝 3.8） |
| Node.js | ≥ 22.22.2 |
| Python | ≥ 3.11（建议 3.13） |
| 中间件 | MySQL 8、PostgreSQL 16 + pgvector、Redis、RocketMQ 5、MinIO |

提交前请在仓库根目录跑一遍离线门禁：

```bash
./scripts/verify-all.sh --offline                 # Git Bash / Linux / CI
powershell -File scripts/verify-all.ps1 -Offline  # Windows PowerShell 5.1
```

门禁会依次执行 Java `mvn clean verify`、Python `pytest + ruff`、前端
`typecheck + lint + test + build`，任一失败即非零退出。

> 本地服务正在运行时，`mvn clean verify` 会因 `target/*.jar` 被占用而失败。
> 先执行 `.\scripts\stop-dev.ps1`，验证后再用 `.\scripts\start-dev.ps1` 重新启动。

## 3. 分支与提交

完整流程见 [docs/00-infra/git-workflow.md](docs/00-infra/git-workflow.md)。

- 从最新 `main` 创建分支：`feature/{issue-id}-{描述}`、`hotfix/{issue-id}-{描述}`、`release/{major.minor.patch}`；编号用 GitHub issue / PR 号。
- 评审机制：个人项目由备用账号 `Y-super000` 履行第二人 review 与批准（模拟双人流程）；分支保护另强制 required status checks、线性历史与禁 force push。
- 分支名使用小写英文与连字符，长度尽量不超过 30 个字符。
- 每日至少 rebase 一次 `origin/main`，合并前确保历史线性。
- 提交信息遵循 [Conventional Commits](https://www.conventionalcommits.org/)：

```text
<type>(<scope>): <subject>

<body：解释为什么这样改，而不是重复代码做了什么>

<footer：BREAKING CHANGE / Refs / Co-authored-by>
```

`type` 白名单：`feat`、`fix`、`docs`、`refactor`、`perf`、`test`、`build`、`ci`、`chore`、`style`、`revert`。

`scope` 使用模块名：`common`、`mq-support`、`user`、`document`、`agent`、`reminder`、`audit`、`infra`、`frontend`。

示例：

```text
feat(document): reject encrypted pdf before parse

PARSING 阶段无法读取加密流，提前在 complete 接口返回明确错误，
避免任务反复重试并在 MQ 中堆积。

Refs: #103
```

## 4. 代码规范

| 端 | 工具 | 要求 |
|---|---|---|
| Java | Checkstyle（绑定 `verify`）、ArchUnit | 0 violation；不得绕过模块依赖方向 |
| Python | Ruff（`ruff check` + `ruff format --check`） | 行宽 120，目标 Python 3.11 |
| 前端 | ESLint + `vue-tsc --noEmit` | 0 error；不得使用内联脚本与 `v-html` 渲染用户内容 |
| 文档 | Markdown | 相对链接可点击，命令可直接复制执行 |

Java 分层与依赖边界见 [docs/00-infra/maven-modules.md](docs/00-infra/maven-modules.md) 和
[docs/00-infra/archunit-rules.md](docs/00-infra/archunit-rules.md)。

## 5. 测试要求

- 新增业务分支必须有单元测试；跨进程/跨中间件行为必须有集成测试。
- 数据库相关测试使用 Testcontainers 启动真实 MySQL / PostgreSQL，不允许只用 H2 或 `ddl-auto=update`。
- 修复缺陷时先补一个能复现问题的测试，再修实现。
- 不允许为了让测试通过而缩小断言范围、删除断言或跳过用例；确需跳过时在 PR 中说明原因。

## 6. 数据库变更

- Flyway 迁移脚本与依赖它的代码**必须在同一个分支、同一个 PR**。
- 版本号格式：`V{yyyyMMddHHmm}__{lower_snake_case_description}.sql`。
- 已合入 `main` 或已应用到任何共享环境的脚本不可修改，只能新增补偿脚本。
- 大表变更拆成「加可空列 → 回填 → 加约束」多步，并评估锁表时间。
- 回滚方案写在 PR 描述中；数据库变更不会随应用回滚自动撤销。

## 7. 文档要求

- 接口变更同步更新 [docs/02-design/openapi-java.yaml](docs/02-design/openapi-java.yaml) 或
  [docs/02-design/cross-language-protocol.md](docs/02-design/cross-language-protocol.md)。
- 表结构变更同步更新 [docs/02-design/storage.md](docs/02-design/storage.md)。
- 用户可见行为变更同步更新 `README.md`、相关 `docs/` 与 [CHANGELOG.md](CHANGELOG.md)。
- 文档与实现冲突时，以「实现 + 测试」为准并在同一个 PR 中修正文档。

## 8. Pull Request 清单

提交 PR 前逐项确认：

- [ ] 标题符合 Conventional Commits，描述包含背景、改动范围、验证方式与风险。
- [ ] 已 rebase 到最新 `origin/main`，PR 只包含一个意图。
- [ ] `scripts/verify-all` 全绿（或在 CI 中通过）。
- [ ] 新增/修改的测试覆盖了行为变化。
- [ ] 涉及接口、数据库、跨语言契约时，文档与迁移脚本已同步。
- [ ] 没有硬编码密钥、Token、连接串、PII 或完整 Prompt 进入代码、测试快照与日志。
- [ ] 没有提交 `target/`、`node_modules/`、`.env`、IDE 本地配置、密钥文件。
- [ ] 破坏性变更使用 `feat!:` / `fix!:` 并在 footer 写明迁移方式。

合并方式使用 **Squash Merge**；合并后删除源分支。

## 9. 安全问题

不要在公开 Issue 或 PR 中披露安全漏洞。请按 [SECURITY.md](SECURITY.md) 的流程私下报告。

## 10. 治理现状与评审边界

本节只写**可被 `gh api` 或 workflow 文件反查证实的现状**，不写无法证实的「已启用 / 强制」断言。

### 分支保护：已启用，但审批门槛偏弱

- `main` 的 Branch Protection **已启用**（`strict=true`、3 个 required status checks、
  `enforce_admins=true`、禁 force push / 删除、线性历史）—— 这部分属实。
- 但 `required_approving_review_count: 0`、`require_code_owner_reviews: false`：
  **0 审批即可合并**，`.github/CODEOWNERS` 当前无强制力。
- **本轮（2026-11-21）按授权启用了其余远端配置，但刻意未改这两项**：单人仓库设
  `required_approving_review_count=1` 后，PR 作者不能批准自己的 PR，会直接卡死合并；
  需要第二个 GitHub 账号才能安全启用。命令保留在下方备查。
- 补强审批门槛（G3，需仓库管理员在远端执行，且需第二个账号）：

```bash
gh api -X PATCH repos/ybw0405-study/DocQA/branches/main/protection/required_pull_request_reviews \
  -H "Accept: application/vnd.github+json" --input - <<'JSON'
{
  "dismiss_stale_reviews": true,
  "require_code_owner_reviews": true,
  "required_approving_review_count": 1
}
JSON
```

> 若该子资源返回 404，改用 `gh api -X PUT repos/ybw0405-study/DocQA/branches/main/protection`
> 提交完整 body（含现有 `required_status_checks` 等，勿丢）。`require_code_owner_reviews: true`
> 依赖 `.github/CODEOWNERS`（已存在，路径经 `git ls-files` 核实）。

### 安全能力：远端开关已启用，工作流仍待合并

- 仓库级安全开关**已启用 3 项**：`dependabot_security_updates`（原本已开）/ `secret_scanning` /
  `secret_scanning_push_protection`（2026-11-21 由 `gh api -X PATCH` 启用，
  可用 `gh api repos/ybw0405-study/DocQA --jq .security_and_analysis` 反查）。
- `secret_scanning_non_provider_patterns` 与 `secret_scanning_validity_checks` 经 API 提交后
  仍回 `disabled`，需到 Settings → Code security and analysis 网页确认是否受账户计划限制。
- 本轮新增 `.github/workflows/security.yml`（CodeQL `java-kotlin` / `javascript-typescript` /
  `python`、`dependency-review-action`、`npm audit --omit=dev --audit-level=high`，G9）；
  **合并推送到 `main` 后**才会在 PR 与 push 上运行，本轮本地改动本身不构成「已启用」。
- PR #26 已打 `do-not-merge` 标签并附发布顺序说明（runbook 见 `修复执行报告.md` 第 5 节）。

### 门禁与评审边界：单人仓库无法做到「第二人复核」

- 第 2 节的 `scripts/verify-all` 是**本地手动脚本**，不在 GitHub 侧强制；
  第 8 节「Pull Request 清单」是**人工自查**，在审批门槛补强前没有「第二人复核」的机器强制力。
- **本仓库由单人维护，物理上不存在第二个账号来做独立复核。** `required_approving_review_count: 1`
  + `require_code_owner_reviews: true` 即便配好，对单人仓库也会变成「自己审自己」或无人可审卡死，
  不能假装做到了第二人复核。替代方案：自审 PR + 强制检查清单（验证方式 / 回滚方式 /
  `git diff --check` 输出）+ 延迟合并 + 依赖三端 CI 与 `security` 工作流全绿作为客观门禁。
