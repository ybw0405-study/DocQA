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

## 验证方式

<!-- 列出执行的命令与结果，例如 scripts/verify-all 全绿、手工验证步骤 -->

```text
# 粘贴关键输出
```

## 检查清单

- [ ] 已 rebase 到最新 `origin/main`，一个 PR 只做一件事
- [ ] 提交信息符合 [Conventional Commits](https://www.conventionalcommits.org/)
- [ ] `scripts/verify-all` 通过（或 CI 全绿）
- [ ] 新增/修改的测试覆盖了行为变化
- [ ] 涉及接口/数据库/跨语言契约时，文档与迁移脚本已同步
- [ ] 没有提交密钥、Token、连接串、PII 或 `target/`、`node_modules/`、`.env`
- [ ] 破坏性变更已写明迁移方式

## 风险与回滚

<!-- 影响范围、监控指标、回滚方式；数据库变更请说明前向兼容策略 -->

## 截图 / 日志

<!-- 可选，注意脱敏 -->
