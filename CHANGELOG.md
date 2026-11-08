# 变更日志

本文件格式遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

### Added

- 补全开源社区文件：贡献指南、行为准则、安全策略、Issue/PR 模板、CODEOWNERS、Dependabot 配置
- README 增加徽章、目录、Mermaid 架构图与文档索引

### Changed

- 统一各端版本号到 `1.0.0`

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

### Changed

- 项目与代码标识统一为 **DocQA**：Java 包 `com.docqa`、Maven 坐标 `com.docqa:docqa`、
  Python 包 `docqa_ai`、环境变量 `DOCQA_*`、MySQL 库 `docqa`、PostgreSQL 库 `docqa_vec`、
  MinIO 桶 `docqa-documents`
- CI Action 升级到 Node 24 版本（`checkout@v5`、`setup-java@v5`、`setup-python@v6`、
  `setup-node@v5`、`upload-artifact@v5`）

### Fixed

- 修正改名后遗留的文档路径、许可证元数据、Docker/数据库/桶名不一致
- 修正 ArchUnit 单独执行时 `mq-support` 无匹配测试导致的失败
- 修正 `start-local.ps1` 只回写 User 作用域导致 Machine 变量携带旧 WSL IP 的问题

### Security

- 数据库最小权限：`audit_event` 对运行时账号只允许 `SELECT` / `INSERT`
- 审计只追加守卫支持 `off` / `warn` / `enforce` 三种模式

[Unreleased]: https://github.com/ybw0405-study/DocQA/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/ybw0405-study/DocQA/releases/tag/v1.0.0
