# 文档索引

本目录按「基础设施 → 需求 → 设计」组织，所有命令都可以从仓库根目录直接复制执行。

## 00-infra · 基础设施与本地运行

| 文档 | 内容 |
|---|---|
| [getting-started.md](00-infra/getting-started.md) | 本地完整启动、端口、环境变量、故障排查 |
| [maven-modules.md](00-infra/maven-modules.md) | Java 多模块职责、依赖方向与构建顺序 |
| [archunit-rules.md](00-infra/archunit-rules.md) | 架构约束与 ArchUnit 规则 |
| [api-doc-convention.md](00-infra/api-doc-convention.md) | 接口文档与错误码约定 |
| [git-workflow.md](00-infra/git-workflow.md) | 分支模型、提交规范、PR 检查项 |
| [dual-tenant-e2e-drill.md](00-infra/dual-tenant-e2e-drill.md) | 本地双租户 HTTP 演练报告、证据矩阵与界面截图 |
| [rollback-runbook.md](00-infra/rollback-runbook.md) | 回滚操作手册 |
| [naming.md](00-infra/naming.md) | 命名与改名边界（哪些能改、哪些是契约） |
| [ci-pipeline.yml](00-infra/ci-pipeline.yml) | CI 门禁清单与本地等价命令 |

## 01-requirements · 需求

| 文档 | 内容 |
|---|---|
| [user-stories.md](01-requirements/user-stories.md) | 用户故事与验收边界 |
| [tdd.md](01-requirements/tdd.md) | 测试驱动开发计划 |
| [uml.md](01-requirements/uml.md) | 用例图、时序图与领域模型 |

## 02-design · 设计

| 文档 | 内容 |
|---|---|
| [storage.md](02-design/storage.md) | 表结构、索引、向量存储与迁移 |
| [openapi-java.yaml](02-design/openapi-java.yaml) | Java 对外接口契约（OpenAPI 3.0） |
| [cross-language-protocol.md](02-design/cross-language-protocol.md) | Java ↔ Python 跨语言治理协议 |
| [saas-kernel-upgrade.md](02-design/saas-kernel-upgrade.md) | SaaS 内核升级方案与落地记录 |
| [saas-tenant-decisions.md](02-design/saas-tenant-decisions.md) | 多租户遗留决策清单 |
| [retrieval-eval.md](02-design/retrieval-eval.md) | 检索质量评估 harness（Recall@k / MRR / nDCG@k 离线回归） |
| [retrieval-golden-set.md](02-design/retrieval-golden-set.md) | 可溯源语料黄金集设计与覆盖说明 |
| [performance-baseline.md](02-design/performance-baseline.md) | 本地离线检索压测基线与复现方法 |
| [migration-comment-codes.md](02-design/migration-comment-codes.md) | 历史迁移注释代号索引（仅供追溯） |

## 其他

- [README](../README.md)：项目介绍与快速开始
- [CONTRIBUTING](../CONTRIBUTING.md)：贡献指南
- [SECURITY](../SECURITY.md)：安全策略
- [CHANGELOG](../CHANGELOG.md)：版本变更记录

## 文档路径约定

- 文档内链接一律使用相对路径；从仓库根目录复制的命令必须可直接执行。
- 新增设计文档放 `02-design/`，需求与验收放 `01-requirements/`，运行、CI、构建与运维放 `00-infra/`，按主题命名，不用阶段代号。
- `docs/02-design/openapi-java.yaml` 与 `ai-python/` 中的评测基线是 CI 契约文件，路径固定，不随文档整理移动。
- 已发布的历史文档保留原路径以稳定外部链接；新文档遵循编号分层。
- 交叉引用以本索引为准。设计决策写明取舍与代价，不用编号代替解释。

> 文档与实现冲突时，以「实现 + 测试」为准，并在同一个 PR 中修正文档。
