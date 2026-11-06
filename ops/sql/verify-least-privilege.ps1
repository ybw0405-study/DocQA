# =============================================================================
# 最小权限梯子的行为级取证
#
#   pwsh -File ops/sql/verify-least-privilege.ps1
#   pwsh -File ops/sql/verify-least-privilege.ps1 -SkipInsertProbe
#
# 与 apply-least-privilege.ps1 的分工：那个负责「把授权改成目标态」，这个负责
# 「用行为证明目标态真的成立」。判据不读 information_schema 的授权表（那是配置），
# 而是让每个受限账号自己去执行动作，看数据库到底拦不拦——配置对不等于行为对，
# 中间还隔着角色（MySQL 8 的 roles）、代理用户与默认库的选择。
#
# 唯一不写数据的手法：UPDATE / DELETE 带 WHERE 1 = 0，语句会真的被解析与鉴权，
# 但一行也不会命中；CREATE TABLE 会成功（对 migrator 而言），脚本立刻 DROP 掉。
# INSERT 没法用「零行」表达，因此**真的插一行**（带 PRIVILEGE_PROBE 标记），
# 再由 root 删掉——这一来一回本身就是证据：能追加的账号删不掉自己刚写的行。
#
# 退出码：0 = 全部判据成立；1 = 有 FAIL；2 = 账号尚未创建。
# =============================================================================
[CmdletBinding()]
param(
    [string]$AccountHost = 'localhost',
    [switch]$SkipInsertProbe
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$Schema = 'docqa'
$ProbeEventType = 'PRIVILEGE_PROBE'

$MySqlCandidates = @(
    'C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe',
    'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
)
$MySql = $MySqlCandidates | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $MySql) {
    $cmd = Get-Command mysql.exe -ErrorAction SilentlyContinue
    if ($cmd) { $MySql = $cmd.Source }
}
if (-not $MySql) { throw '找不到 mysql.exe' }

foreach ($scope in @('Machine', 'User')) {
    $vars = [Environment]::GetEnvironmentVariables($scope)
    foreach ($key in $vars.Keys) {
        if ($key -like 'DOCQA_*' -and -not [Environment]::GetEnvironmentVariable($key, 'Process')) {
            [Environment]::SetEnvironmentVariable($key, [string]$vars[$key], 'Process')
        }
    }
}

if (-not $env:DOCQA_DB_PASSWORD) { throw '缺少 DOCQA_DB_PASSWORD（管理员口令），清理探针行需要它' }
$AdminUser = if ($env:DOCQA_DB_USERNAME) { $env:DOCQA_DB_USERNAME } else { 'root' }

# -----------------------------------------------------------------------------
# 执行器
# -----------------------------------------------------------------------------
function Invoke-Sql {
    param([string]$User, [string]$Password, [string]$Sql)
    $env:MYSQL_PWD = $Password
    # Windows PowerShell 5.1 的坑：mysql 把 ERROR 1142 这类鉴权拒绝写到 stderr，
    # 而 EAP=Stop 会把 2>&1 捕获到的每一条 stderr 行当 NativeCommandError 抛出，
    # 于是一次**预期中的拒绝**反而杀掉了整个取证脚本（已实测）。
    # 本函数自己负责解析退出码与错误号，所以函数内临时降到 Continue。
    $prevEap = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $out = $Sql | & $MySql -h 127.0.0.1 -u $User -N -B --default-character-set=utf8mb4 2>&1
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $prevEap
    }
    $text = ($out | Out-String).Trim()
    $errorNumber = 0
    if ($text -match 'ERROR (\d+)') { $errorNumber = [int]$Matches[1] }
    return [pscustomobject]@{ ExitCode = $code; Text = $text; ErrorNumber = $errorNumber }
}

function Invoke-Admin([string]$Sql) {
    return Invoke-Sql -User $AdminUser -Password $env:DOCQA_DB_PASSWORD -Sql $Sql
}

# 权限拒绝的三个码：1142 表级、1044 库级、1145 全局；登录失败是 1045，必须区分开，
# 否则「口令配错」会被误读成「权限收敛得很成功」。
$PermissionDeniedCodes = @(1142, 1044, 1145)

$Results = New-Object System.Collections.Generic.List[object]
function Add-Result([string]$Account, [string]$Probe, [string]$Expected, $Result) {
    if ($Result.ExitCode -eq 0) { $actual = 'ALLOW' }
    elseif ($PermissionDeniedCodes -contains $Result.ErrorNumber) { $actual = 'DENY' }
    elseif ($Result.ErrorNumber -eq 1045) { $actual = 'LOGIN_FAILED' }
    else { $actual = "ERROR_$($Result.ErrorNumber)" }
    $verdict = if ($actual -eq $Expected) { 'PASS' } else { 'FAIL' }
    $Results.Add([pscustomobject]@{
            Account  = $Account
            Probe    = $Probe
            Expected = $Expected
            Actual   = $actual
            Verdict  = $verdict
            Detail   = if ($verdict -eq 'PASS') { '' } else { $Result.Text }
        })
}

# -----------------------------------------------------------------------------
# 预检：账号是否存在、口令是否就位
# -----------------------------------------------------------------------------
$AccountSpecs = @(
    @{ Name = 'ss_app'; Env = 'DOCQA_APP_DB_PASSWORD'; Expect = 'business runtime' },
    @{ Name = 'ss_audit'; Env = 'DOCQA_AUDIT_DB_PASSWORD'; Expect = 'audit consumer' },
    @{ Name = 'ss_migrator'; Env = 'DOCQA_MIGRATOR_DB_PASSWORD'; Expect = 'flyway only' }
)

Write-Host '== 预检'
$missingAccounts = @()
foreach ($spec in $AccountSpecs) {
    $grants = Invoke-Admin "SELECT COUNT(*) FROM information_schema.SCHEMA_PRIVILEGES WHERE TABLE_SCHEMA = '$Schema' AND GRANTEE = '''$($spec.Name)''@''$AccountHost''';"
    $tableGrants = Invoke-Admin "SELECT COUNT(*) FROM information_schema.TABLE_PRIVILEGES WHERE TABLE_SCHEMA = '$Schema' AND GRANTEE = '''$($spec.Name)''@''$AccountHost''';"
    $exists = Invoke-Admin "SELECT COUNT(*) FROM mysql.user WHERE user = '$($spec.Name)' AND host = '$AccountHost';"
    if (("$($exists.Text)").Trim() -ne '1') {
        Write-Host "   $($spec.Name)：账号不存在（先跑 apply-least-privilege.ps1）"
        $missingAccounts += $spec.Name
        continue
    }
    $password = [Environment]::GetEnvironmentVariable($spec.Env, 'Process')
    if (-not $password) {
        Write-Host "   $($spec.Name)：账号存在但环境变量 $($spec.Env) 为空，无法行为取证"
        $missingAccounts += $spec.Name
        continue
    }
    $spec.Password = $password
    Write-Host "   $($spec.Name)：账号在位，库级授权 $((("$($grants.Text)").Trim())) 条、表级授权 $((("$($tableGrants.Text)").Trim())) 条"
}

$activeSpecs = @($AccountSpecs | Where-Object { $_.ContainsKey('Password') })
if ($activeSpecs.Count -eq 0) {
    Write-Host '没有可取证账号：退出码 2'
    exit 2
}

# -----------------------------------------------------------------------------
# 清场：上一次中断留下的探针行与探针表
# -----------------------------------------------------------------------------
Write-Host '== 清场'
$leftover = Invoke-Admin "SELECT COUNT(*) FROM $Schema.audit_event WHERE event_type = '$ProbeEventType';"
if (("$($leftover.Text)").Trim() -ne '0') {
    Invoke-Admin "DELETE FROM $Schema.audit_event WHERE event_type = '$ProbeEventType';" | Out-Null
    Write-Host "   清掉上次残留的探针审计行：$((("$($leftover.Text)").Trim()))"
}
Invoke-Admin "DROP TABLE IF EXISTS $Schema.__ss_privilege_probe;" | Out-Null
$baselineRows = (Invoke-Admin "SELECT COUNT(*) FROM $Schema.audit_event;").Text.Trim()
Write-Host "   audit_event 基线行数：$baselineRows"

# -----------------------------------------------------------------------------
# 取证矩阵
# -----------------------------------------------------------------------------
$AuditSelectSql = "SELECT COUNT(*) FROM $Schema.audit_event;"
$AuditInsertSql = @"
INSERT INTO $Schema.audit_event
  (id, trace_id, user_id, event_type, agent_name, source, severity, payload_redacted, occurred_at, ingested_at)
VALUES
  (UUID(), REPLACE(UUID(), '-', ''), '00000000-0000-4000-8000-000000000000', '$ProbeEventType',
   'least-privilege-probe', 'JAVA', 'INFO', JSON_OBJECT('probe', 'ops/sql/verify-least-privilege.ps1'),
   UTC_TIMESTAMP(3), UTC_TIMESTAMP(3));
"@
$AuditUpdateSql = "UPDATE $Schema.audit_event SET severity = severity WHERE 1 = 0;"
$AuditDeleteSql = "DELETE FROM $Schema.audit_event WHERE 1 = 0;"
$DdlSql = "CREATE TABLE $Schema.__ss_privilege_probe (id INT PRIMARY KEY);"
$BusinessReadSql = "SELECT COUNT(*) FROM $Schema.app_user;"
$HistoryReadSql = "SELECT COUNT(*) FROM $Schema.flyway_schema_history;"

$Expectations = @{
    'ss_app'      = @{ AuditSelect = 'ALLOW'; AuditInsert = 'ALLOW'; AuditUpdate = 'DENY'; AuditDelete = 'DENY'; Ddl = 'DENY'; Business = 'ALLOW'; History = 'DENY' }
    'ss_audit'    = @{ AuditSelect = 'ALLOW'; AuditInsert = 'ALLOW'; AuditUpdate = 'DENY'; AuditDelete = 'DENY'; Ddl = 'DENY'; Business = 'DENY'; History = 'DENY' }
    'ss_migrator' = @{ AuditSelect = 'DENY'; AuditInsert = 'DENY'; AuditUpdate = 'DENY'; AuditDelete = 'DENY'; Ddl = 'ALLOW'; Business = 'DENY'; History = 'ALLOW' }
}

foreach ($spec in $activeSpecs) {
    $name = $spec.Name
    $pwd = $spec.Password
    $expect = $Expectations[$name]
    Write-Host "== 取证：$name"

    Add-Result $name 'SELECT audit_event' $expect.AuditSelect (Invoke-Sql $name $pwd $AuditSelectSql)

    if ($SkipInsertProbe) {
        Write-Host '   （已跳过 INSERT 探针：追加能力未被行为验证）'
    } else {
        Add-Result $name 'INSERT audit_event' $expect.AuditInsert (Invoke-Sql $name $pwd $AuditInsertSql)
    }

    Add-Result $name 'UPDATE audit_event' $expect.AuditUpdate (Invoke-Sql $name $pwd $AuditUpdateSql)
    Add-Result $name 'DELETE audit_event' $expect.AuditDelete (Invoke-Sql $name $pwd $AuditDeleteSql)

    $ddlResult = Invoke-Sql $name $pwd $DdlSql
    Add-Result $name 'CREATE TABLE' $expect.Ddl $ddlResult
    if ($ddlResult.ExitCode -eq 0) {
        # 探针表建成了就必须立刻拆掉；拆不掉说明这个账号能建表却不能删表，也要记下来
        $dropResult = Invoke-Sql $name $pwd "DROP TABLE $Schema.__ss_privilege_probe;"
        if ($dropResult.ExitCode -ne 0) {
            Write-Host ('   {0} 能建表但删不掉，已交回管理员清理：{1}' -f $name, $dropResult.Text)
            Invoke-Admin "DROP TABLE IF EXISTS $Schema.__ss_privilege_probe;" | Out-Null
        }
    }

    Add-Result $name 'SELECT app_user（业务数据）' $expect.Business (Invoke-Sql $name $pwd $BusinessReadSql)
    Add-Result $name 'SELECT flyway_schema_history' $expect.History (Invoke-Sql $name $pwd $HistoryReadSql)
}

# -----------------------------------------------------------------------------
# 覆盖度：业务表有没有漏授权（逐表授权的维护代价就靠这一步兜）
# -----------------------------------------------------------------------------
Write-Host '== 覆盖度：ss_app 对业务表的授权'
$appSpec = $AccountSpecs | Where-Object { $_.Name -eq 'ss_app' -and $_.ContainsKey('Password') }
if ($appSpec) {
    $businessTables = Invoke-Admin "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA = '$Schema' AND TABLE_TYPE = 'BASE TABLE' AND TABLE_NAME <> 'flyway_schema_history' ORDER BY TABLE_NAME;"
    $missingGrants = New-Object System.Collections.Generic.List[string]
    foreach ($table in ($businessTables.Text -split "`n" | ForEach-Object { $_.Trim() } | Where-Object { $_ })) {
        $count = Invoke-Admin "SELECT COUNT(*) FROM information_schema.TABLE_PRIVILEGES WHERE TABLE_SCHEMA = '$Schema' AND TABLE_NAME = '$table' AND GRANTEE = '''ss_app''@''$AccountHost''';"
        if (("$($count.Text)").Trim() -eq '0') { $missingGrants.Add($table) }
    }
    if ($missingGrants.Count -eq 0) {
        Write-Host '   全部业务表都有表级授权'
    } else {
        foreach ($table in $missingGrants) {
            $Results.Add([pscustomobject]@{
                    Account = 'ss_app'; Probe = "GRANT on $table"; Expected = 'ALLOW'; Actual = 'MISSING'; Verdict = 'FAIL'
                    Detail = '新增表后忘了同步 least-privilege.sql.tmpl'
                })
        }
    }
}

# -----------------------------------------------------------------------------
# 复原：删掉探针行，核对行数回到基线
# -----------------------------------------------------------------------------
Write-Host '== 复原'
if (-not $SkipInsertProbe) {
    $deleted = Invoke-Admin "DELETE FROM $Schema.audit_event WHERE event_type = '$ProbeEventType'; SELECT ROW_COUNT();"
    Write-Host "   管理员删除探针行：$((("$($deleted.Text)").Trim()))"
}
$afterRows = (Invoke-Admin "SELECT COUNT(*) FROM $Schema.audit_event;").Text.Trim()
$rowVerdict = if ($afterRows -eq $baselineRows) { 'PASS' } else { 'FAIL' }
$Results.Add([pscustomobject]@{
        Account = 'admin'; Probe = 'row count restored'; Expected = $baselineRows; Actual = $afterRows
        Verdict = $rowVerdict; Detail = ''
    })

# -----------------------------------------------------------------------------
# 报告
# -----------------------------------------------------------------------------
Write-Host ''
$Results | Format-Table -AutoSize Account, Probe, Expected, Actual, Verdict | Out-String -Width 200 | Write-Host
foreach ($row in $Results) {
    if ($row.Verdict -ne 'PASS' -and $row.Detail) {
        Write-Host "   [$($row.Account) / $($row.Probe)] $($row.Detail)"
    }
}

$failed = @($Results | Where-Object { $_.Verdict -ne 'PASS' })
Write-Host ("判据 {0} 条，通过 {1} 条，失败 {2} 条" -f $Results.Count, ($Results.Count - $failed.Count), $failed.Count)
if ($missingAccounts.Count -gt 0) {
    Write-Host ("未取证账号（未创建或缺口令）：{0}" -f ($missingAccounts -join ', '))
}
if ($failed.Count -gt 0) { exit 1 }
if ($missingAccounts.Count -gt 0) { exit 2 }
exit 0
