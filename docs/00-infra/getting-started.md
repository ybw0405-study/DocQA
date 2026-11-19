# 本地启动指南

本文说明如何在本机完整拉起整套服务（中间件 + Java 控制面 + Python 数据面 + 前端），供开发与联调使用。
所有脚本都按自身位置推导仓库根目录，**目录改名或移动不需要改任何命令**。

命名边界（哪些名字会随目录变、哪些绝对不能改）见 [naming.md](naming.md)。

---

## 一键启动（2026-11-19 起，推荐）

前置：安装并打开 **Docker Desktop**（本机 `C:\Users\YYYBBW\AppData\Local\Programs\DockerDesktop`）。
旧方案里的 WSL 中间件、Windows 原生 mysqld、开机自启、34 个全局环境变量**全部退役**，本文后半部分保留仅供回滚参考。

```powershell
cd C:\Users\YYYBBW\Desktop\DocQA

# 仅首次：从模板生成项目内配置（gitignored，不写 Windows 全局作用域）
Copy-Item ops/env/local.env.example ops/env/local.env

.\scripts\start-dev.ps1          # compose up → 等健康 → 幂等授权 → 构建 → 起 5 个 Java 服务
.\scripts\stop-dev.ps1           # 只停应用进程
.\scripts\stop-dev.ps1 -Infra    # 连容器一起停（卷保留，数据不丢）
```

脚本选项：`-NoBuild` 跳过 `mvn package`（jar 已最新时）、`-InfraOnly` 只起中间件、`-SkipGrants` 跳过授权重放。

**这套方案的四个性质：**

1. **零全局环境变量**：所有配置在 `ops/env/local.env`（模板 `local.env.example`），由 `start-dev.ps1` 注入**进程作用域**，脚本退出即消失。Windows User / Machine 作用域里 `DOCQA_*` 应为 0 个（与 AgentFlow 的 `AF_*` 对称）。
2. **零 IP 漂移**：中间件全在 Docker Desktop bridge，端点固定 `127.0.0.1:<专属端口>`，不再依赖 WSL `eth0` 地址，也就不需要开机刷环境变量。
3. **零开机自启**：计划任务 `DocQA Local Stack` 已 Disabled、WSL 三个 `docqa-*.service` 已 `disable`、原生 `MySQL84` 服务由管理员脚本改 Manual。用哪个项目就跑哪个项目的 `start-dev.ps1`。
4. **可与 AgentFlow 同时运行**：端口段完全错开，不再需要互斥切换。

### 端口表（唯一事实来源：`ops/env/local.env`）

| 服务 | 地址 | 服务 | 地址 |
|---|---|---|---|
| MySQL 8.4 | `127.0.0.1:13306` | RocketMQ NameServer | `127.0.0.1:19876` |
| PostgreSQL + pgvector | `127.0.0.1:15432` | RocketMQ Broker / VIP | `127.0.0.1:20911` / `20909` |
| Redis | `127.0.0.1:16380` | Python 数据面 API | `127.0.0.1:18000` |
| MinIO API / Console | `127.0.0.1:19100` / `19101` | Java 网关 / user / document / reminder | `18080` / `18081` / `18082` / `18083` |
| 前端 Vite | `127.0.0.1:5173` | | |

> RocketMQ 的 broker 会把 `brokerIP1:listenPort` 注册进 NameServer，客户端按此直连；
> `ops/infra/broker.conf` 显式写 `brokerIP1=127.0.0.1` / `listenPort=20911`，VIP 端口为 `listenPort-2=20909`，
> 三者都映射到宿主 `127.0.0.1`。**不要只改宿主映射端口而不改 listenPort**，客户端会连到旧端口。
> 同理 NameServer 端口由 `ops/infra/namesrv.properties` 的 `listenPort=19876` 指定。

### 数据库

首次搬迁已完成：原 Windows 原生 `docqa` 库（16 张表、含 Flyway V1–V10 与 `default` 租户行）
经 `mysqldump` 导入 `docqa-mysql` 容器卷，备份在 `ops/backup/docqa-20261119.sql`（gitignored）。
`start-dev.ps1` 每次启动会幂等重放 `ops/sql/least-privilege.sql.tmpl`（账号 host 为 `%`，因为
Windows 侧经端口映射连入时 MySQL 看到的是 bridge 网关地址）。迁移仍由 document-service 启动时跑 Flyway。

### Python 数据面与前端

`start-dev.ps1` 默认只起中间件 + 5 个 Java 服务。Python 数据面与前端仍按本文后半部分的
「手动启动顺序」单独启动（端口 `18000` / `5173`）；它们依赖 WSL venv 与 librocketmq，暂未容器化。

### 回滚

旧链路文件全部保留、未修改：`docs/00-infra/start-local.ps1`、`run-local.ps1`、
`prepare-local-stack.sh`、`local-stack.compose.yaml`、`install-wsl-autostart.sh`。
回滚步骤：管理员启用 `MySQL84` 服务 → WSL 里 `systemctl enable --now docqa-infra.service ...` →
启用计划任务 → 用旧脚本启动。数据库备份见上。

---

## 以下为 legacy 方案（WSL + 原生 mysqld，已退役，仅供回滚参考）

## 拓扑总览

| 主机 | 组件 | 地址 |
|---|---|---|
| Windows | MySQL 8 (`MySQL84` 服务) | `127.0.0.1:3306` |
| 虚拟机 | Redis | `192.168.40.128:6379` |
| WSL2 (docker, host 网络) | RocketMQ namesrv / broker | `<WSL_IP>:9876` / `10911` |
| WSL2 (docker, host 网络) | PostgreSQL 16 + pgvector | `<WSL_IP>:5432` |
| WSL2 (docker, host 网络) | MinIO | `<WSL_IP>:9100` |
| WSL2 (venv) | 解析 / 向量 Worker | 无端口，消费 MQ |
| Windows | Java 控制面 | `8080` gateway / `8081` user / `8082` document / `8083` reminder |
| Windows | Python 数据面 API | `8000` |
| Windows | 前端 dev server | `5173` |

> `<WSL_IP>` 是 WSL2 `eth0` 的 NAT 地址，**每次 Windows 重启都会变**。
> Windows 侧不能一律假设 `localhost` 转发可用（实测 namesrv 的 9876 就不通），
> 所以由 `start-local.ps1` 在每次登录后自动回写环境变量。
> WSL 内部一律用 `127.0.0.1`。

## 一次性准备

在**常驻的 WSL root 终端**执行（不要用一次性 `wsl -e bash` 会话托管后台进程）：

```bash
REPO="$(pwd)"                                # 在 WSL 中先 cd 到仓库根目录

# ① 中间件：生成仓库外的本地密钥、按当前 WSL IP 渲染 broker.conf、拉起四个容器
bash "$REPO/docs/00-infra/prepare-local-stack.sh"

# ② Python 依赖（librocketmq.so + venv + 可编辑安装）；目录改名后必须重跑一次
bash "$REPO/docs/00-infra/setup-parse-worker.sh" install

# ③ worker 运行环境变量（Redis 口令从 stdin 读入，不落命令行）
printf '%s\n' '<Redis 口令>' | bash "$REPO/docs/00-infra/setup-parse-worker.sh" env
```

Windows 侧的 `DOCQA_*` 用户/系统环境变量需事先配置，脚本只读取、不含任何凭据。
必需项见下文「环境变量」。

## 开机自启（推荐）

分两段，缺一不可：

**WSL 侧** —— 注册 systemd 单元，WSL 一启动就重渲染配置并拉起 worker：

```bash
bash "$REPO/docs/00-infra/install-wsl-autostart.sh" install
bash "$REPO/docs/00-infra/install-wsl-autostart.sh" status     # 核对 3 个单元
# 撤销：bash "$REPO/docs/00-infra/install-wsl-autostart.sh" uninstall
```

前提：`/etc/wsl.conf` 含 `[boot] systemd=true`（本机已启用）。
容器自身是 `restart: unless-stopped`，但 `brokerIP1` 依赖当前 WSL IP，
所以仍需要 `docqa-infra.service` 这个 oneshot 每次重渲染并重启 broker。

**Windows 侧** —— 注册「登录时触发」的计划任务（当前用户，无需管理员）：

```powershell
powershell -ExecutionPolicy Bypass -File .\docs\00-infra\start-local.ps1 -InstallAutostart
# 若希望连 Java / Python / 前端一起拉起：
powershell -ExecutionPolicy Bypass -File .\docs\00-infra\start-local.ps1 -InstallAutostart -IncludeApp
# 撤销：
powershell -ExecutionPolicy Bypass -File .\docs\00-infra\start-local.ps1 -UninstallAutostart
```

该脚本每次登录会：拉起一个脱离会话的常驻 WSL 会话 → 读取新 `eth0` IP →
回写所有含旧 WSL IP 的 `DOCQA_*` 变量 → 等待 3 个单元 `active` →
（可选）拉起应用层并做 4/4 就绪自检与单点重试。
日志在 `%LOCALAPPDATA%\DocQA\startup.log`，可通过
`Get-ScheduledTask -TaskName 'DocQA Local Stack'` 核对。

> **为什么需要常驻 WSL 会话**：本机 WSL2 在最后一个会话退出后会回收虚拟机，
> systemd 的 oneshot 单元会在启动途中被 SIGTERM，表现为 `journalctl --list-boots`
> 里连续几十秒一次的重启、worker 永远 `inactive`。脚本用 WMI `Win32_Process.Create`
> 起一个 `wsl -u root -e sleep …` 锚住虚拟机，进程不属于调用方进程树，
> 计划任务结束后依然存活。撤自启时会一并结束它。

> 不注册计划任务也可以手动跑一次同样的流程：
> `powershell -File .\docs\00-infra\start-local.ps1 -IncludeApp`。

## 手动启动顺序

```powershell
# ① 同步端点 + 等待 WSL 栈（不启应用层）
powershell -File .\docs\00-infra\start-local.ps1

# ② Java 控制面（五个服务，jar 需已由 mvn -DskipTests package 生成）
powershell -File .\docs\00-infra\run-local.ps1 -Action start
powershell -File .\docs\00-infra\run-local.ps1 -Action status

# ③ Python 数据面 API
powershell -File .\docs\00-infra\run-ai-local.ps1 -Action start -Component api

# ④ 前端
cd frontend-vue; npm install; npm run dev
```

### 端口被占用时

本机 `8080` / `8081` 若被其它常驻程序占用，用端口整体平移，不必停别人的进程：

```powershell
powershell -File .\docs\00-infra\start-local.ps1 -IncludeApp -PortOffset 10000
# -> gateway 18080 / user 18081 / document 18082 / reminder 18083
#    同时自动设置网关上游 DOCQA_*_SERVICE_URI 与前端 VITE_PROXY_*_URL
```

## 环境变量

所有配置经 `DOCQA_*` 环境变量注入，仓库内不含任何真实凭据。
启动脚本从 Machine / User 两级读取，缺失关键项时直接报错而非回退默认值。

必需变量：

- `DOCQA_JWT_SECRET`、`DOCQA_DB_PASSWORD`、`DOCQA_REDIS_PASSWORD`
- `DOCQA_REDIS_HOST`、`DOCQA_ROCKETMQ_NAMESRV`
- `DOCQA_MINIO_ENDPOINT` / `DOCQA_MINIO_ACCESS_KEY` / `DOCQA_MINIO_SECRET_KEY`
- `DOCQA_DEMO_PASSWORD_HASH`

Python 侧额外变量（见 `ai-python/README.md` 配置节）：

- `DOCQA_AI_PG_DSN`、`DOCQA_AI_REDIS_URL`、`DOCQA_AI_MQ_ENABLED`
- `DOCQA_AI_MINIO_ENABLED` / `DOCQA_AI_MINIO_ACCESS_KEY` / `DOCQA_AI_MINIO_SECRET_KEY`

含 WSL IP 的变量由 `start-local.ps1` 自动维护；若虚拟机 Redis 换了地址，
额外传 `-RedisHost <host:port>` 即可。

## 一键验证

```bash
./scripts/verify-all.sh --offline                 # Git Bash / Linux / CI
powershell -File scripts/verify-all.ps1 -Offline  # Windows PowerShell 5.1
```

脚本按序执行 Java `mvn clean verify`、Python `pytest + ruff`、前端 `typecheck + lint + test + build`，
任一失败即非零退出。脚本会先清掉进程里的 `DOCQA_*` / `SERVER__PORT` 等运行期变量，
保证离线门禁与 CI 环境一致（否则本机常配的审计守卫、实验预算会污染 Spring Boot 宽松绑定，
出现「CI 全绿、本机假红」）。

> **Windows 注意**：`mvn clean` 无法删除正被 JVM 占用（已启动服务）的 `target/*.jar`。
> 跑门禁前先执行
> `powershell -File .\docs\00-infra\run-local.ps1 -Action stop`，
> 跑完再用 `start-local.ps1 -IncludeApp` 拉起即可。

## 故障排查

| 现象 | 原因 | 处理 |
|---|---|---|
| `wsl -l -v` 显示 Ubuntu `Stopped`，单元反复 `activating → inactive` | 无常驻 WSL 会话，虚拟机被回收 | 跑一次 `start-local.ps1`，它会拉起 keep-alive 会话 |
| worker 日志 `ValueError: Port out of range 0-65535` | worker 环境文件混入 `\r`，systemd 在 `\r` 处把 Redis URL 截断成 `redis://:<口令>` | `install-wsl-autostart.sh install` 会自动清洗并备份 `.bak` |
| 上传卡在 `PARSING` | topic 未预建，消费者静默空轮询 | `wsl-boot.sh` 已带 10 次重试；手工执行 `setup-parse-worker.sh topics` 核对 |
| 服务启动报 `Migration checksum mismatch for migration version N` | 已应用的迁移文件被改过（哪怕只改注释） | 本地库执行 Flyway repair：把 `flyway_schema_history` 该版本的 `checksum` 更新为当前文件的 CRC32（对每行内容做 CRC32，首行去 BOM）。生产环境应**禁止**修改已应用的迁移 |
| `mvn clean verify` 删不掉 `target/*.jar` | jar 正被运行中的 JVM 占用 | 先 `run-local.ps1 -Action stop` |
| CI 全绿、本机门禁假红 | 本机 `DOCQA_*` 污染了 Spring Boot 宽松绑定 / pydantic-settings | 门禁脚本已自动清理这些变量，无需手工处理 |
| `git push` / `fetch` 报 `Recv failure: Connection was reset` 或 `Could not connect to server`，而 `gh` 命令正常 | `github.com` 解析到的官方 IP 在部分网络不可达；`api.github.com` 不受影响 | 临时绑定一个可达 IP（只写本仓库 `.git/config`，不会提交）：`git config --local http.curloptResolve github.com:443:<可达IP>`。**IP 会轮换，默认解析恢复可达后要撤销**：`git config --local --unset http.curloptResolve`（绑死已失效的 IP 会反过来连不上）。或把 `~/.ssh/id_ed25519.pub` 加到 GitHub 后把远程换成 SSH |

## 数据库最小权限

生产/半生产环境建议收敛数据库权限为最小集（审计只追加、迁移与运行时账号分离），
详见 `ops/sql/README.md`。
