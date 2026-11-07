<#
    DocQA local stack orchestrator (Windows side).

    Responsibilities, in order:
      1. Wake the WSL2 distro so systemd can bring up the middleware + workers.
      2. Read the current WSL eth0 IP and rewrite every DOCQA_* endpoint that
         still points at the previous IP. WSL2 NAT addresses change on reboot, so
         hard-coded addresses are the single most common "it worked yesterday" bug.
      3. Optionally start the Java control plane, Python API and Vite dev server.

    Autostart registration (current user, at logon, no admin required):
      powershell -File docs\00-infra\start-local.ps1 -InstallAutostart
      powershell -File docs\00-infra\start-local.ps1 -InstallAutostart -IncludeApp -PortOffset 10000
      powershell -File docs\00-infra\start-local.ps1 -UninstallAutostart

    This file is intentionally ASCII-only: Windows PowerShell 5.1 reads a UTF-8
    file without BOM as ANSI and would garble non-ASCII string literals.
#>
[CmdletBinding()]
param(
    [switch]$InstallAutostart,
    [switch]$UninstallAutostart,
    [switch]$IncludeApp,
    [switch]$SyncEnvOnly,
    [int]$PortOffset = 0,
    [string]$RedisHost = '',
    [int]$WaitSec = 180
)

$ErrorActionPreference = 'Stop'

$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$ScriptPath = Join-Path $PSScriptRoot 'start-local.ps1'
$TaskName = 'DocQA Local Stack'
$StateDir = Join-Path $env:LOCALAPPDATA 'DocQA'
$LogFile = Join-Path $StateDir 'startup.log'
$IpMarker = Join-Path $StateDir 'wsl-ip.txt'
$Units = @('docqa-infra.service', 'docqa-parse-worker.service', 'docqa-vector-worker.service')

if (-not (Test-Path $StateDir)) { New-Item -ItemType Directory -Force -Path $StateDir | Out-Null }
if ($PortOffset -lt 0 -or (8083 + $PortOffset) -gt 65000) { throw 'PortOffset out of range.' }

function Write-Log([string]$Message) {
    $line = '{0}  {1}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $Message
    Add-Content -Path $LogFile -Value $line -Encoding UTF8
    Write-Output $line
}

function Invoke-Wsl([string]$BashCommand) {
    $out = & wsl.exe -u root -e bash -lc $BashCommand 2>$null
    return (($out -join "`n").Trim())
}

function Test-TcpPort([int]$Port) {
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $pending = $client.BeginConnect('127.0.0.1', $Port, $null, $null)
        return ($pending.AsyncWaitHandle.WaitOne(1200, $false) -and $client.Connected)
    }
    catch { return $false }
    finally { $client.Close() }
}

function Convert-ToWslPath([string]$WindowsPath) {
    $p = $WindowsPath -replace '\\', '/'
    if ($p -match '^([A-Za-z]):/(.*)$') {
        return '/mnt/' + $Matches[1].ToLowerInvariant() + '/' + $Matches[2]
    }
    return $p
}

$KeepAliveMarker = 'sleep 2147483647'

function Ensure-WslKeepAlive {
    # WSL2 stops the distro when the last attached session exits. On this machine a
    # systemd unit started by a short-lived `wsl -e` call gets SIGTERM mid-boot
    # (verified via journalctl --list-boots: repeated ~60s boots, infra never finishes).
    # A detached, long-lived wsl.exe session anchors the VM. Win32_Process.Create is
    # used instead of Start-Process so the child is NOT in the calling task's process
    # tree and survives after this script exits.
    $existing = Get-CimInstance Win32_Process -Filter "Name='wsl.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -like "*$KeepAliveMarker*" }
    if ($existing) {
        Write-Log ("WSL keep-alive already running (pid {0})" -f (($existing | Select-Object -First 1).ProcessId))
        return
    }
    $cmd = "wsl.exe -u root -e $KeepAliveMarker"
    $result = Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{ CommandLine = $cmd } -ErrorAction SilentlyContinue
    if ($result -and $result.ReturnValue -eq 0) {
        Write-Log ("WSL keep-alive started (pid {0})" -f $result.ProcessId)
    } else {
        Start-Process -FilePath 'wsl.exe' -ArgumentList '-u', 'root', '-e', 'sleep', '2147483647' -WindowStyle Hidden | Out-Null
        Write-Log 'WSL keep-alive started via Start-Process fallback'
    }
}

function Stop-WslKeepAlive {
    $procs = Get-CimInstance Win32_Process -Filter "Name='wsl.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -like "*$KeepAliveMarker*" }
    foreach ($p in $procs) { Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue }
    Write-Log ("WSL keep-alive stopped ({0} process(es))" -f @($procs).Count)
}

function Get-WslIp {
    $raw = Invoke-Wsl 'hostname -I'
    $ip = ($raw -split '\s+')[0]
    if ($ip -notmatch '^\d{1,3}(\.\d{1,3}){3}$') { throw "Cannot resolve WSL IP from: '$raw'" }
    return $ip
}

function Sync-Endpoints([string]$WslIp) {
    # Replace both the previously recorded WSL IP and any address in the WSL2 NAT
    # range 172.16.0.0/12. Never touch 127.0.0.1 or the VM subnet (192.168.x).
    $patterns = @('\b172\.(?:1[6-9]|2\d|3[01])\.\d{1,3}\.\d{1,3}\b')
    if (Test-Path $IpMarker) {
        $old = (Get-Content $IpMarker -Raw).Trim()
        if ($old -match '^\d{1,3}(\.\d{1,3}){3}$') { $patterns += [regex]::Escape($old) }
    }
    $rx = '(' + ($patterns -join '|') + ')'

    $userVars = [Environment]::GetEnvironmentVariables('User')
    $changed = @()
    foreach ($key in $userVars.Keys) {
        if ($key -notlike 'DOCQA_*') { continue }
        $value = [string]$userVars[$key]
        $newValue = [regex]::Replace($value, $rx, $WslIp)
        if ($newValue -ne $value) {
            [Environment]::SetEnvironmentVariable($key, $newValue, 'User')
            $changed += $key
            $value = $newValue
        }
        [Environment]::SetEnvironmentVariable($key, $value, 'Process')
    }

    if ($RedisHost) {
        # Optional: the VM may also be re-addressed by DHCP.
        [Environment]::SetEnvironmentVariable('DOCQA_REDIS_HOST', ($RedisHost -split ':')[0], 'User')
        [Environment]::SetEnvironmentVariable('DOCQA_REDIS_HOST', ($RedisHost -split ':')[0], 'Process')
        $changed += 'DOCQA_REDIS_HOST'
        $dsn = [string][Environment]::GetEnvironmentVariable('DOCQA_AI_REDIS_URL', 'User')
        if ($dsn) {
            $newDsn = [regex]::Replace($dsn, '(?<=://:[^@]*@)[^/]+', $RedisHost)
            [Environment]::SetEnvironmentVariable('DOCQA_AI_REDIS_URL', $newDsn, 'User')
            [Environment]::SetEnvironmentVariable('DOCQA_AI_REDIS_URL', $newDsn, 'Process')
            $changed += 'DOCQA_AI_REDIS_URL'
        }
    }

    Set-Content -Path $IpMarker -Value $WslIp -Encoding ASCII
    if ($changed.Count -gt 0) {
        Write-Log ("Endpoint sync: {0} DOCQA_* keys updated to WSL IP {1}" -f $changed.Count, $WslIp)
    } else {
        Write-Log ("Endpoint sync: already current (WSL IP {0})" -f $WslIp)
    }
}

function Wait-WslStack {
    $deadline = (Get-Date).AddSeconds($WaitSec)
    $check = 'systemctl is-active ' + ($Units -join ' ')
    while ((Get-Date) -lt $deadline) {
        $states = @((Invoke-Wsl $check) -split '\s+')
        if ($states.Count -eq $Units.Count -and -not ($states | Where-Object { $_ -ne 'active' })) {
            Write-Log 'WSL stack: 3/3 units active.'
            return $true
        }
        Start-Sleep -Seconds 3
    }
    Write-Log ("WSL stack: timeout after {0}s; last states = {1}" -f $WaitSec, (Invoke-Wsl $check))
    return $false
}

function Install-LogonTask {
    $argLine = '-NoProfile -NonInteractive -ExecutionPolicy Bypass -WindowStyle Hidden -File "{0}"' -f $ScriptPath
    if ($IncludeApp) { $argLine += ' -IncludeApp' }
    if ($PortOffset -ne 0) { $argLine += (' -PortOffset {0}' -f $PortOffset) }
    if ($RedisHost) { $argLine += (' -RedisHost {0}' -f $RedisHost) }

    $action = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument $argLine
    $trigger = New-ScheduledTaskTrigger -AtLogOn -User ("{0}\{1}" -f $env:USERDOMAIN, $env:USERNAME)
    $principal = New-ScheduledTaskPrincipal -UserId ("{0}\{1}" -f $env:USERDOMAIN, $env:USERNAME) -LogonType Interactive -RunLevel Limited
    $settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable -ExecutionTimeLimit (New-TimeSpan -Minutes 20)
    Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger `
        -Principal $principal -Settings $settings `
        -Description 'Start DocQA WSL middleware and workers, then refresh Windows endpoint variables.' -Force | Out-Null
    Write-Log ("Registered logon task '{0}' -> {1}" -f $TaskName, $argLine)
}

function Uninstall-LogonTask {
    if (Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue) {
        Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false
        Stop-WslKeepAlive
        Write-Log ("Removed logon task '{0}'." -f $TaskName)
    } else {
        Write-Log 'No logon task registered.'
    }
}

function Start-Apps([string]$WslIp) {
    $gw = 8080 + $PortOffset
    $user = 8081 + $PortOffset
    $doc = 8082 + $PortOffset
    $rem = 8083 + $PortOffset

    Write-Log ("Starting Java services on ports {0}/{1}/{2}/{3}" -f $gw, $user, $doc, $rem)
    $runner = Join-Path $PSScriptRoot 'run-local.ps1'
    & $runner -Action start -PortOffset $PortOffset

    # A single module can fail to bind (Flyway validation, port race, upstream not
    # ready yet). run-local.ps1 reports a timeout but does not fail the script, so
    # detect it here and retry just the missing modules once before continuing.
    $checks = [ordered]@{
        'user-service'     = $user
        'document-service' = $doc
        'reminder-service' = $rem
        'agent-gateway'    = $gw
    }
    $down = @($checks.Keys | Where-Object { -not (Test-TcpPort $checks[$_]) })
    if ($down.Count -gt 0) {
        Write-Log ("Readiness check: retrying {0}" -f ($down -join ', '))
        & $runner -Action start -PortOffset $PortOffset -Modules $down -PortTimeoutSec 120 | Out-Null
        $stillDown = @($checks.Keys | Where-Object { -not (Test-TcpPort $checks[$_]) })
        if ($stillDown.Count -gt 0) { Write-Log ("WARNING: still down: {0}" -f ($stillDown -join ', ')) }
        else { Write-Log 'Readiness check: all modules recovered.' }
    } else {
        Write-Log 'Readiness check: 4/4 Java modules listening.'
    }

    Write-Log 'Starting Python data-plane API on :8000'
    & (Join-Path $PSScriptRoot 'run-ai-local.ps1') -Action start -Component api

    [Environment]::SetEnvironmentVariable('VITE_PROXY_GATEWAY_URL', "http://127.0.0.1:$gw", 'Process')
    [Environment]::SetEnvironmentVariable('VITE_PROXY_AUTH_URL', "http://127.0.0.1:$user", 'Process')
    [Environment]::SetEnvironmentVariable('VITE_PROXY_DOCUMENT_URL', "http://127.0.0.1:$doc", 'Process')
    [Environment]::SetEnvironmentVariable('VITE_PROXY_REMINDER_URL', "http://127.0.0.1:$rem", 'Process')
    [Environment]::SetEnvironmentVariable('VITE_MINIO_URL', "http://${WslIp}:9100", 'Process')

    $fe = Join-Path $RepoRoot 'frontend-vue'
    $outLog = Join-Path $StateDir 'vite.out.log'
    $errLog = Join-Path $StateDir 'vite.err.log'

    # Re-runnable: drop a previous dev server owned by this repo before starting a new one.
    $old = Get-CimInstance Win32_Process -Filter "Name='node.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -like "*$fe*" }
    foreach ($p in $old) { Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue }

    # --host 127.0.0.1 is deliberate: Vite 8 otherwise binds ::1 only, which makes
    # http://127.0.0.1:5173 fail even though the server is up.
    Start-Process -FilePath 'cmd.exe' -ArgumentList '/c', 'npm run dev -- --host 127.0.0.1' -WorkingDirectory $fe `
        -WindowStyle Hidden -RedirectStandardOutput $outLog -RedirectStandardError $errLog | Out-Null
    Write-Log ("Started Vite on 127.0.0.1:5173 (proxy -> gateway {0}, MinIO {1}:9100)" -f $gw, $WslIp)
}

# ---------------------------------------------------------------- main ------
if ($UninstallAutostart) { Uninstall-LogonTask; return }
if ($InstallAutostart) { Install-LogonTask; return }
if ($SyncEnvOnly) { $IncludeApp = $false }

Write-Log ("DocQA startup begin. Repo = {0} (WSL: {1})" -f $RepoRoot, (Convert-ToWslPath $RepoRoot))
Ensure-WslKeepAlive         # anchor the WSL VM so systemd units can finish booting
$null = Invoke-Wsl 'true'   # wake the distro; systemd starts the enabled units

$wslIp = Get-WslIp
Sync-Endpoints $wslIp
$ready = Wait-WslStack
if (-not $ready) { Write-Log 'WARNING: proceeding with a partially ready WSL stack.' }

if ($IncludeApp) { Start-Apps $wslIp }
Write-Log 'DocQA startup done.'
