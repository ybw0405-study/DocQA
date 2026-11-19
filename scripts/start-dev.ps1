# DocQA 一键启动（Windows PowerShell 5.1+）
#
# 顺序：读 ops/env/local.env 注入进程作用域 → docker compose up → 等 MySQL 健康
#       → 幂等重放最小权限授权 → mvn package → 起 5 个 Java 服务
# 停止：scripts/stop-dev.ps1（-Infra 连容器一起 down，卷保留）
#
# 与 AgentFlow 的 scripts/start-dev.ps1 同构：配置只在进程作用域，不写 Windows 全局；
# 中间件全在 Docker Desktop bridge，端点固定 127.0.0.1，因此不需要开机自启、不需要刷 IP。
# 旧路径 docs/00-infra/{start-local,run-local,prepare-local-stack}.ps1 保留作 legacy 回滚。
[CmdletBinding()]
param(
    [switch]$NoBuild,     # 跳过 mvn package（jar 已是最新时使用）
    [switch]$InfraOnly,   # 只起中间件，不构建、不起 Java 服务
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
Write-Host ("==> [1/6] 已注入 {0} 个变量（进程作用域，不写 Windows 全局）" -f $loaded)

$required = @(
    'DOCQA_DB_PASSWORD','DOCQA_APP_DB_PASSWORD','DOCQA_MIGRATOR_DB_PASSWORD',
    'DOCQA_AUDIT_DB_PASSWORD','DOCQA_REDIS_PASSWORD','DOCQA_PG_PASSWORD',
    'DOCQA_MINIO_ACCESS_KEY','DOCQA_MINIO_SECRET_KEY','DOCQA_JWT_SECRET',
    'DOCQA_DEMO_PASSWORD_HASH','DOCQA_DB_PORT','DOCQA_ROCKETMQ_NAMESRV_PORT'
)
$missing = @($required | Where-Object { -not (Get-Conf $_) })
if ($missing.Count -gt 0) { throw ("local.env 缺少必需项：{0}" -f ($missing -join ', ')) }

# -----------------------------------------------------------------------------
# 2. Docker Desktop + 基础设施
# -----------------------------------------------------------------------------
Write-Host '==> [2/6] 检查 Docker Desktop 并启动基础设施'
docker info 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Docker Desktop 未运行；请先打开 Docker Desktop，再执行本脚本。' }

# 管道给 docker exec -i 的 SQL 必须是 UTF-8 字节（PS5.1 默认 ASCII 会毁掉中文）
$OutputEncoding = [System.Text.UTF8Encoding]::new($false)
docker compose -f $compose up -d
if ($LASTEXITCODE -ne 0) { throw 'docker compose up 失败；查看上面的输出。' }

# -----------------------------------------------------------------------------
# 3. 等 MySQL 健康
# -----------------------------------------------------------------------------
Write-Host '==> [3/6] 等待 docqa-mysql 就绪'
$deadline = (Get-Date).AddMinutes(3)
do {
    Start-Sleep -Seconds 3
    $health = docker inspect -f '{{.State.Health.Status}}' docqa-mysql 2>$null
} until ($health -eq 'healthy' -or (Get-Date) -ge $deadline)
if ($health -ne 'healthy') { throw 'MySQL 未在 3 分钟内就绪；docker logs docqa-mysql 查看。' }

# -----------------------------------------------------------------------------
# 4. 幂等重放最小权限授权（复用 ops/sql/least-privilege.sql.tmpl）
#    账号 host 必须是 '%'：Windows 侧经端口映射连入时，MySQL 看到的是 bridge 网关地址，
#    不是 localhost。模板只做加法（CREATE USER IF NOT EXISTS + GRANT），可重复执行。
# -----------------------------------------------------------------------------
if (-not $SkipGrants) {
    Write-Host '==> [4/6] 幂等重放最小权限授权'
    $tplPath = Join-Path $root 'ops/sql/least-privilege.sql.tmpl'
    $sql = Get-Content -LiteralPath $tplPath -Raw -Encoding UTF8
    $sql = $sql.Replace('__ACCOUNT_HOST__', '%')
    $sql = $sql.Replace('__MIGRATOR_PASSWORD__', (Get-Conf 'DOCQA_MIGRATOR_DB_PASSWORD'))
    $sql = $sql.Replace('__APP_PASSWORD__',     (Get-Conf 'DOCQA_APP_DB_PASSWORD'))
    $sql = $sql.Replace('__AUDIT_PASSWORD__',   (Get-Conf 'DOCQA_AUDIT_DB_PASSWORD'))
    $rootPwd = Get-Conf 'DOCQA_DB_PASSWORD'
    $sql | docker exec -i docqa-mysql mysql --default-character-set=utf8mb4 -uroot -p"$rootPwd" docqa 2>&1 |
        Select-Object -Last 4 | ForEach-Object { Write-Host "   $_" }
    if ($LASTEXITCODE -ne 0) { throw '授权重放失败；确认 docqa 库与 flyway_schema_history 表已导入。' }
} else {
    Write-Host '==> [4/6] 跳过授权重放（-SkipGrants）'
}

if ($InfraOnly) {
    Write-Host ''
    Write-Host 'DocQA 基础设施已启动（127.0.0.1 专属端口段，可与 AgentFlow 同时运行）：'
    Write-Host ("  MySQL {0} / PG {1} / Redis {2} / MinIO {3} / RMQ {4},{5}" -f `
        (Get-Conf 'DOCQA_DB_PORT'), (Get-Conf 'DOCQA_PG_PORT'), (Get-Conf 'DOCQA_REDIS_PORT'), `
        (Get-Conf 'DOCQA_MINIO_PORT'), (Get-Conf 'DOCQA_ROCKETMQ_NAMESRV_PORT'), (Get-Conf 'DOCQA_ROCKETMQ_BROKER_PORT'))
    return
}

# -----------------------------------------------------------------------------
# 5. 停旧进程 + 构建
# -----------------------------------------------------------------------------
Write-Host '==> [5/6] 停掉在跑的 DocQA 服务并构建'
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
# 6. 起 Java 服务（保持 Windows 原生，与 AgentFlow 一致）
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
    Write-Host ("==> [6/6] 启动 {0} {1}" -f $svc.Name, $label)
    Start-Process -FilePath 'java' -WorkingDirectory $backend -ArgumentList $argList `
        -RedirectStandardOutput (Join-Path $logs ($svc.Name + '.out.log')) `
        -RedirectStandardError  (Join-Path $logs ($svc.Name + '.err.log')) -WindowStyle Hidden
}

Write-Host ''
Write-Host 'DocQA 已启动：'
Write-Host '  网关          http://127.0.0.1:18080'
Write-Host '  MinIO 控制台  http://127.0.0.1:19101'
Write-Host '  基础设施      MySQL 13306 / PG 15432 / Redis 16380 / RMQ 19876,20911,20909'
Write-Host '  停止          scripts/stop-dev.ps1         （只停应用进程）'
Write-Host '                scripts/stop-dev.ps1 -Infra （连容器一起停，卷保留）'
Write-Host '  Python 数据面 / 前端仍按 docs/00-infra/getting-started.md 单独启动。'
