# 本地启动指南

本文说明如何在本地完整拉起整套服务（中间件 + Java 控制面 + Python 数据面 + 前端），供开发与联调使用。

## 拓扑总览

| 主机 | 组件 | 地址 |
|---|---|---|
| Windows | MySQL 8 | `127.0.0.1:3306` |
| Windows | Java 控制面 | `8080` gateway / `8081` user / `8082` document / `8083` reminder |
| Windows | Python 数据面 API | `0.0.0.0:8000` |
| Windows | 前端 dev server | `5173` |
| WSL2 (docker) | RocketMQ namesrv / broker | `127.0.0.1:9876` / `10911` |
| WSL2 (docker) | PostgreSQL 16 + pgvector | `127.0.0.1:5432` |
| WSL2 (docker) | MinIO | `127.0.0.1:9100` |
| WSL2 (venv) | 解析 / 向量 Worker | 无端口，消费 MQ |

> Windows 侧访问 WSL 容器要用 WSL 的 eth0 IP，不能假设 `localhost` 转发一定存在；
> WSL 内部一律 `127.0.0.1`。

## 启动顺序

```powershell
# ① WSL 内启动中间件（RocketMQ / PostgreSQL / MinIO）
wsl -u root bash /mnt/c/<repo>/docs/00-infra/prepare-local-stack.sh /mnt/c/<repo>

# ② WSL 内启动解析 / 向量 worker（必须在 Java 之前）
wsl -u root bash /mnt/c/<repo>/docs/00-infra/setup-parse-worker.sh start all

# ③ Windows 启动 Java 控制面（五个服务）
powershell -File <repo>\docs\00-infra\run-local.ps1

# ④ Windows 启动 Python 数据面 API
powershell -File <repo>\docs\00-infra\run-ai-local.ps1 -Action start -Component api

# ⑤ 前端开发服务器
cd frontend-vue && npm install && npm run dev
```

## 环境变量

所有配置经 `DOCQA_*` 环境变量注入，仓库内不含任何真实凭据。启动脚本会从
Machine / User 两级读取 `DOCQA_*` 变量，缺失关键项时直接报错而非回退默认值。

必需变量（启动前必须配置）：

- `DOCQA_JWT_SECRET`、`DOCQA_DB_PASSWORD`、`DOCQA_REDIS_PASSWORD`
- `DOCQA_REDIS_HOST`、`DOCQA_ROCKETMQ_NAMESRV`
- `DOCQA_MINIO_ENDPOINT` / `DOCQA_MINIO_ACCESS_KEY` / `DOCQA_MINIO_SECRET_KEY`
- `DOCQA_DEMO_PASSWORD_HASH`

Python 侧额外变量（见 `ai-python/README.md` 配置节）：

- `DOCQA_AI_PG_DSN`、`DOCQA_AI_REDIS_URL`、`DOCQA_AI_MQ_ENABLED`
- `DOCQA_AI_MINIO_ENABLED` / `DOCQA_AI_MINIO_ACCESS_KEY` / `DOCQA_AI_MINIO_SECRET_KEY`

## 一键验证

```bash
./scripts/verify-all.sh --offline                 # Git Bash / Linux / CI
powershell -File scripts/verify-all.ps1 -Offline  # Windows PowerShell 5.1
```

脚本按序执行 Java `mvn clean verify`、Python `pytest + ruff`、前端 `typecheck + lint + test + build`，
任一失败即非零退出。

## 数据库最小权限

生产/半生产环境建议收敛数据库权限为最小集（审计只追加、迁移与运行时账号分离），
详见 `ops/sql/README.md`。
