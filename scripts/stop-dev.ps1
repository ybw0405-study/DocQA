# 停止本仓库启动的全部进程（保留基础设施容器；加 -Infra 连容器一起 down，卷保留）。
#
# 为什么用 Get-CimInstance 而不是 Get-Process：
#   PS 5.1 的 System.Diagnostics.Process 没有 CommandLine 属性，旧脚本会「成功返回」
#   却一个进程都没停，jar 一直被占用，下一次 mvn package 改名失败、整个启动中断。
#
# 为什么按「仓库路径」而不是按模块名 / java.exe 全量匹配：
#   同机可能还有其它本地项目跑着 java.exe 与同名 gateway jar。按模块名或按任意
#   java.exe 匹配会把别的项目一起杀掉。这里只认三种归属证据：
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

# 函数返回会把单元素数组解包成标量，$null.Count 让下面的计数与判空全部失真，调用点必须再套 @()。
$targets = @(Get-RepoProcesses)
$failed = @()
foreach ($p in $targets) {
    if (-not $Quiet) { Write-Host ("  stop {0,-16} pid={1}" -f $p.Name, $p.ProcessId) }
    # 不能用 SilentlyContinue：非提权 shell 杀不掉提权进程，「拒绝访问」被吞掉就成了假成功。
    try { Stop-Process -Id $p.ProcessId -Force -ErrorAction Stop }
    catch { $failed += $p.ProcessId }
}

# 提权进程的命令行对非提权 shell 不可读，Test-OwnedByRepo 因此一个都匹配不上。
# 先分清「看不见」和「真没跑」，否则结尾会误报 nothing running.
$invisible = @(Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
    Where-Object {
        $_.Name -in @('java.exe', 'python.exe', 'node.exe', 'cmd.exe') -and
        [string]::IsNullOrWhiteSpace($_.CommandLine)
    })

# 杀进程到句柄真正释放之间有窗口；轮询确认，最多 10s，不做无意义的固定 sleep。
$deadline = (Get-Date).AddSeconds(10)
while ((Get-Date) -lt $deadline) {
    if (@(Get-RepoProcesses).Count -eq 0) { break }
    Start-Sleep -Milliseconds 300
}

if ($Infra) {
    # compose 的端口映射依赖 local.env 插值；不带 --env-file 时变量为空，
    # config 解析直接报错，down 会静默失败、容器继续跑。
    $envFile = Join-Path $root 'ops/env/local.env'
    if (Test-Path -LiteralPath $envFile) {
        docker compose --env-file $envFile -f (Join-Path $root 'ops/docker-compose.infra.yml') down
    } else {
        docker compose -f (Join-Path $root 'ops/docker-compose.infra.yml') down
    }
}
if (-not $Quiet) {
    if ($failed.Count -gt 0) {
        Write-Host ("stopped {0}, 但杀不掉 {1} 个 (pid={2})。" -f `
            ($targets.Count - $failed.Count), $failed.Count, ($failed -join ','))
    } elseif ($targets.Count -eq 0) {
        if ($invisible.Count -gt 0) {
            # 归属证据（命令行）在提权进程上不可得，这里不能报「没在跑」。
            Write-Host ("没找到可归属到本仓库的进程，但有 {0} 个 java/python/node/cmd 进程的命令行读不到。" -f $invisible.Count)
            Write-Host '   它们多半由管理员 PowerShell 启动；请在同一个管理员窗口里重跑本脚本，否则端口会一直被占着。'
        } else {
            Write-Host 'nothing running.'
        }
    } else {
        Write-Host ("stopped ({0} processes)." -f $targets.Count)
    }
}
