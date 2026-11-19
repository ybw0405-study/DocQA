# 停止本仓库启动的全部进程（保留基础设施容器；加 -Infra 连容器一起 down，卷保留）。
#
# 为什么用 Get-CimInstance 而不是 Get-Process：
#   PS 5.1 的 System.Diagnostics.Process 没有 CommandLine 属性，旧脚本会「成功返回」
#   却一个进程都没停，jar 一直被占用，下一次 mvn package 改名失败、整个启动中断。
#
# 为什么按「仓库路径」而不是按模块名 / java.exe 全量匹配：
#   同机还有 AgentFlow 跑着 java.exe 与 agent-gateway-0.1.0.jar。按模块名或按任意
#   java.exe 匹配会把另一个项目一起杀掉（2026-11-18 事故）。这里只认三种归属证据：
#   解释器/脚本路径在仓库内、绝对 jar 路径在仓库内、相对 jar 路径能在仓库根下解析到真实文件。
param(
    [switch]$Infra,
    [switch]$Quiet
)
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path

function Test-OwnedByRepo([string]$cmd) {
    if ([string]::IsNullOrWhiteSpace($cmd)) { return $false }
    if ($cmd.IndexOf($root, [StringComparison]::OrdinalIgnoreCase) -ge 0) { return $true }
    $m = [regex]::Match($cmd, '-jar\s+"?([^"\s]+\.jar)')
    if (-not $m.Success) { return $false }
    $jar = $m.Groups[1].Value
    if ([System.IO.Path]::IsPathRooted($jar)) {
        return $jar.StartsWith($root, [StringComparison]::OrdinalIgnoreCase)
    }
    $backend = Join-Path $root 'backend-java'
    $p1 = Join-Path $root $jar
    $p2 = Join-Path $backend $jar
    return (Test-Path -LiteralPath $p1) -or (Test-Path -LiteralPath $p2)
}

function Get-RepoProcesses {
    @(Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
        Where-Object {
            $_.Name -in @('java.exe', 'python.exe', 'node.exe', 'cmd.exe') -and
            (Test-OwnedByRepo $_.CommandLine)
        })
}

$targets = Get-RepoProcesses
foreach ($p in $targets) {
    if (-not $Quiet) { Write-Host ("  stop {0,-16} pid={1}" -f $p.Name, $p.ProcessId) }
    Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue
}

# 杀进程到句柄真正释放之间有窗口；轮询确认，最多 10s，不做无意义的固定 sleep。
$deadline = (Get-Date).AddSeconds(10)
while ((Get-Date) -lt $deadline) {
    if ((Get-RepoProcesses).Count -eq 0) { break }
    Start-Sleep -Milliseconds 300
}

if ($Infra) {
    docker compose -f (Join-Path $root 'ops/docker-compose.infra.yml') down
}
if (-not $Quiet) {
    if ($targets.Count -eq 0) { Write-Host 'nothing running.' }
    else { Write-Host ("stopped ({0} processes)." -f $targets.Count) }
}
