# DocQA · 智能学习助手

面向大学生的本地智能学习工作台：上传课件与文档，异步解析并建立向量索引，提供基于文档内容的 RAG 问答；同时支持课表导入、作业截止提醒与自定义提醒。

基于 **Spring Boot 3 微服务 + FastAPI + Vue 3** 的双栈架构，覆盖认证授权、文档解析、向量检索、流式问答、提醒调度与管理治理的全链路。

---

## 功能特性

- **文档智能问答**：上传 PDF / Word / 文本，后台异步解析、分块、向量化，支持基于文档的 RAG 问答（同步 + SSE 流式），回答附带引用溯源
- **认证与 RBAC**：JWT 无状态认证、刷新令牌轮换、基于角色的访问控制（USER / ADMIN）
- **文档管理**：上传、重命名、标签、软删除、失败重试、重建索引
- **课表与提醒**：ICS 日历导入、作业截止提醒、自定义提醒、站内通知
- **治理与可观测**：AI 调用开关、按用户配额、审计事件、用量统计、Prometheus 指标

## 技术栈

| 端 | 技术 |
|---|---|
| 后端（Java 17） | Spring Boot 3.3、Spring Cloud Gateway、Spring Security、MyBatis / JDBC、Flyway |
| 数据面（Python ≥3.11） | FastAPI、LangChain/LangGraph、Pydantic v2、psycopg3、OpenTelemetry |
| 前端 | Vue 3.5、TypeScript 5.9、Vite、Pinia（无 UI 组件库，样式自持） |
| 存储与中间件 | MySQL 8、PostgreSQL 16 + pgvector、Redis、RocketMQ 5、MinIO |

## 架构

项目采用**控制面 / 数据面分离**的微服务架构：

```
                        ┌──────────────────────────────┐
                        │          agent-gateway        │  (8080, 统一入口)
                        │  鉴权 · 限流 · 路由 · 治理    │
                        └──────┬──────────┬────────────┘
                               │          │
              ┌────────────────┘          └─────────────────┐
              ▼                                              ▼
   ┌────────────────────┐                        ┌──────────────────────┐
   │     Java 控制面     │                        │    Python 数据面      │
   │  user-service 8081 │                        │     ai-api 8000      │
   │ document-svc 8082 │                        │  RAG · Agent · PII   │
   │ reminder-svc 8083 │                        │ parse-worker (MQ)    │
   │  audit-service     │                        │ vector-worker (MQ)   │
   └─────────┬──────────┘                        └──────────┬───────────┘
             │                                               │
   ┌─────────┴───────────────────────────────────────────────┴─────────┐
   │  MySQL · PostgreSQL(pgvector) · Redis · RocketMQ · MinIO           │
   └───────────────────────────────────────────────────────────────────┘
```

各服务职责、依赖关系与目录说明见 [docs/00-infra/maven-modules.md](docs/00-infra/maven-modules.md)。

## 目录结构

```
DocQA/
├── backend-java/        # Java 控制面（Maven 多模块）
│   ├── common/          #   公共模型、指标、架构约束
│   ├── mq-support/      #   RocketMQ 管理采集适配层
│   ├── user-service/    #   认证与用户（8081）
│   ├── document-service/#   文档上传·解析·索引（8082）
│   ├── agent-gateway/   #   统一网关与 AI 治理（8080）
│   ├── reminder-service/#   提醒与通知（8083）
│   └── audit-service/   #   审计（纯 MQ 消费者）
├── ai-python/           # Python 数据面（FastAPI）
│   └── src/docqa_ai/
│       ├── api/         #   REST 入口
│       ├── agent/       #   Agent 编排与 RAG
│       ├── parse_worker/#   解析 / 向量 worker
│       ├── governance/  #   配额、预算、审计
│       └── providers/   #   LLM / Embedding / Rerank 适配
├── frontend-vue/        # 前端（Vue 3 + Vite）
├── docs/                # 需求、设计、运维文档
├── ops/                 # 数据库最小权限脚本
└── scripts/             # 一键验证脚本
```

## 快速开始

### 前置依赖

- JDK 17、Maven 3.9+（父 POM 的 enforcer 拒绝 3.8）
- Node.js ≥22.22.2、pnpm / npm（jsdom 30 与 Vite 8 的下限）
- Python 3.11+（建议 3.13）
- MySQL 8、Redis、PostgreSQL 16 + pgvector、RocketMQ 5、MinIO
- WSL2（解析 worker 的 RocketMQ 客户端仅提供 Linux 绑定）

### 一键验证

```bash
./scripts/verify-all.sh --offline                 # Git Bash / Linux / CI
powershell -File scripts/verify-all.ps1 -Offline  # Windows PowerShell 5.1
```

脚本按序执行 Java `mvn clean verify`、Python `pytest + ruff`、前端 `typecheck + lint + test + build`，任一失败即非零退出。

### 启动服务

完整顺序、端口与环境变量清单见 [docs/00-infra/getting-started.md](docs/00-infra/getting-started.md)。
以下脚本全部按自身位置推导仓库根，**没有 `<repo>` 占位符，目录改名后也不用改命令**。

首次准备，在 WSL root 终端且当前目录为仓库根时执行：

```bash
REPO="$(pwd)"
bash "$REPO/docs/00-infra/prepare-local-stack.sh"          # 中间件
bash "$REPO/docs/00-infra/setup-parse-worker.sh" install   # venv + C 绑定
bash "$REPO/docs/00-infra/install-wsl-autostart.sh" install # 注册 WSL 开机自启
```

日常使用，在仓库根目录开一个 PowerShell：

```powershell
# 推荐：注册登录自启（当前用户，无需管理员），之后开机自动完成全部拉起
powershell -ExecutionPolicy Bypass -File .\docs\00-infra\start-local.ps1 -InstallAutostart -IncludeApp

# 或：立即启动一次 —— 同步 WSL IP 并拉起中间件、worker、Java、Python API、前端
powershell -ExecutionPolicy Bypass -File .\docs\00-infra\start-local.ps1 -IncludeApp

# 若 8080/8081 被其它程序占用，整体平移端口
powershell -ExecutionPolicy Bypass -File .\docs\00-infra\start-local.ps1 -IncludeApp -PortOffset 10000
```

只启动中间件与 worker（不启应用层）：去掉 `-IncludeApp` 即可；
分步手动启动见 [getting-started.md](docs/00-infra/getting-started.md)。
命名与改名的边界见 [docs/00-infra/naming.md](docs/00-infra/naming.md)。

### 环境变量

所有连接串与凭据均通过 `DOCQA_*` 环境变量注入，仓库内不含任何真实凭据。变量清单见 [docs/00-infra/getting-started.md](docs/00-infra/getting-started.md)。

## 设计文档

- 需求与边界：[docs/01-requirements/user-stories.md](docs/01-requirements/user-stories.md)
- 存储与向量：[docs/02-design/storage.md](docs/02-design/storage.md)
- 接口契约：[docs/02-design/openapi-java.yaml](docs/02-design/openapi-java.yaml)
- 跨语言治理协议：[docs/02-design/cross-language-protocol.md](docs/02-design/cross-language-protocol.md)

## 许可证

[Apache License 2.0](LICENSE) © 2026 YYYBBW
