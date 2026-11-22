# DocQA 一键启动（Windows PowerShell 5.1+）
#
# 顺序：读 ops/env/local.env 注入进程作用域 + 预检 → docker compose up → 等 MySQL 健康
#       → 预建 MQ topic → 幂等重放最小权限授权 → 幂等重放 pgvector 结构
#       → mvn package → 起 5 个 Java 服务
#       → 起 Python 数据面 API(:18000) → 起前端 dev server(:5173)
# 停止：scripts/stop-dev.ps1（-Infra 连容器一起 down，卷保留）
#
# 配置只在进程作用域，不写 Windows 全局；
# 中间件全在 Docker Desktop bridge，端点固定 127.0.0.1，因此不需要开机自启、不需要刷 IP。
# 旧路径 docs/00-infra/{start-local,run-local,prepare-local-stack}.ps1 保留作 legacy 回滚。
[CmdletBinding()]
param(
    [switch]$NoBuild,     # 跳过 mvn package（jar 已是最新时使用）
    [switch]$InfraOnly,   # 只起中间件，不构建、不起任何应用层进程
    [switch]$SkipGrants   # 跳过授权重放（账号已建好时使用）
)

$ErrorActionPreference = 'Continue'
# PS 5.1 会把 native 命令写到 stderr 的普通警告当成终止错误，故不用 Stop；
# 真正的失败点用 $LASTEXITCODE 显式检查 + throw 兜底。


# 清大小写重复的代理变量：本机同时存在 HTTPS_PROXY / https_proxy 时，
# .NET 构造子进程环境字典会抛「已添加项」，Start-Process 一个服务都起不来。
foreach ($n in @('http_proxy','https_proxy','no_proxy','HTTP_PROXY','HTTPS_PROXY','NO_PROXY','ALL_PROXY','all_proxy')) {
    [Environment]::SetEnvironmentVariable($n, $null, 'Process')
}

$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$backend = Join-Path $root 'backend-java'
$compose = Join-Path $root 'ops/docker-compose.infra.yml'
$envFile = Join-Path $root 'ops/env/local.env'
$logs = Join-Path $root 'logs'
New-Item -ItemType Directory -Force -Path $logs | Out-Null

function Get-Conf([string]$Name) {
    return [Environment]::GetEnvironmentVariable($Name, 'Process')
}

# 只看「端口有没有人监听」，不看进程是否存活：uvicorn / vite 都可能在父进程还在时已崩掉。
function Test-Port([int]$Port) {
    try {
        $c = New-Object System.Net.Sockets.TcpClient
        $c.Connect('127.0.0.1', $Port)
        $c.Close()
        return $true
    } catch { return $false }
}

# -----------------------------------------------------------------------------
# 1. 配置：唯一事实来源 = ops/env/local.env（gitignored）
# -----------------------------------------------------------------------------
if (-not (Test-Path -LiteralPath $envFile)) {
    throw '缺少 ops/env/local.env；请先 Copy-Item ops/env/local.env.example ops/env/local.env 并填写。'
}
$loaded = 0
foreach ($line in Get-Content -LiteralPath $envFile -Encoding UTF8) {
    if ($line -match '^\s*(#|$)') { continue }
    $idx = $line.IndexOf('=')
    if ($idx -lt 1) { continue }
    $k = $line.Substring(0, $idx).Trim()
    $v = $line.Substring($idx + 1).Trim()
    if ($v.Length -ge 2 -and ((($v[0] -eq '"') -and ($v[-1] -eq '"')) -or (($v[0] -eq "'") -and ($v[-1] -eq "'")))) {
        $v = $v.Substring(1, $v.Length - 2)
    }
    [Environment]::SetEnvironmentVariable($k, $v, 'Process')
    $loaded++
}
Write-Host ("==> [1/8] 已注入 {0} 个变量（进程作用域，不写 Windows 全局）" -f $loaded)

$required = @(
    'DOCQA_DB_PASSWORD','DOCQA_APP_DB_PASSWORD','DOCQA_MIGRATOR_DB_PASSWORD',
    'DOCQA_AUDIT_DB_PASSWORD','DOCQA_REDIS_PASSWORD','DOCQA_PG_PASSWORD',
    'DOCQA_MINIO_ACCESS_KEY','DOCQA_MINIO_SECRET_KEY','DOCQA_JWT_SECRET',
    'DOCQA_DEMO_PASSWORD_HASH','DOCQA_DB_PORT','DOCQA_ROCKETMQ_NAMESRV_PORT',
    'DOCQA_AI_PORT'
)
$missing = @($required | Where-Object { -not (Get-Conf $_) })
if ($missing.Count -gt 0) { throw ("local.env 缺少必需项：{0}" -f ($missing -join ', ')) }

$aiPort = [int](Get-Conf 'DOCQA_AI_PORT')
# 与 frontend-vue/vite.config.ts 的 server.port 同源；改一处必须改另一处。
$webPort = 5173

# -----------------------------------------------------------------------------
# 1.5 预检两处「启动不报错、用的时候才塌」的配置
# -----------------------------------------------------------------------------
# 占位符 key 过得了 Settings（它只校验类型），OpenAIPlanner 要到生成那一步才带 401 出去，
# 而网关把所有上游异常统一塌成 AGT-UPSTREAM-001，日志里看不出是凭据问题。
# 这里把它压回启动期：显式降级到 offline-extractive（项目自带的无凭证演示路径），
# 让本地问答仍然给得出答案，而不是给得出 503。
$llmOfflineFallback = $false
if ((Get-Conf 'DOCQA_AI_LLM_PROVIDER') -eq 'openai') {
    $llmKey = Get-Conf 'DOCQA_AI_LLM_API_KEY'
    if (-not $llmKey -or $llmKey.StartsWith('CHANGE_ME')) {
        $llmOfflineFallback = $true
        Write-Host '   注意：DOCQA_AI_LLM_PROVIDER=openai，但 DOCQA_AI_LLM_API_KEY 仍是占位符。'
        Write-Host '         本次运行把数据面生成降级为 offline-extractive（抽取式，不调外部模型）。'
        Write-Host '         要真实模型回答：填 key 后重跑，或在 local.env 显式设 DOCQA_AI_LLM_PROVIDER=offline。'
    }
}

# 种子开关一旦打开，摘要就必须在启动期可用：DemoUserSeeder 对非 BCrypt 形态和 cost < 12
# 都抛 IllegalStateException，user-service 会在 [6/8] 直接起不来，现象像「构建坏了」。
if ((Get-Conf 'DOCQA_DEMO_USER_ENABLED') -eq 'true') {
    $demoHash = Get-Conf 'DOCQA_DEMO_PASSWORD_HASH'
    $m = [regex]::Match($demoHash, '^\$2[aby]\$(\d{2})\$[./A-Za-z0-9]{53}$')
    if (-not $m.Success) {
        throw 'DOCQA_DEMO_USER_ENABLED=true 但 DOCQA_DEMO_PASSWORD_HASH 不是合法 BCrypt 摘要（模板占位符 CHANGE_ME_BCRYPT_HASH 不算）。'
    }
    if ([int] $m.Groups[1].Value -lt 12) {
        throw ("DOCQA_DEMO_PASSWORD_HASH 的 BCrypt cost={0} 低于种子账号下限 12；请用 cost 12 重新生成摘要。" -f [int] $m.Groups[1].Value)
    }
}

# -----------------------------------------------------------------------------
# 2. Docker Desktop + 基础设施
# -----------------------------------------------------------------------------
Write-Host '==> [2/8] 检查 Docker Desktop 并启动基础设施'
docker info 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Docker Desktop 未运行；请先打开 Docker Desktop，再执行本脚本。' }

# 管道给 docker exec -i 的 SQL 必须是 UTF-8 字节（PS5.1 默认 ASCII 会毁掉中文）
$OutputEncoding = [System.Text.UTF8Encoding]::new($false)
docker compose -f $compose up -d
if ($LASTEXITCODE -ne 0) { throw 'docker compose up 失败；查看上面的输出。' }

# -----------------------------------------------------------------------------
# 3. 等 MySQL 健康
# -----------------------------------------------------------------------------
Write-Host '==> [3/8] 等待 docqa-mysql 就绪'
$deadline = (Get-Date).AddMinutes(3)
do {
    Start-Sleep -Seconds 3
    $health = docker inspect -f '{{.State.Health.Status}}' docqa-mysql 2>$null
} until ($health -eq 'healthy' -or (Get-Date) -ge $deadline)
if ($health -ne 'healthy') { throw 'MySQL 未在 3 分钟内就绪；docker logs docqa-mysql 查看。' }

# -----------------------------------------------------------------------------
# 3.5 预建 MQ topic（旧 WSL 脚本有此步，迁移时遗漏会让 document-service 的
#     MQ 消费 lag 探针查不到 %RETRY% 路由，readiness 一直 DOWN）
#     三个坑与 AgentFlow 同源：
#     1) namesrv 是独立容器，broker 容器内的 127.0.0.1 够不到它，走 docker 网络主机名；
#     2) mqadmin 失败仍返回退出码 0，按 topicRoute 输出有无 brokerDatas 判定；
#     3) broker TCP 健康通过 ≠ 完成首次 namesrv 注册（~30s 周期），带重试窗口。
#     HOME=/tmp：命名卷属主问题让 rocketmq 用户建不了工具日志目录。
# -----------------------------------------------------------------------------
Write-Host '==> [3.5/8] 预建 MQ topic（消费端 readiness 依赖路由存在）'
$namesrvPort = Get-Conf 'DOCQA_ROCKETMQ_NAMESRV_PORT'
$topics = @('parse-requested', 'parse-completed', 'parse-failed',
            'vector-change-requested', 'vector-change-completed', 'audit-event')
foreach ($t in $topics) {
    $ok = $false
    foreach ($i in 1..18) {
        docker exec -e HOME=/tmp docqa-rmq-broker sh -c "sh mqadmin updateTopic -n rocketmq-namesrv:$namesrvPort -c DefaultCluster -t $t" *> $null
        $route = docker exec -e HOME=/tmp docqa-rmq-broker sh -c "sh mqadmin topicRoute -t $t -n rocketmq-namesrv:$namesrvPort" 2>$null
        if ($route -match 'brokerDatas') { $ok = $true; break }
        Start-Sleep -Seconds 5
    }
    if (-not $ok) { throw "预建 topic $t 失败：90s 内 namesrv 始终查不到路由（docker logs docqa-rmq-broker）" }
}

# -----------------------------------------------------------------------------
# 4. 幂等重放最小权限授权（复用 ops/sql/least-privilege.sql.tmpl）
#    账号 host 必须是 '%'：Windows 侧经端口映射连入时，MySQL 看到的是 bridge 网关地址，
#    不是 localhost。模板只做加法（CREATE USER IF NOT EXISTS + GRANT），可重复执行。
#    口令走容器自带的 MYSQL_ROOT_PASSWORD，不写进命令行：-p 形式的口令在
#    docker top / 容器内 ps 里是明文可见的。
# -----------------------------------------------------------------------------
$accountHost = '%'
if (-not $SkipGrants) {
    Write-Host '==> [4/8] 幂等重放最小权限授权'
    $tplPath = Join-Path $root 'ops/sql/least-privilege.sql.tmpl'
    $sql = Get-Content -LiteralPath $tplPath -Raw -Encoding UTF8
    $sql = $sql.Replace('__ACCOUNT_HOST__', $accountHost)
    $sql = $sql.Replace('__MIGRATOR_PASSWORD__', (Get-Conf 'DOCQA_MIGRATOR_DB_PASSWORD'))
    $sql = $sql.Replace('__APP_PASSWORD__',     (Get-Conf 'DOCQA_APP_DB_PASSWORD'))
    $sql = $sql.Replace('__AUDIT_PASSWORD__',   (Get-Conf 'DOCQA_AUDIT_DB_PASSWORD'))
    $sql | docker exec -i docqa-mysql sh -c 'export MYSQL_PWD=$MYSQL_ROOT_PASSWORD; exec mysql --default-character-set=utf8mb4 -N -B -uroot docqa' 2>&1 |
        Select-Object -Last 4 | ForEach-Object { Write-Host "   $_" }
    if ($LASTEXITCODE -ne 0) { throw '授权重放失败；确认 docqa 库与 flyway_schema_history 表已导入。' }
} else {
    Write-Host '==> [4/8] 跳过授权重放（-SkipGrants）'
}

# 授权是逐表枚举的，固定的失配点是「Flyway 加了表、模板没跟上」。
# 它不会让任何进程起不来，只在运行时以 ERROR 1142 → SQLState 42000 →
# BadSqlGrammarException 的形式出现，日志只剩一行异常类型名。
# audit_relay_cursor 漏授权时，网关 outbox relay 就按 5s 一次的节奏这样空转。
# 所以这里当场对账：库里的每张业务表都必须在 ss_app 的授权面上。
$gapSql = @"
SELECT t.TABLE_NAME
FROM information_schema.TABLES t
LEFT JOIN information_schema.TABLE_PRIVILEGES p
       ON p.TABLE_SCHEMA = t.TABLE_SCHEMA
      AND p.TABLE_NAME = t.TABLE_NAME
      AND p.GRANTEE = '''ss_app''@''$accountHost'''
WHERE t.TABLE_SCHEMA = 'docqa'
  AND t.TABLE_TYPE = 'BASE TABLE'
  AND t.TABLE_NAME <> 'flyway_schema_history'
  AND p.TABLE_NAME IS NULL
ORDER BY t.TABLE_NAME;
"@
$gaps = @(($gapSql | docker exec -i docqa-mysql sh -c 'export MYSQL_PWD=$MYSQL_ROOT_PASSWORD; exec mysql -N -B -uroot docqa' 2>$null |
    ForEach-Object { "$_".Trim() }) | Where-Object { $_ -ne '' -and $_ -notmatch '^mysql: ' })
if ($gaps.Count -gt 0) {
    throw ("未授给 ss_app 的表：{0}；把表名补进 ops/sql/least-privilege.sql.tmpl 与 ops/sql/apply-least-privilege.ps1 的 `$businessTables 后重跑。" -f ($gaps -join ', '))
}

# -----------------------------------------------------------------------------
# 4.5 pgvector 结构：MySQL 侧有 Flyway 账本，PG 侧没有，只能在这里幂等重放。
#     现象：docqa_vec 是空库时数据面 /health/ready 依旧报 pg: ok（它只做 SELECT 1），
#     问答要等检索撞上「表不存在」才塌成 AGT-UPSTREAM-001，日志只留一行 RETRIEVAL_FAILED。
#     WSL → Docker 迁移只恢复了 MySQL 卷，PG 卷是空的，这一步补的就是那次遗漏。
#     005（drop tenant default）故意不跑：它要求 tenant_id 回填已完成，
#     无条件重放会把还没回填的库改坏，那步留给人工执行。
#     SQL 走 docker cp + psql -f，不走管道：002 把 CJK 正则字符类直接写进语句，
#     PowerShell 的编码转换会把字节改掉。迁移脚本本身带 IF NOT EXISTS / COMMENT ON，可重复执行。
#     -U/-d 写死对齐 compose 的 POSTGRES_USER/DB；容器内 psql 走 unix socket，
#     镜像 initdb 默认 trust local，所以这条链路不需要 PG 口令。
# -----------------------------------------------------------------------------
Write-Host '==> [4.5/8] 幂等重放 pgvector 结构（ai-python/migrations 001-004）'
$deadline = (Get-Date).AddMinutes(3)
do {
    Start-Sleep -Seconds 3
    $pgHealth = docker inspect -f '{{.State.Health.Status}}' docqa-pg 2>$null
} until ($pgHealth -eq 'healthy' -or (Get-Date) -ge $deadline)
if ($pgHealth -ne 'healthy') { throw 'docqa-pg 未在 3 分钟内就绪；docker logs docqa-pg 查看。' }

$migrations = Join-Path $root 'ai-python\migrations'
foreach ($f in @('001_chunk_embedding_1024.sql', '002_chunk_lexical_index.sql',
                 '003_chunk_embedding_1024_text_embedding_v4.sql', '004_add_tenant_id.sql')) {
    $src = Join-Path $migrations $f
    if (-not (Test-Path -LiteralPath $src)) { throw "缺少迁移文件 ai-python\migrations\$f" }
    docker cp $src "docqa-pg:/tmp/$f" 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "docker cp 失败：$f" }
    # 重复执行时 psql 会刷一串 "already exists, skipping" NOTICE，成功时不提；
    # 只有真失败才把原文倒出来，否则启动日志里全是噪音。
    $psql = docker exec docqa-pg sh -c "exec psql -U docqa -d docqa_vec -v ON_ERROR_STOP=1 -q -f /tmp/$f" 2>&1
    if ($LASTEXITCODE -ne 0) {
        $psql | ForEach-Object { Write-Host ("   " + $_) }
        throw "pgvector 迁移失败：$f"
    }
}
$vecTables = docker exec docqa-pg psql -U docqa -d docqa_vec -tA -c "SELECT count(*) FROM information_schema.tables WHERE table_schema='public';" 2>$null
Write-Host ("   public schema 现有 {0} 张表" -f ($vecTables -join '').Trim())

# 结构在位 ≠ 向量在位：写入走 parse / vector worker，而 worker 依赖 librocketmq 的 Linux 绑定，
# Windows 侧起不来。空表只会让检索返回 0 条，不会报错，所以这里显式提示一次。
Write-Host '   提示：本步不重建向量。文档向量需由 Linux 侧 worker 写入，否则问答检索恒为 0 条。'

if ($InfraOnly) {
    Write-Host ''
    Write-Host 'DocQA 基础设施已启动（127.0.0.1 专属端口段，可与其他本地项目并行）：'
    Write-Host ("  MySQL {0} / PG {1} / Redis {2} / MinIO {3} / RMQ {4},{5}" -f `
        (Get-Conf 'DOCQA_DB_PORT'), (Get-Conf 'DOCQA_PG_PORT'), (Get-Conf 'DOCQA_REDIS_PORT'), `
        (Get-Conf 'DOCQA_MINIO_PORT'), (Get-Conf 'DOCQA_ROCKETMQ_NAMESRV_PORT'), (Get-Conf 'DOCQA_ROCKETMQ_BROKER_PORT'))
    return
}

# -----------------------------------------------------------------------------
# 5. 停旧进程 + 构建
# -----------------------------------------------------------------------------
Write-Host '==> [5/8] 停掉在跑的 DocQA 服务并构建'
& (Join-Path $PSScriptRoot 'stop-dev.ps1') -Quiet
Get-ChildItem -Path (Join-Path $backend '*/target') -Filter '*.jar.original' -Recurse -ErrorAction SilentlyContinue |
    ForEach-Object { try { [System.IO.File]::Delete($_.FullName) } catch { } }

if (-not $NoBuild) {
    Push-Location $backend
    mvn -q '-DskipTests' '-Dcheckstyle.skip=true' package
    $rc = $LASTEXITCODE
    Pop-Location
    if ($rc -ne 0) { throw 'mvn package 失败。' }
} else {
    Write-Host '   跳过构建（-NoBuild）'
}

# -----------------------------------------------------------------------------
# 6. 起 Java 服务（保持 Windows 原生进程）
# -----------------------------------------------------------------------------
$javaServices = @(
    @{ Name = 'user-service';     Port = 18081 },
    @{ Name = 'document-service'; Port = 18082 },
    @{ Name = 'reminder-service'; Port = 18083 },
    @{ Name = 'audit-service';    Port = 0 },
    @{ Name = 'agent-gateway';    Port = 18080 }
)
foreach ($svc in $javaServices) {
    $dir = Join-Path $backend (Join-Path $svc.Name 'target')
    $jar = Get-ChildItem -Path $dir -Filter ($svc.Name + '-*.jar') -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch 'sources|tests|original' } | Select-Object -First 1
    if (-not $jar) { throw ("未找到 {0} 的 jar；请先构建。" -f $svc.Name) }
    $argList = @('-jar', $jar.FullName)
    if ($svc.Port -gt 0) { $argList += ('--server.port={0}' -f $svc.Port) }
    $label = if ($svc.Port -gt 0) { ':' + $svc.Port } else { '(no web port)' }
    Write-Host ("==> [6/8] 启动 {0} {1}" -f $svc.Name, $label)
    Start-Process -FilePath 'java' -WorkingDirectory $backend -ArgumentList $argList `
        -RedirectStandardOutput (Join-Path $logs ($svc.Name + '.out.log')) `
        -RedirectStandardError  (Join-Path $logs ($svc.Name + '.err.log')) -WindowStyle Hidden
}

$svcPort = @{}
foreach ($svc in $javaServices) { $svcPort[$svc.Name] = $svc.Port }

# -----------------------------------------------------------------------------
# 7. Python 数据面 API
#    入口用 `python -m docqa_ai.api.main`，不是 `-m uvicorn docqa_ai.api.main:app`：
#    前者的 main() 会切 WindowsSelectorEventLoopPolicy，后者绕过它，psycopg 异步连接
#    在 Proactor 循环上直接被拒，PGVector 检索恒定 503。
#    解析 / 向量 Worker 不在这里起：rocketmq-client-python 只有 Linux 的 librocketmq C 绑定。
# -----------------------------------------------------------------------------
Write-Host ("==> [7/8] 启动 Python 数据面 API :{0}" -f $aiPort)
$ai = Join-Path $root 'ai-python'
$py = Join-Path $ai '.venv\Scripts\python.exe'
if (-not (Test-Path -LiteralPath $py)) {
    throw ("未找到 {0}；请先在 ai-python 下执行 python -m venv .venv 并按 README.md 安装依赖。" -f $py)
}
# 数据面的生产判定「默认最严」：没有任何环境信号就要求 DOCQA_AI_INTERNAL_TOKEN，缺失即 fail-fast。
# 本地 dev 形态是两端都不启用这层内部鉴权（见 agent-gateway 的 WebClientConfiguration），
# 所以只给这个子进程声明 dev，且取值必须是 dev/test/prod 之一（Settings 用 Literal 校验）。
$env:DOCQA_AI_ENVIRONMENT = 'dev'
if ($llmOfflineFallback) { $env:DOCQA_AI_LLM_PROVIDER = 'offline' }
Start-Process -FilePath $py -ArgumentList @('-u', '-m', 'docqa_ai.api.main') -WorkingDirectory $ai `
    -RedirectStandardOutput (Join-Path $logs 'ai-api.out.log') `
    -RedirectStandardError  (Join-Path $logs 'ai-api.err.log') -WindowStyle Hidden
$env:DOCQA_AI_ENVIRONMENT = $null
if ($llmOfflineFallback) { $env:DOCQA_AI_LLM_PROVIDER = $null }

$deadline = (Get-Date).AddSeconds(90)
while ((Get-Date) -lt $deadline -and -not (Test-Port $aiPort)) { Start-Sleep -Milliseconds 700 }
if (-not (Test-Port $aiPort)) { throw "Python 数据面 :$aiPort 未在 90s 内监听；查看 logs\ai-api.err.log。" }

# -----------------------------------------------------------------------------
# 8. 前端 dev server
#    代理目标必须显式覆盖：vite.config.ts 的兜底是 WSL 旧路线的 808x，这里按上面
#    同一张 $javaServices 表生成，两处不会漂移；漏一条前缀不是报错而是静默降级成 SPA fallback。
#    直接 node 跑仓库内的 vite.js 而不是 npm run dev：命令行里带仓库绝对路径，
#    stop-dev.ps1 才能按归属证据收掉它（cmd/npm 包装层的命令行里没有这个路径）。
# -----------------------------------------------------------------------------
Write-Host ("==> [8/8] 启动前端 dev server :{0}" -f $webPort)
$web = Join-Path $root 'frontend-vue'
$viteJs = Join-Path $web 'node_modules\vite\bin\vite.js'
if (-not (Test-Path -LiteralPath $viteJs)) {
    Write-Host '   首次运行：npm install（可能需要几分钟）'
    Push-Location $web
    npm install --no-audit --no-fund 2>&1 | Select-Object -Last 3 | ForEach-Object { Write-Host "   $_" }
    $rc = $LASTEXITCODE
    Pop-Location
    if ($rc -ne 0 -or -not (Test-Path -LiteralPath $viteJs)) { throw 'npm install 失败；查看上面的输出。' }
}
$node = (Get-Command node -ErrorAction SilentlyContinue).Source
if (-not $node) { throw '未找到 node；请安装 Node.js 22+ 并确保它在 PATH 中。' }
$env:VITE_PROXY_AUTH_URL     = ('http://127.0.0.1:{0}' -f $svcPort['user-service'])
$env:VITE_PROXY_DOCUMENT_URL = ('http://127.0.0.1:{0}' -f $svcPort['document-service'])
$env:VITE_PROXY_REMINDER_URL = ('http://127.0.0.1:{0}' -f $svcPort['reminder-service'])
$env:VITE_PROXY_GATEWAY_URL  = ('http://127.0.0.1:{0}' -f $svcPort['agent-gateway'])
$env:VITE_MINIO_URL          = ('http://127.0.0.1:{0}' -f (Get-Conf 'DOCQA_MINIO_PORT'))
Start-Process -FilePath $node -ArgumentList @($viteJs, '--host', '127.0.0.1') -WorkingDirectory $web `
    -RedirectStandardOutput (Join-Path $logs 'frontend.out.log') `
    -RedirectStandardError  (Join-Path $logs 'frontend.err.log') -WindowStyle Hidden

$deadline = (Get-Date).AddSeconds(60)
while ((Get-Date) -lt $deadline -and -not (Test-Port $webPort)) { Start-Sleep -Milliseconds 500 }
if (-not (Test-Port $webPort)) { throw "前端 :$webPort 未在 60s 内监听；查看 logs\frontend.out.log。" }

Write-Host ''
Write-Host 'DocQA 已启动：'
Write-Host ("  前端          http://127.0.0.1:{0}" -f $webPort)
Write-Host ("  网关          http://127.0.0.1:{0}" -f $svcPort['agent-gateway'])
Write-Host ("  Python 数据面 http://127.0.0.1:{0}" -f $aiPort)
Write-Host ("  MinIO 控制台  http://127.0.0.1:{0}" -f (Get-Conf 'DOCQA_MINIO_CONSOLE_PORT'))
Write-Host ("  基础设施      MySQL {0} / PG {1} / Redis {2} / RMQ {3},{4},{5}" -f `
    (Get-Conf 'DOCQA_DB_PORT'), (Get-Conf 'DOCQA_PG_PORT'), (Get-Conf 'DOCQA_REDIS_PORT'), `
    (Get-Conf 'DOCQA_ROCKETMQ_NAMESRV_PORT'), (Get-Conf 'DOCQA_ROCKETMQ_BROKER_PORT'), `
    (Get-Conf 'DOCQA_ROCKETMQ_VIP_PORT'))
Write-Host '  停止          scripts/stop-dev.ps1         （只停应用进程）'
Write-Host '                scripts/stop-dev.ps1 -Infra （连容器一起停，卷保留）'
if ($llmOfflineFallback) {
    Write-Host '  生成模型      offline-extractive（LLM key 是占位符，本次不调外部模型）'
}
Write-Host '  解析 / 向量 Worker 依赖 librocketmq 的 Linux 绑定，不在 Windows 侧启动；'
Write-Host '                因此本步之后新上传的文档不会写入向量，检索命中数为 0。'
