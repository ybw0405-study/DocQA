## 变更说明

<!-- 说明背景、改了什么、为什么这样改 -->

## 关联 Issue

<!-- 例如 Refs: #123 / Closes: #123 -->

## 变更类型

- [ ] `feat` 新功能
- [ ] `fix` 缺陷修复
- [ ] `docs` 文档
- [ ] `refactor` 重构（不改变外部行为）
- [ ] `perf` 性能优化
- [ ] `test` 测试
- [ ] `build` / `ci` 构建与流水线
- [ ] `chore` 杂项
- [ ] **BREAKING CHANGE**（请在下方说明迁移方式）

## 验证方式（必填）

<!-- 列出执行的命令与真实结果，例如 scripts/verify-all 全绿、手工验证步骤 -->

```text
# 粘贴关键输出
```

## 回滚方式（必填）

<!-- 必须写出「按 digest 部署上一版本」的可执行步骤，见 docs/00-infra/rollback-runbook.md：
     1) 从上一版 Release 的 digests.txt / Release notes 取各服务镜像 digest；
     2) docker pull <repo>:<tag>@sha256:<digest>；
     3) 把部署清单的 image 切换到该 digest 并重新应用（不用 latest / 不用浮动 tag）；
     4) 跑「回退后验证清单」；数据库结构变更需说明前向兼容策略，禁止 flyway clean。
     若本次无制品变更，写明「无制品变更，按代码 revert PR」。 -->

## git diff --check 输出（必填）

<!-- 提交前执行 `git diff --check`，把真实输出粘贴于此；无输出请写「无输出，0 问题」。 -->

```text
git diff --check
```

## 检查清单

- [ ] 已 rebase 到最新 `origin/main`，一个 PR 只做一件事
- [ ] 提交信息符合 [Conventional Commits](https://www.conventionalcommits.org/)
- [ ] `scripts/verify-all` 通过（或 CI 全绿）
- [ ] 新增/修改的测试覆盖了行为变化
- [ ] 涉及接口/数据库/跨语言契约时，文档与迁移脚本已同步
- [ ] 没有提交密钥、Token、连接串、PII 或 `target/`、`node_modules/`、`.env`
- [ ] 破坏性变更已写明迁移方式
- [ ] CI 新增门禁（SBOM / 版本下限 / 配置守卫 / 跨服务 e2e）均已跑绿

## 风险与回滚补充

<!-- 影响范围、监控指标；数据库变更请说明前向兼容策略 -->

## 截图 / 日志

<!-- 可选，注意脱敏 -->
