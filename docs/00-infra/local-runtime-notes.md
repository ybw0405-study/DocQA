# 本机运行时拓扑与踩坑记录（阶段 6 实测）

> 本文件只记录**位置和顺序**这类无法从代码推断的事实。凭据一律不入库：中间件密钥由 `prepare-local-stack.sh` 生成到仓库外的 `/opt/docqa-infra/.env`，应用侧密钥来自 Windows 环境变量，脚本只做「存在性 + 长度」检查，不打印值。

## 1. 谁跑什么

| 主机 | 组件 | 地址 | 由谁拉起 |
|---|---|---|---|
| Windows 本机 | MySQL 8.4 | `127.0.0.1:3306` | 委托方既有服务 |
| Windows 本机 | Java 控制面 ×4 | `8080` gateway / `8081` user / `8082` document / `8083` reminder | `docs/00-infra/run-local.ps1` |
| Windows 本机 | audit-service | 无 HTTP 端口（纯 MQ 消费者 + 定时 relay） | 同上 |
| Windows 本机 | Python 数据面 API | `0.0.0.0:8000`（`GET /health`、`POST /agent/ask`） | `docs/00-infra/run-ai-local.ps1 -Action start -Component api` |
| VM `192.168.40.128` | Redis 6379 | 需要密码 | 委托方既有服务（同一台 VM 上跑的是 **RabbitMQ**，不是 RocketMQ，二者互不相干） |
| ↳ 带日期更正（2026-11-05 S11-3）：VM 重启后**落在 `192.168.40.130`** | Redis 6379 | 同上 | DHCP 漂移，非新搭的实例。`.128:6379` 当场 `TimeoutError`、`.130:6379` 裸 RESP `AUTH +OK` / `PING +PONG`（口令 len=6，明文不落盘），且 `.130` 上 `22` / `5672`（RabbitMQ）OPEN、`3306` / `5432` closed。运行侧生效值已改指 `.130`——当场以 `show-details=always` 重启的 `agent-gateway` 报 `redis` 组件 **UP 且带 `version` 明细**，而本机并无第二个可连的 Redis。**坑**：同一窗口内不同 shell 实例把 `DOCQA_REDIS_HOST` 分别读出过 `.128` 与 `.130`，进程环境变量快照会陈旧 ⇒ 判断「运行中的 JVM 看到什么」只能读注册表作用域，或看该进程自己的可观测面（health 组件明细 / 它的 ESTABLISHED socket）。仍建议委托方给 VM 固定 DHCP 租约。 |
| WSL2 Ubuntu (docker) | RocketMQ namesrv / broker | `127.0.0.1:9876` / `10911`（WSL 网卡 IP 亦可达） | `prepare-local-stack.sh` |
| WSL2 Ubuntu (docker) | PostgreSQL 16 + pgvector 0.8.7 | `127.0.0.1:5432`，库 `docqa_vec`，角色 `docqa` | 同上 |
| WSL2 Ubuntu (docker) | MinIO（应用侧） | `127.0.0.1:9100` API / `9101` 控制台 | 同上（**不能用 9000/9001**，被 Milvus 自带的 minio 占了） |
| WSL2 Ubuntu (venv) | Python 解析 Worker | 无端口，消费 `parse-requested` | `docs/00-infra/setup-parse-worker.sh start parse` |
| WSL2 Ubuntu (venv) | Python 向量 Worker | 无端口，消费 `vector-change-requested`，回投 `vector-change-completed` | `docs/00-infra/setup-parse-worker.sh start vector`（日志 `/root/vector-worker.log`） |
| WSL2 Ubuntu (docker) | Milvus standalone | `19530` / 健康检查 `9091/healthz` | 委托方自备 compose（S6/S5 之后才接入产品链路） |

Windows 侧访问 WSL 容器要用 WSL 的 eth0 IP（实测 `172.24.108.240`），不能假设 `localhost` 转发一定存在；WSL 内部则一律 `127.0.0.1`。

## 2. 启动顺序（重启机器后照这个顺序做）

```powershell
# ① WSL 起中间件（必须在 root 下跑，脚本要 apt/dpkg/chown）
wsl -u root bash /mnt/c/<repo>/docs/00-infra/prepare-local-stack.sh /mnt/c/<repo>
wsl -u root bash /mnt/c/<repo>/docs/00-infra/setup-parse-worker.sh status   # 四个端口应全 open

# ①b 预建 S2 新增的两个 topic（消费者不会自动建，broker 侧 autoCreateTopicEnable 只对生产者生效）
wsl -u root bash -c 'docker exec docqa-rmq-broker sh mqadmin updateTopic -n 127.0.0.1:9876 -b 127.0.0.1:10911 -t vector-change-requested'
wsl -u root bash -c 'docker exec docqa-rmq-broker sh mqadmin updateTopic -n 127.0.0.1:9876 -b 127.0.0.1:10911 -t vector-change-completed'
wsl -u root bash -c 'docker exec docqa-rmq-broker sh mqadmin topicList -n 127.0.0.1:9876'   # 代码共声明 7 个业务 topic，见第 3 节第 7 条

# ② WSL 起两个 Python Worker（必须在 Java 之前，否则 complete/回投后没人消费）
wsl -u root bash /mnt/c/<repo>/docs/00-infra/setup-parse-worker.sh start all

# ③ Windows 起 Java 四个服务 + audit-service
powershell -File <repo>\docs\00-infra\run-local.ps1

# ④ Windows 起 Python API
powershell -File <repo>\docs\00-infra\run-ai-local.ps1 -Action start -Component api

# ⑤ 取证（两段，中间等真实解析回写）
powershell -File <repo>\docs\06-expansion\s0-verify.ps1 -Stage http
powershell -File <repo>\docs\06-expansion\s0-verify.ps1 -Stage final

# ⑥ S2 向量删除链路取证（只碰合成 documentId，可重复执行）
wsl -u root bash /mnt/c/<repo>/docs/06-expansion/s2-vector-probe.sh
```

`setup-parse-worker.sh status` 是这条链的体检入口：它打印六个变量的**长度**、四个依赖端口的连通性、
以及 parse/vector 两个 Worker 各自的运行状态，不打印任何值。

## 3. 会咬人的九件事

1. **`librocketmq.so` 只在 Linux 存在**。`rocketmq-client-python==2.0.0` 是 ctypes 绑定，Windows 侧 pip 能装上但 `import` 即失败——所以解析 Worker 必须在 WSL 跑，Windows 侧 `run-ai-local.ps1 -Component worker` 会主动拒绝并提示改用 Linux 运行位。C++ 客户端包名是 `rocketmq`（实测 2.2.0），落到 `/usr/local/lib/librocketmq.so`。
2. **broker 必须注册「客户端可达」的地址**。`rocketmq-broker.conf` 里的 `brokerIP1` 是运行时按当前 WSL IP 渲染的占位符；改用 bridge + 端口映射会让 broker 注册容器内网 IP，Windows 侧客户端拿到地址后连不上。WSL IP 每次重启都可能变，`prepare-local-stack.sh` 会重新渲染，但**应用侧若把 WSL IP 写死在环境变量里也要同步改**。
3. **uvicorn 在 Windows 上会绕过你的事件循环策略**。它硬用 `ProactorEventLoop`，而 psycopg 异步直接拒绝该循环，症状是问答链路恒定 503。`api/main.py` 的 win32 分支自建 `Server` 并用 Selector 循环驱动；只加 `asyncio.set_event_loop_policy(...)` 是无效的（详见 `cr-log-s0-runtime.md` R-03）。
4. **PowerShell 5.1 的三个坑**：① 含中文的 `.ps1` 必须存成带 BOM 的 UTF-8，否则按 GBK 解码；② 内联 `$env:X` / `$var` 写进 bash 双引号会被 bash 先展开——一律落成 `.ps1` 文件再执行；③ 后端响应头不带 charset，`Invoke-WebRequest` 的 `.Content` 字符串按 Latin-1 解码会变乱码，取证脚本必须从 `RawContentStream` 取原始字节自己按 UTF-8 解（另见 R-09 里 byte[] 被管道枚举成 `Object[]` 的坑）。
5. **Windows 环境变量不会传播进 WSL**，`wsl` 传多行 here-string 也会被换行截断。需要跨侧的脚本一律落到 `/mnt/c/...`，在 WSL 内 `tr -d '\r'` 复制到 `/tmp` 再执行。Git Bash 里 `wsl bash /mnt/c/...` 会被 MSYS 改写路径，必须 `wsl bash -c '...'`。
6. **`/actuator/health` 在 user/document/reminder 上返回 500 而不是 404**，因为这三个模块还没引入 actuator，而全局 Advice 把「未映射路径」兜底成了内部错误（R-01/R-02，S9 修）。健康检查目前只有 `8080` 可信；Python 侧用 `GET :8000/health`。
   ↳ **带日期更正（2026-11-05，S11-3）**：S9 已交付 actuator，真实进程上四个模块（8080/8081/8082/8083）的 `GET /actuator/health` 当场均为 **200**，body 为 `{"status":"UP","groups":["liveness","readiness"]}`，本条描述的症状只在 S0–S8 的快照里成立。但同窗口暴露一个**新异常**：四模块 `application.yml` 的 readiness 分组都 `include: readinessState,db,redis`（注释写着「下游不可用时如实摘流（D-163）」），而此时 Redis 目标地址根本连不通（`.128` 当场 `TimeoutError`），`GET /actuator/health/readiness` 仍返回 **200 `{"status":"UP"}`**。`/actuator/health/{db,redis,ping}` 的 404 与「仓库未配置 `show-details`、默认 never」一致，**不能据此断定 indicator 不存在**。定位过程与结论见 `docs/03-review/cr-log-s11-e2e.md`（判据 A22 / D-163，S11-3c）。
7. **topic 只对生产者懒建，对消费者不会**。`autoCreateTopicEnable = true` 时生产者首次发送会自动建 topic，但消费组订阅一个不存在的 topic 只会静默空轮询——所以 `vector-change-requested` / `vector-change-completed` 必须按 ①b 预建。代码声明的业务 topic 共 7 个：`audit-event`、`reminder-due`、`parse-requested`、`parse-completed`、`parse-failed`、`vector-change-requested`、`vector-change-completed`；本机 `topicList` 实测只列出 5 个，缺的 **`parse-failed` 与 `reminder-due` 说明这两条路径至今从未在真实链路上产生过消息**（此前只由单测覆盖）。
8. **两个 shell 坑**：① `mqadmin` 只存在于 broker 容器里，命令必须写成 `docker exec docqa-rmq-broker sh mqadmin ...`，在 WSL 文件系统里找不到它；② `tr -dc 'a-f0-9' </dev/urandom | head -c 32` 在 `set -euo pipefail` 下会让整脚本以 **141** 退出（head 先关管道，tr 收到 SIGPIPE），生成定长 hex 要改用 `cut -c1-32` 这类读完全部输入的写法。
9. **声明了 `validation_alias` 的配置字段会静默吞掉关键字传参**。`Settings` 里 `redis_url` / `pg_dsn` 用 `AliasChoices(...)` 接环境变量，pydantic-settings 在未开 `populate_by_name` 时**只认别名不认字段名**，于是 `Settings(pg_dsn="")` 里的那个 `""` 落进 `extra="ignore"` 被丢弃，字段值仍然来自本机的 `DOCQA_AI_PG_DSN`。后果不是「测试写错了」而是**测试跑在真实凭据面上**：S5 装配词法通道时才被一条 `isinstance` 断言撞出来（本机 DSN 一旦注入，`build_repositories` 直接返回真的 `PgVectorRepository`）。修法是在 `model_config` 开 `populate_by_name=True`——生产形态不变（不传参仍从环境读，凭据不进代码与仓库），但传参显式覆盖从此有效，并由两条用例分别钉住「覆盖生效」与「不覆盖时仍取环境值」。

## 4. 密钥卫生

- 仓库内不出现任何真实凭据；`local-stack.compose.yaml` 里全部是 `${...}`，由 `prepare-local-stack.sh` 用 `envsubst` 预渲染到仓库外的 `/opt/docqa-infra/compose.rendered.yaml`（600）。
- 目录不能放 `~/docqa-infra`：dockerd 以 root 跑时 `HOME=/root` 是 700，broker 容器内非 root 的 `rocketmq(3000)` 读不到挂载的 `broker.conf`。固定用 `/opt/docqa-infra`（755）+ 文件 644。
- Redis 口令交给 Worker 脚本时走 **stdin 管道**，不进 argv、不落进程列表；所有验证输出只报 `present len=N` 或 SHA-256 前 12 位指纹。
- 演示登录口令 `demo / test-password-123` 来自仓库测试夹具与契约示例，不是生产凭据。
