<#
    阶段 6 / S0 Python 数据面本地启动器。
    与 run-local.ps1 同源：只做「导入环境变量 -> 拉起进程 -> 等待端口」三件事，脚本本身不含凭据。
    用法： .\run-ai-local.ps1 -Action start|stop|status [-Component api|worker]
    注意：worker 依赖 rocketmq-client-python（可选 extra），该包只有 Linux 侧的 C 绑定，Windows 上无法加载，
          因此 -Component worker 在 Windows 上会直接失败并提示改用 Linux 容器。
#>
param(
    [ValidateSet('start', 'stop', 'status')]
    [string]$Action = 'start',
    [ValidateSet('api', 'worker')]
    [string]$Component = 'api',
    [int]$Port = 8000,
    [int]$TimeoutSec = 90
)

$ErrorActionPreference = 'Stop'
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$Ai = Join-Path $RepoRoot 'ai-python'
$Py = Join-Path $Ai '.venv\Scripts\python.exe'
$RunDir = Join-Path $env:TEMP 'docqa-runtime'
$LogDir = Join-Path $RunDir 'logs'
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null
if (-not (Test-Path $Py)) { throw "未找到虚拟环境 $Py" }

function Import-DocQAEnv {
    foreach ($scope in @('Machine', 'User')) {
        $vars = [Environment]::GetEnvironmentVariables($scope)
        foreach ($key in $vars.Keys) {
            if ($key -like 'DOCQA_*') {
                [Environment]::SetEnvironmentVariable($key, [string]$vars[$key], 'Process')
            }
        }
    }
    $required = @('DOCQA_AI_PG_DSN', 'DOCQA_AI_REDIS_URL', 'DOCQA_AI_MINIO_ACCESS_KEY', 'DOCQA_AI_MINIO_SECRET_KEY')
    $missing = $required | Where-Object { -not [Environment]::GetEnvironmentVariable($_, 'Process') }
    if ($missing) { throw ("缺少变量: {0}" -f ($missing -join ', ')) }
    Repair-ProcessEnvCase
    "已导入 DOCQA_* 变量，Python 侧必需项齐全"
}

<#
    与 run-local.ps1 同因同解：Start-Process 的环境字典大小写不敏感，
    HTTPS_PROXY 与 https_proxy 并存会让子进程一个都起不来。
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

function Test-Port([int]$p) {
    try { $c = New-Object System.Net.Sockets.TcpClient; $c.Connect('127.0.0.1', $p); $c.Close(); return $true } catch { return $false }
}

function Stop-Component {
    $procs = Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match 'python' -and $_.CommandLine -match 'docqa_ai|docqa-api|docqa-parse-worker' }
    if (-not $procs) { '没有运行中的 Python 数据面进程'; return }
    foreach ($p in $procs) { Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue; "已停止 pid=$($p.ProcessId)" }
}

switch ($Action) {
    'stop' { Stop-Component }
    'status' { "{0} :{1} {2}" -f $Component, $Port, $(if (Test-Port $Port) { 'LISTEN' } else { 'DOWN' }) }
    'start' {
        # 幂等：重复执行时先收掉上一个同组件进程，否则新进程会因端口占用静默失败，
        # 而旧进程仍占着端口让 readiness 探测误判为「启动成功」。
        if ($Component -eq 'api' -and (Test-Port $Port)) { Stop-Component; Start-Sleep -Seconds 1 }
        if ($Component -eq 'worker') {
            & $Py -c "import rocketmq" 2>$null
            if ($LASTEXITCODE -ne 0) {
                throw "rocketmq-client-python 未安装；该可选 extra 依赖 Linux 的 librocketmq C 绑定，Windows 无法运行解析 Worker，请在 Linux 容器中执行。"
            }
        }
        Import-DocQAEnv
        $log = Join-Path $LogDir "ai-$Component.log"
        # 用 `python -m <模块>` 而不是 .venv\Scripts\*.exe 控制台包装：
        # ① 包装 exe 会被应用执行控制策略拦（实测 "An Application Control policy has blocked this file"），
        #    而 venv 里的 python.exe 本身可以执行；
        # ② 模块入口与 pyproject 的 [project.scripts] 指向同一个函数，语义完全一致。
        if ($Component -eq 'api') {
            $moduleArgs = @('-u', '-m', 'docqa_ai.api.main')
        } else {
            $moduleArgs = @('-u', '-m', 'docqa_ai.parse_worker.worker')
        }
        $p = Start-Process -FilePath $Py -ArgumentList $moduleArgs -WorkingDirectory $Ai `
            -RedirectStandardOutput $log -RedirectStandardError (Join-Path $LogDir "ai-$Component.err.log") `
            -WindowStyle Hidden -PassThru
        "启动 ai-$Component pid=$($p.Id) 日志 $log"
        $deadline = (Get-Date).AddSeconds($TimeoutSec)
        while ((Get-Date) -lt $deadline -and -not (Test-Port $Port)) { Start-Sleep -Milliseconds 700 }
        if (Test-Port $Port) { "就绪 ai-$Component :$Port" } else { "超时：:$Port 未监听，见 $log" }
    }
}
