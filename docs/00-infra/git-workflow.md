# Git 分支、提交与合并流程

## 1. 分支模型

采用"短生命周期 feature 分支 + 受保护的 trunk"模式。`main` 永远可发布，禁止直接向 `main` 推送。

| 分支 | 命名 | 用途 | 生命周期 |
|---|---|---|---|
| 主干 | `main` | 唯一集成分支，受保护 | 永久 |
| 功能 | `feature/REQ-{ID}-{描述}` | 单个需求或技术任务的开发 | 建议不超过 3 天 |
| 修复 | `hotfix/REQ-{ID}-{描述}` | 线上紧急缺陷，仍须走 MR | 上线后立即删除 |
| 发布 | `release/{major.minor.patch}` | 仅用于需要冻结的发布准备 | 发布后合并并删除 |

功能分支命名示例：

```text
feature/REQ-101-document-upload
feature/REQ-107-rag-ask-contract
feature/REQ-112-rrule-reminder
```

规则：

- `{ID}` 使用需求或缺陷编号，不允许无编号的 `feature/test`、`feature/dev`。
- `{描述}` 使用小写英文与连字符，长度尽量不超过 30 个字符。
- 从最新 `main` 创建；每日至少 rebase 一次，避免最后一天集中解冲突。
- 一个分支只服务一个 REQ，禁止"顺手重构整个项目"；跨需求的改动拆分支。
- 合并后删除远端分支，保持分支列表可审计。

推荐命令：

```bash
git switch main
git pull --ff-only
git switch -c feature/REQ-101-document-upload
# 开发与本地自检
git rebase origin/main
git push -u origin feature/REQ-101-document-upload
```

## 2. Conventional Commits 规范

提交信息格式：

```text
<type>(<scope>): <subject>

<body：解释为什么这样改，而不是重复代码做了什么>

<footer：BREAKING CHANGE / Refs / Co-authored-by>
```

`type` 白名单：

| type | 含义 | 示例 |
|---|---|---|
| `feat` | 新增用户可见功能或接口 | `feat(document): add md5 instant upload` |
| `fix` | 修复缺陷 | `fix(agent): rollback budget on python timeout` |
| `docs` | 文档、注释、契约说明 | `docs(infra): add ci pipeline gate` |
| `refactor` | 不改变外部行为的重构 | `refactor(document): extract upload state machine` |
| `perf` | 性能优化 | `perf(agent): cache embedding query plan` |
| `test` | 新增或修改测试 | `test(audit): add append-only integration test` |
| `build` | 构建系统、依赖、插件 | `build(java): pin spring boot version` |
| `ci` | CI/CD 配置 | `ci: split archunit into dedicated step` |
| `chore` | 杂项维护，不影响产品行为 | `chore: update gitignore` |
| `style` | 仅格式调整，不改变逻辑 | `style(common): format error code registry` |
| `revert` | 回滚提交 | `revert: feat(document): add md5 instant upload` |

约束：

- `scope` 使用模块名：`common`、`user`、`document`、`agent`、`reminder`、`audit`、`infra`、`frontend`。
- `subject` 使用祈使句、描述结果，不超过 72 个字符，不使用"update code""fix bug"这类无信息描述。
- 一个提交只表达一个意图；格式化与业务改动分开提交。
- 破坏性变更使用 `feat!:` 或 `fix!:`，并在 footer 写 `BREAKING CHANGE:` 及迁移方式。
- 关联需求或缺陷使用 footer：`Refs: REQ-101`。

示例：

```text
feat(document): reject encrypted pdf before parse

PARSING 阶段无法读取加密流，提前在 complete 接口返回明确错误，
避免任务反复重试并在 MQ 中堆积。

Refs: REQ-103
```

## 3. MR 合并前必过检查项

所有 MR 必须满足以下条件，缺一项不得合并：

- [ ] 标题符合 Conventional Commits；描述包含背景、改动范围、验证方式与风险。
- [ ] 目标分支为 `main`，分支已 rebase 到最新 `main`。
- [ ] GitHub Actions 四项检查全部通过：`Maven Compile`、`Maven Test`、`Checkstyle`、`ArchUnit`。
- [ ] 至少 1 名模块 Owner 审批；涉及数据库、鉴权、跨语言契约、审计表结构时需 2 名审批。
- [ ] 单元测试覆盖新增业务分支；集成测试覆盖跨进程/跨中间件行为。
- [ ] 涉及数据库结构时，Flyway 迁移脚本与代码在同一分支、同一 MR 中提交。
- [ ] 涉及接口变化时，同步更新 `docs/02-design/openapi-java.yaml` 或 `cross-language-protocol.md`，并通过契约校验。
- [ ] 没有硬编码密钥、Token、连接串、PII 或完整 Prompt 进入代码、测试快照与日志配置。
- [ ] 没有提交 `target/`、`node_modules/`、`.env`、IDE 本地配置、密钥文件。
- [ ] ArchUnit 豁免（若确有必要）附带 ADR 编号与到期时间。
- [ ] 变更后的文档、DDL、UML 与实现没有相互矛盾。
- [ ] 合并方式使用 Squash Merge；合并后删除源分支。

分支保护要求：

- 禁止 force push 到 `main`。
- 禁止绕过 required checks。
- 要求 conversation resolved 后才可合并。
- 要求线性历史或 squash 历史，避免无意义的 merge commit 噪声。

## 4. Flyway 迁移脚本必须随分支提交

### 4.1 为什么

数据库结构是代码运行契约的一部分，不是部署时的"手工附件"。Flyway 迁移脚本必须与依赖它的 Java 代码在同一个分支、同一次 MR 中提交，原因如下：

1. **原子审查。** Reviewer 需要同时看到"代码如何使用新列"和"新列如何创建"，才能判断默认值、约束、索引和回滚风险。
2. **环境一致性。** CI、开发、预发、生产都从 `main` 的迁移序列演进；脚本不在分支里，就不会进入任何环境的迁移链。
3. **Flyway 无法发现不存在的脚本。** Flyway 只能校验"已存在的版本"和"已应用的版本"。一个从未提交的 `V7` 脚本不会让 Flyway 报错，它只会安静地不存在。
4. **版本顺序可追溯。** 共同提交才能保证版本号、依赖顺序、代码上线顺序在同一评审上下文中被检查。
5. **禁止事后补丁式救火。** 上线后再手工执行 DDL 会造成环境漂移，下一次自动迁移可能失败。

迁移规范：

```text
backend-java/<module>/src/main/resources/db/migration/
  V{yyyyMMddHHmm}__{lower_snake_case_description}.sql
```

- 已合并到 `main` 或已应用到任何共享环境的脚本不可修改，只能新增版本修正。
- 默认只做向前迁移；需要回滚时编写新的补偿脚本，不依赖 Flyway undo（社区版不可用）。
- 大表变更必须评估锁表时间，拆成"加可空列 -> 回填 -> 加约束"多步迁移。
- 每个迁移必须在 Testcontainers 启动的真实 PostgreSQL/MySQL 上执行；不允许只靠 H2 或 `ddl-auto=update`。
- CI 检查 MR 是否同时包含代码与迁移：只要 `src/main/java` 发生数据库相关改动，或 MR 描述声明了 schema change，就必须出现 `db/migration/V*.sql` diff。

### 4.2 漏提交事故案例（复合教学案例）

以下为用于训练的复合案例，不对应某一家公司的真实数据：

1. `document-service` 为解析失败重试新增 `retry_count` 字段，开发者在本地数据库手工加了列，并在代码中读写该列。
2. 对应的 `V20261015__add_document_retry_count.sql` 只存在本地，提交时被遗漏，MR 只包含 Java 代码。
3. 单元测试把 Repository mock 掉了；CI 没有启动真实数据库执行迁移，因此测试全绿。Flyway 在预发环境也没有报"缺少脚本"，因为 `V20261015` 从未进入版本库。
4. 发布后，新代码查询 `retry_count` 直接报 `Unknown column`。用户上传完成接口 500，解析状态无法推进；重试逻辑反复失败，告警开始堆积。
5. 团队回滚应用版本，手工在生产补列，再重新发布。事故的直接修复时间不长，但期间文档处理不可用，且手工 DDL 让预发与生产结构产生漂移。
6. 根因不是"开发者忘了提交一个文件"，而是流程允许"代码变更"和"数据库迁移"分离评审，且 CI 没有在真实数据库上验证迁移链。

防复发动作：

- MR 模板加入"是否包含 Flyway 脚本"必选项，并由 CI 检查 diff。
- 所有 Repository 集成测试使用 Testcontainers，启动时执行完整迁移链。
- 部署前执行 `flyway validate` 并在预发跑一次迁移演练。
- 禁止手工修改共享环境结构；紧急变更也必须在事后立即补齐版本化脚本并通过 PR 审核。

## 5. 合并与回滚

- 推荐 Squash Merge，一个 MR 对应一个可回滚提交；提交信息保留 REQ 编号。
- 回滚以 revert MR 为主，不直接 reset `main`。
- 数据库变更不随应用回滚而自动撤销；回滚方案必须在 MR 描述中写明（前向兼容、补偿脚本或双写过渡）。
- 发布后观察错误率、问答 P99、MQ 消费延迟和审计写入延迟，再关闭变更单。

---

拦截的是什么问题：拦截"代码先合并、数据库迁移后补""提交信息不可追溯""绕过 CI 和评审直接改主干"这类协作失控，避免环境漂移与不可回滚的发布。
