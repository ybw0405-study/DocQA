#requires -Version 5.1
<#
  DocQA - one-shot gate runner (Java / Python / Frontend)

  Usage:
    powershell -File scripts/verify-all.ps1
    powershell -File scripts/verify-all.ps1 -Offline          # local Maven offline
    powershell -File scripts/verify-all.ps1 -SkipFrontend

  Exit code: 0 = all passed, 1 = at least one step failed, 2 = bad usage.
#>
[CmdletBinding()]
param(
    [switch]$SkipJava,
    [switch]$SkipPython,
    [switch]$SkipFrontend,
    [switch]$Offline,
    [switch]$NoFrontendBuild
)

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
$artifactDir = Join-Path $root '.artifacts'
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$jsonPath = Join-Path $artifactDir ("verify-{0}.json" -f $stamp)

if (-not (Test-Path $artifactDir)) { New-Item -ItemType Directory -Path $artifactDir | Out-Null }

Push-Location $root
$commit = 'unknown'
$branch = 'unknown'
if (Get-Command git -ErrorAction SilentlyContinue) {
    $c = & git rev-parse --short HEAD 2>$null
    if ($LASTEXITCODE -eq 0 -and $c) { $commit = $c.Trim() }
    $b = & git rev-parse --abbrev-ref HEAD 2>$null
    if ($LASTEXITCODE -eq 0 -and $b) { $branch = $b.Trim() }
}
Pop-Location

$results = New-Object System.Collections.ArrayList
$script:failed = $false

function Invoke-Step {
    param(
        [string]$Name,
        [string]$WorkDir,
        [scriptblock]$Action
    )
    Write-Host ''
    Write-Host ("==> {0}" -f $Name) -ForegroundColor Cyan
    $watch = [System.Diagnostics.Stopwatch]::StartNew()
    Push-Location $WorkDir
    try {
        & $Action
        $code = $LASTEXITCODE
        if ($null -eq $code) { $code = 0 }
    }
    catch {
        Write-Host ("    error: {0}" -f $_.Exception.Message) -ForegroundColor Red
        $code = 1
    }
    finally {
        Pop-Location
        $watch.Stop()
    }
    $seconds = [int][Math]::Round($watch.Elapsed.TotalSeconds)
    $status = 'PASS'
    if ($code -ne 0) {
        $status = 'FAIL'
        $script:failed = $true
        Write-Host ("<== {0} FAIL rc={1} ({2}s)" -f $Name, $code, $seconds) -ForegroundColor Red
    }
    else {
        Write-Host ("<== {0} PASS ({1}s)" -f $Name, $seconds) -ForegroundColor Green
    }
    [void]$results.Add([pscustomobject]@{
        name     = $Name
        exitCode = [int]$code
        seconds  = $seconds
        status   = $status
    })
}

# Same reason as verify-all.sh: runtime DOCQA_* variables on a developer
# machine leak into Spring Boot / pydantic-settings and produce false local failures
# that never happen in CI. Scrub them for the duration of this process only.
$vars = [Environment]::GetEnvironmentVariables('Process')
foreach ($key in @($vars.Keys)) {
    if ($key -like 'DOCQA_*' -or $key -in @('SERVER__PORT', 'SERVER_PORT')) {
        [Environment]::SetEnvironmentVariable($key, $null, 'Process')
    }
}

Write-Host 'DocQA - gate runner' -ForegroundColor White
Write-Host ("Repo   : {0}" -f $root)
Write-Host ("Commit : {0} ({1})" -f $commit, $branch)
Write-Host ("Time   : {0}" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz'))

if (-not $SkipJava) {
    $mvnArgs = @('-B', '-ntp')
    if ($Offline) { $mvnArgs += '-o' }
    Invoke-Step -Name 'java:mvn clean verify' -WorkDir (Join-Path $root 'backend-java') -Action {
        & mvn @mvnArgs clean verify
    }
}
else { Write-Host '==> java skipped (-SkipJava)' }

if (-not $SkipPython) {
    $py = $null
    $candidates = @(
        (Join-Path $root 'ai-python\.venv\Scripts\python.exe'),
        (Join-Path $root 'ai-python\.venv\bin\python')
    )
    foreach ($candidate in $candidates) {
        if (Test-Path $candidate) { $py = $candidate; break }
    }
    if (-not $py) {
        $cmd = Get-Command python -ErrorAction SilentlyContinue
        if ($cmd) { $py = $cmd.Source }
    }
    if (-not $py) {
        Write-Host 'python interpreter not found' -ForegroundColor Red
        $script:failed = $true
        [void]$results.Add([pscustomobject]@{ name = 'python'; exitCode = 127; seconds = 0; status = 'FAIL' })
    }
    else {
        Write-Host ("Python : {0}" -f $py) -ForegroundColor DarkGray
        $aiDir = Join-Path $root 'ai-python'
        Invoke-Step -Name 'python:pytest' -WorkDir $aiDir -Action { & $py -m pytest -p no:warnings -q }
        Invoke-Step -Name 'python:ruff check' -WorkDir $aiDir -Action { & $py -m ruff check . }
        Invoke-Step -Name 'python:ruff format' -WorkDir $aiDir -Action { & $py -m ruff format --check . }
    }
}
else { Write-Host '==> python skipped (-SkipPython)' }

if (-not $SkipFrontend) {
    $feDir = Join-Path $root 'frontend-vue'
    if (-not (Test-Path (Join-Path $feDir 'node_modules'))) {
        Invoke-Step -Name 'frontend:npm ci' -WorkDir $feDir -Action { & npm ci }
    }
    Invoke-Step -Name 'frontend:typecheck' -WorkDir $feDir -Action { & npm run typecheck }
    Invoke-Step -Name 'frontend:lint' -WorkDir $feDir -Action { & npm run lint }
    Invoke-Step -Name 'frontend:test' -WorkDir $feDir -Action { & npm run test }
    if (-not $NoFrontendBuild) {
        Invoke-Step -Name 'frontend:build' -WorkDir $feDir -Action { & npm run build }
    }
}
else { Write-Host '==> frontend skipped (-SkipFrontend)' }

$totalSeconds = 0
foreach ($r in $results) { $totalSeconds += $r.seconds }

Write-Host ''
Write-Host '================ summary ================' -ForegroundColor White
foreach ($r in $results) {
    $color = 'Green'
    if ($r.status -ne 'PASS') { $color = 'Red' }
    Write-Host ("{0,-28} {1,-6} {2}s" -f $r.name, $r.status, $r.seconds) -ForegroundColor $color
}
if ($script:failed) {
    Write-Host ("RESULT: FAILED (total {0}s)" -f $totalSeconds) -ForegroundColor Red
}
else {
    Write-Host ("RESULT: ALL PASSED (total {0}s)" -f $totalSeconds) -ForegroundColor Green
}

$payload = [pscustomobject]@{
    timestamp    = (Get-Date).ToString('o')
    commit       = $commit
    branch       = $branch
    totalSeconds = $totalSeconds
    failed       = [bool]$script:failed
    steps        = @($results)
}
$payload | ConvertTo-Json -Depth 5 | Set-Content -Path $jsonPath -Encoding UTF8
Write-Host ("Summary: {0}" -f $jsonPath)

if ($script:failed) { exit 1 }
exit 0
