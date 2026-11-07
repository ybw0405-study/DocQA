<#
    本地运行时启动器（阶段 6 / S0）。
    作用：把 Machine + User 两级 DOCQA_* 环境变量导入当前进程，再以 jar 方式拉起后端服务。
    说明：服务只从环境变量读取配置，脚本本身不含任何凭据；缺失变量时直接报错而不是回退默认值。
#>
param(
    [ValidateSet('start', 'stop', 'status')]
    [string]$Action = 'start',
    [string[]]$Modules = @('user-service', 'document-service', 'reminder-service', 'audit-service', 'agent-gateway'),
    [int]$PortTimeoutSec = 90,
    # 本机 8080/8081 被其它常驻程序占用时，整体平移端口（例如 10000 -> 18080..18083）。
    # 网关的上游地址用 DOCQA_*_SERVICE_URI 同步覆盖，见 application.yml。
    [int]$PortOffset = 0
)

$ErrorActionPreference = 'Stop'
# 控制台按 UTF-8 输出，避免中文提示在管道里被按 GBK 解码
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

# 端口表：audit-service 为 web-application-type: none，不监听端口
$PortMap = @{
    'user-service'     = 8081
    'document-service' = 8082
    'reminder-service' = 8083
    'audit-service'    = 0
    'agent-gateway'    = 8080
}

if ($PortOffset -ne 0) {
    if ($PortOffset -lt 0 -or (8083 + $PortOffset) -gt 65000) { throw 'PortOffset 超出可用范围' }
    foreach ($name in @($PortMap.Keys)) {
        if ($PortMap[$name] -gt 0) { $PortMap[$name] += $PortOffset }
    }
    $upstream = @{
        'DOCQA_USER_SERVICE_URI'     = $PortMap['user-service']
        'DOCQA_DOCUMENT_SERVICE_URI' = $PortMap['document-service']
        'DOCQA_REMINDER_SERVICE_URI' = $PortMap['reminder-service']
    }
    foreach ($key in $upstream.Keys) {
        [Environment]::SetEnvironmentVariable($key, "http://127.0.0.1:$($upstream[$key])", 'Process')
    }
}

$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$Backend = Join-Path $RepoRoot 'backend-java'
$RunDir = Join-Path $env:TEMP 'docqa-runtime'
$LogDir = Join-Path $RunDir 'logs'
$PidFile = Join-Path $RunDir 'pids.json'
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null

function Import-DocQAEnv {
    $loaded = @()
    foreach ($scope in @('Machine', 'User')) {
        $vars = [Environment]::GetEnvironmentVariables($scope)
        foreach ($key in $vars.Keys) {
            if ($key -like 'DOCQA_*') {
                [Environment]::SetEnvironmentVariable($key, [string]$vars[$key], 'Process')
                $loaded += $key
            }
        }
    }
    # 启动前必需项：任一为空即中止，避免半启动状态下排查困难
    $required = @(
        'DOCQA_JWT_SECRET', 'DOCQA_DB_PASSWORD', 'DOCQA_REDIS_PASSWORD',
        'DOCQA_REDIS_HOST', 'DOCQA_ROCKETMQ_NAMESRV',
        'DOCQA_MINIO_ENDPOINT', 'DOCQA_MINIO_ACCESS_KEY', 'DOCQA_MINIO_SECRET_KEY',
        'DOCQA_DEMO_PASSWORD_HASH'
    )
    # 本地通过 http://127.0.0.1:8080 访问，Secure Cookie 不会被浏览器回发；只在启动器内降级，生产默认仍为 true。
    if (-not [Environment]::GetEnvironmentVariable('DOCQA_COOKIE_SECURE', 'Process')) {
        [Environment]::SetEnvironmentVariable('DOCQA_COOKIE_SECURE', 'false', 'Process')
        $loaded += 'DOCQA_COOKIE_SECURE'
    }
    $missing = $required | Where-Object { -not [Environment]::GetEnvironmentVariable($_, 'Process') }
    if ($missing) {
        throw ("缺少环境变量: {0}；请先执行 docs/00-infra/prepare-local-stack.sh 并配置 Windows 侧变量" -f ($missing -join ', '))
    }
    Repair-ProcessEnvCase
    "已导入 {0} 个 DOCQA_* 变量，必需项齐全" -f ($loaded | Sort-Object -Unique).Count
}

<#
    Windows 环境块大小写不敏感：当调用方进程里同时存在 HTTPS_PROXY 与 https_proxy（Git Bash 与
    Windows 各写一份是常见来源）时，Start-Process 组装的子进程环境字典会抛
    「已添加项。字典中的关键字:“HTTPS_PROXY”所添加的关键字:“https_proxy”」，服务一个都起不来。
    这里按大小写归并每组，保留首个非空值，再用其中的一种拼写写回。只动重复项，不动值。
#>
function Repair-ProcessEnvCase {
    $vars = [Environment]::GetEnvironmentVariables('Process')
    $dupGroups = $vars.Keys | Group-Object { $_.ToUpperInvariant() } | Where-Object { $_.Count -gt 1 }
    foreach ($group in $dupGroups) {
        $names = @($group.Group)
        $value = $names | ForEach-Object { $vars[$_] } | Where-Object { $_ } | Select-Object -First 1
        foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $null, 'Process') }
        [Environment]::SetEnvironmentVariable([string]$names[0], [string]$value, 'Process')
        "已归并大小写重复的环境变量: {0} -> {1}" -f ($names -join ' / '), $names[0]
    }
}

function Start-Modules {
    Import-DocQAEnv
    $pids = @{}
    if (Test-Path $PidFile) {
        (Get-Content $PidFile -Raw | ConvertFrom-Json).PSObject.Properties | ForEach-Object { $pids[$_.Name] = $_.Value }
    }
    foreach ($m in $Modules) {
        $jar = Get-ChildItem -Path (Join-Path $Backend "$m\target") -Filter "$m-*.jar" -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -notmatch 'sources|tests|original' } | Select-Object -First 1
        if (-not $jar) { throw "未找到 $m 的可执行 jar，请先执行 mvn -DskipTests package" }
        Stop-ModuleProcess -Module $m
        $out = Join-Path $LogDir "$m.out.log"
        $err = Join-Path $LogDir "$m.err.log"
        # 端口以本脚本的 PortMap 为准，显式用 --server.port 传参（命令行参数优先级最高）。
        # 起因：调用方环境里若存在 SERVER__PORT=60911 这类外来变量，Spring 的宽松绑定会把它
        # 当成 server.port，五个服务于是全部去抢同一个端口，日志里表现为「Tomcat initialized with
        # port 60911」+「Port 60911 was already in use」，与 application.yml 写的 8081 完全不符。
        $javaArgs = @('-jar', $jar.FullName)
        $port = $PortMap[$m]
        if ($port -gt 0) { $javaArgs += "--server.port=$port" }
        $p = Start-Process -FilePath 'java' `
            -ArgumentList $javaArgs `
            -WorkingDirectory $Backend `
            -RedirectStandardOutput $out -RedirectStandardError $err `
            -WindowStyle Hidden -PassThru
        $pids[$m] = $p.Id
        "启动 {0} pid={1} -> {2}" -f $m, $p.Id, (Split-Path $jar -Leaf)
    }
    $pids | ConvertTo-Json | Set-Content -Path $PidFile -Encoding ascii

    foreach ($m in $Modules) {
        $port = $PortMap[$m]
        if ($port -gt 0) {
            $deadline = (Get-Date).AddSeconds($PortTimeoutSec)
            $ready = $false
            while ((Get-Date) -lt $deadline) {
                # 探 readiness 而不是裸 TCP：Tomcat 在 refresh() 里就开始收连接，
                # 而 ContextRefreshedEvent 尚未投递——网关代理的 filters 此刻仍未初始化，
                # 打进这个窗口的请求会拿到 500（S11-9 实测：18:42:46 端口开、18:42:47 登录 500）。
                try {
                    $resp = Invoke-WebRequest -Uri "http://127.0.0.1:$port/actuator/health/readiness" -UseBasicParsing -TimeoutSec 3
                    # PS 5.1 的坑：Content-Type 不带 charset 时 Content 是 byte[]，
                    # 直接 -match 永远不匹配，readiness 会被误判为超时（S11-9 实测）。
                    $body = $resp.Content
                    if ($body -is [byte[]]) { $body = [Text.Encoding]::UTF8.GetString($body) }
                    if ($resp.StatusCode -eq 200 -and ([string]$body) -match '"UP"') { $ready = $true; break }
                } catch {
                    $statusCode = 0
                    try { $statusCode = [int]$_.Exception.Response.StatusCode } catch { }
                    if ($statusCode -eq 404) {
                        # 服务没暴露 readiness 端点，退回裸 TCP 口径
                        try {
                            $c = New-Object System.Net.Sockets.TcpClient
                            $c.Connect('127.0.0.1', $port)
                            $c.Close()
                            $ready = $true
                            break
                        } catch { }
                    }
                    Start-Sleep -Milliseconds 800
                }
            }
            if ($ready) { "就绪 {0} :{1}" -f $m, $port } else { "超时 {0} :{1} 未就绪，见 $LogDir\$m.err.log" -f $m, $port }
        }
    }
}

function Stop-ModuleProcess {
    param([string]$Module)
    # javapath 下的 java.exe 只是壳，真实 JVM 的 PID 与 Start-Process 返回的不同，按命令行匹配才可靠
    $procs = Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -match [regex]::Escape("$Module-") -and $_.CommandLine -match '\.jar' }
    if (-not $procs) { return }
    foreach ($p in $procs) {
        Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue
        "已停止 {0} pid={1}" -f $Module, $p.ProcessId
    }
}

function Stop-Modules {
    foreach ($m in $Modules) { Stop-ModuleProcess -Module $m }
    if (Test-Path $PidFile) { Remove-Item $PidFile -Force }
}

function Show-Status {
    foreach ($m in $Modules) {
        $port = $PortMap[$m]
        if ($port -eq 0) { "{0,-18} 无 HTTP 端口" -f $m; continue }
        $listening = $false
        try {
            $c = New-Object System.Net.Sockets.TcpClient
            $c.Connect('127.0.0.1', $port)
            $c.Close()
            $listening = $true
        } catch { }
        "{0,-18} :{1,-6} {2}" -f $m, $port, $(if ($listening) { 'LISTEN' } else { 'DOWN' })
    }
}

switch ($Action) {
    'start' { Start-Modules }
    'stop' { Stop-Modules }
    'status' { Show-Status }
}
