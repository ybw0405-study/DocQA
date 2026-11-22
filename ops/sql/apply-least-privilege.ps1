# =============================================================================
# 最小权限账号梯子的执行器（幂等）
#
# 用法：
#   pwsh -File ops/sql/apply-least-privilege.ps1                 # 建号 + 授到目标态
#   pwsh -File ops/sql/apply-least-privilege.ps1 -DryRun         # 只打印将要执行的语句
#   pwsh -File ops/sql/apply-least-privilege.ps1 -Rotate         # 同时轮换三个口令
#   pwsh -File ops/sql/apply-least-privilege.ps1 -AccountHost 127.0.0.1
#
# 需要的管理员凭据：DOCQA_DB_USERNAME / DOCQA_DB_PASSWORD（默认 root）。
# 三个受限账号的口令来源优先级：已有环境变量 > 本脚本生成。
# 生成的口令**不回显**，直接写进用户级环境变量（run-local.ps1 会自动导入）。
#
# 执行顺序刻意是「先撤多余、后授目标」而不是反过来：
#   - 中途失败时账号落在**权限不足**一侧，服务会以「表或权限不存在」立刻报错；
#     反过来则落在**权限过多**一侧，静默越过审计红线，只能等 verify 才发现。
#   - 撤多余只针对本次快照里真实存在的授权生成语句：REVOKE 一个不存在的授权
#     会报 ERROR 1141 并中断批处理（已实测），无条件写 REVOKE 的模板第一次就会断。
# =============================================================================
[CmdletBinding()]
param(
    [string]$AccountHost = 'localhost',
    [switch]$Rotate,
    [switch]$DryRun,
    [switch]$NoPersistEnv
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$TemplatePath = Join-Path $PSScriptRoot 'least-privilege.sql.tmpl'
$Schema = 'docqa'

$MySqlCandidates = @(
    'C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe',
    'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
)
$MySql = $MySqlCandidates | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $MySql) {
    $cmd = Get-Command mysql.exe -ErrorAction SilentlyContinue
    if ($cmd) { $MySql = $cmd.Source }
}
if (-not $MySql) {
    throw '找不到 mysql.exe；请把 MySQL bin 目录加入 PATH，或改本脚本的 $MySqlCandidates。'
}
if (-not (Test-Path $TemplatePath)) {
    throw "找不到模板：$TemplatePath"
}

function Write-Step([string]$Text) { Write-Host "== $Text" }
function Write-Note([string]$Text) { Write-Host "   $Text" }

# -----------------------------------------------------------------------------
# 环境变量：本项目把配置放在 Windows User / Machine 作用域，进程里未必可见
# -----------------------------------------------------------------------------
foreach ($scope in @('Machine', 'User')) {
    $vars = [Environment]::GetEnvironmentVariables($scope)
    foreach ($key in $vars.Keys) {
        if ($key -like 'DOCQA_*' -and -not [Environment]::GetEnvironmentVariable($key, 'Process')) {
            [Environment]::SetEnvironmentVariable($key, [string]$vars[$key], 'Process')
        }
    }
}

$AdminUser = if ($env:DOCQA_DB_USERNAME) { $env:DOCQA_DB_USERNAME } else { 'root' }
if (-not $env:DOCQA_DB_PASSWORD) {
    throw '缺少 DOCQA_DB_PASSWORD（管理员口令）；本脚本要建号与授权，不能以受限账号执行。'
}
$env:MYSQL_PWD = $env:DOCQA_DB_PASSWORD

# -----------------------------------------------------------------------------
# mysql 执行器
#   -Raw：给机器读的查询用 -N -B（无表头、制表符分隔）；否则用 --table 给人看。
#   两种口径必须分开：--table 的输出带 +---+ 边框，直接解析会把边框当成数据。
# -----------------------------------------------------------------------------
function Invoke-MySql {
    param(
        [Parameter(Mandatory = $true)][AllowEmptyString()][string]$Sql,
        [Parameter(Mandatory = $true)][string]$Label,
        [switch]$Raw
    )
    if ([string]::IsNullOrWhiteSpace($Sql)) { return @() }
    $mysqlArgs = @('-h', '127.0.0.1', '-u', $AdminUser, '--default-character-set=utf8mb4')
    if ($Raw) { $mysqlArgs += @('-N', '-B') } else { $mysqlArgs += '--table' }
    # 同 verify 脚本踩过的坑：EAP=Stop 下 mysql 写到 stderr 的 ERROR 会被 2>&1
    # 转成 NativeCommandError 直接抛出，下面那段「带 Label 的报错」根本轮不到执行。
    # 失败归因必须落在这层，所以捕获期间临时降到 Continue。
    $prevEap = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = $Sql | & $MySql @mysqlArgs 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $prevEap
    }
    if ($exitCode -ne 0) {
        $text = ($output | Out-String).Trim()
        throw "$Label 执行失败（exit=$exitCode）：`n$text"
    }
    return @($output)
}

function Invoke-Query {
    param([string]$Sql, [string]$Label)
    return @(Invoke-MySql -Sql $Sql -Label $Label -Raw |
        ForEach-Object { ([string]$_).Trim() } |
        Where-Object { $_ })
}

# -----------------------------------------------------------------------------
# 预检
# -----------------------------------------------------------------------------
Write-Step '预检'
if ($AccountHost -notmatch '^[A-Za-z0-9._%-]+$') {
    throw "AccountHost 非法：$AccountHost"
}
$schemaRows = Invoke-Query "SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME = '$Schema';" '检查目标库'
if (($schemaRows -join '') -ne '1') {
    throw "目标库 $Schema 不存在；先跑一次迁移，再做权限收敛。"
}
$historyRows = Invoke-Query "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = '$Schema' AND TABLE_NAME = 'flyway_schema_history';" '检查迁移账本'
if (($historyRows -join '') -ne '1') {
    throw 'flyway_schema_history 不存在；GRANT 到不存在的表会报 1146，先跑一次迁移。'
}
Write-Note "库 $Schema 与迁移账本都在位；管理员账号 $AdminUser"

$businessTables = @(
    'agent_execution', 'ai_quota', 'app_user', 'app_user_role', 'chunk', 'conversation',
    'document', 'document_tag', 'document_tag_link', 'message', 'model_connection',
    'refresh_token', 'reminder', 'reminder_trigger', 'tenant', 'vector_cleanup_task'
)
# audit_relay_cursor 也是业务表，但目标态没有 DELETE（模板按 upsert 覆盖单行，没有删除路径），
# 和 audit_event 一样属于「按表特例授权」，所以不进 $businessTables。
$specialTables = @('audit_event', 'audit_relay_cursor')
$liveTables = @(Invoke-Query "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA = '$Schema' AND TABLE_TYPE = 'BASE TABLE' ORDER BY TABLE_NAME;" '读取表清单' |
    Where-Object { $_ -ne 'flyway_schema_history' })
# 未纳管 = 库里存在但两份清单都没覆盖；这种表连 ss_app 都读不到，运行时会以
# ERROR 1142 → SQLState 42000 → BadSqlGrammarException 出现，日志只剩一行异常类型名。
$drift = @($liveTables | Where-Object { $_ -notin $businessTables -and $_ -notin $specialTables })
if ($drift.Count -gt 0) {
    Write-Note "警告：库里有未纳入授权清单的表：$($drift -join ', ')"
    Write-Note '     apply 不会自动授权新表；确认后把表名同时加进 least-privilege.sql.tmpl 与 $businessTables。'
    if (-not $DryRun) {
        throw "未纳管表 $($drift -join ', ') 会让 ss_app 在运行时撞 ERROR 1142；先把授权清单补齐再执行。"
    }
}

# -----------------------------------------------------------------------------
# 口令：字符集刻意排除 ' " \ $ ` 与 0/O/1/l/I
#   - ' \ ` $ 会被 SQL 字面量或 PowerShell 转义吃掉，脚本选择拒跑而不是自行转义
#   - 0/O/1/l/I 在手工抄写进连接串时最容易错，而这些口令正是要人抄一次进环境变量的
# -----------------------------------------------------------------------------
$PasswordAlphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!#%*+-=?@^_'
$PasswordPattern = '^[A-Za-z0-9!#%*+\-=?@^_]{16,64}$'

function New-StrongPassword {
    param([int]$Length = 28)
    $alphabetLength = $PasswordAlphabet.Length
    # 拒绝采样：直接对随机字节取模会让前 256 % 68 = 52 个字符出现概率偏高约 1.4 倍；
    # 口令熵够用不代表可以接受有偏采样，多花两行把它消掉。
    $limit = [math]::Floor(256 / $alphabetLength) * $alphabetLength
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $chars = New-Object System.Text.StringBuilder
        while ($chars.Length -lt $Length) {
            $buffer = New-Object byte[] 64
            $rng.GetBytes($buffer)
            foreach ($b in $buffer) {
                if ($b -lt $limit -and $chars.Length -lt $Length) {
                    [void]$chars.Append($PasswordAlphabet[[int]($b % $alphabetLength)])
                }
            }
        }
        return $chars.ToString()
    } finally {
        $rng.Dispose()
    }
}

function Resolve-AccountPassword {
    param([string]$EnvName)
    $existing = [Environment]::GetEnvironmentVariable($EnvName, 'Process')
    if ($existing -and -not $Rotate) {
        if ($existing -notmatch $PasswordPattern) {
            throw "环境变量 $EnvName 的字符集不满足约束（16–64 位，且不含引号、反斜杠、反引号、美元符）。"
        }
        return @{ Password = $existing; Generated = $false }
    }
    return @{ Password = (New-StrongPassword -Length 28); Generated = $true }
}

$AccountNames = @('ss_migrator', 'ss_app', 'ss_audit')
$PasswordEnvByAccount = @{
    'ss_migrator' = 'DOCQA_MIGRATOR_DB_PASSWORD'
    'ss_app'      = 'DOCQA_APP_DB_PASSWORD'
    'ss_audit'    = 'DOCQA_AUDIT_DB_PASSWORD'
}
$Resolved = @{}
foreach ($account in $AccountNames) {
    $Resolved[$account] = Resolve-AccountPassword -EnvName $PasswordEnvByAccount[$account]
}

# -----------------------------------------------------------------------------
# 对账：读出各账号现网授权，只撤「目标态之外」的项
# -----------------------------------------------------------------------------
$MigratorDdl = @('CREATE', 'ALTER', 'DROP', 'INDEX', 'REFERENCES', 'CREATE VIEW',
    'CREATE ROUTINE', 'ALTER ROUTINE', 'EXECUTE', 'TRIGGER')
$FullDml = @('SELECT', 'INSERT', 'UPDATE', 'DELETE')
$AppendOnly = @('SELECT', 'INSERT')
# 游标表按 upsert 覆盖单行，没有删除路径；模板故意不给 DELETE，对账目标态必须一致，
# 否则这里会把 DELETE 当成「多余授权」撤掉，与模板互相打架。
$CursorUpsert = @('SELECT', 'INSERT', 'UPDATE')

function Get-DesiredPrivileges {
    param([string]$Account, [string]$Scope, [string]$TableName)
    switch ($Account) {
        'ss_migrator' {
            if ($Scope -eq 'SCHEMA') { return $MigratorDdl }
            if ($Scope -eq 'TABLE' -and $TableName -eq 'flyway_schema_history') { return $FullDml }
            return @()
        }
        'ss_app' {
            if ($Scope -eq 'TABLE' -and $TableName -eq 'audit_event') { return $AppendOnly }
            if ($Scope -eq 'TABLE' -and $TableName -eq 'audit_relay_cursor') { return $CursorUpsert }
            if ($Scope -eq 'TABLE' -and $businessTables -contains $TableName) { return $FullDml }
            return @()
        }
        'ss_audit' {
            if ($Scope -eq 'TABLE' -and $TableName -eq 'audit_event') { return $AppendOnly }
            return @()
        }
        default { return @() }
    }
}

function Get-ScopePrivileges {
    param([string]$Account, [string]$Scope, [string]$TableName)
    # 两个不同的东西，别混：
    #   $granteeLiteral  —— GRANTEE 列里存的**值**，实测是 'root'@'localhost'（含单引号，18 字节）；
    #                       拿它做比较时，整串要当成一个字符串字面量，所以内部的单引号必须翻倍，
    #                       写成 '''root''@''localhost'''。少翻一次就会得到 near '@'localhost'' 的语法错。
    #   $accountLiteral —— REVOKE ... FROM 'root'@'localhost' 里的账号语法，整体不加外层引号。
    $granteeValue = "'$Account'@'$AccountHost'"
    $granteeLiteral = "'" + $granteeValue.Replace("'", "''") + "'"
    $sql = switch ($Scope) {
        'GLOBAL' { "SELECT PRIVILEGE_TYPE FROM information_schema.USER_PRIVILEGES WHERE GRANTEE = $granteeLiteral;" }
        'SCHEMA' { "SELECT PRIVILEGE_TYPE FROM information_schema.SCHEMA_PRIVILEGES WHERE TABLE_SCHEMA = '$Schema' AND GRANTEE = $granteeLiteral;" }
        default  { "SELECT PRIVILEGE_TYPE FROM information_schema.TABLE_PRIVILEGES WHERE TABLE_SCHEMA = '$Schema' AND TABLE_NAME = '$TableName' AND GRANTEE = $granteeLiteral;" }
    }
    return @(Invoke-Query $sql "读取 $Account 的 $Scope 授权")
}

Write-Step '对账：找出目标态之外的授权'
$revokeStatements = New-Object System.Collections.Generic.List[string]
$changes = New-Object System.Collections.Generic.List[string]

foreach ($account in $AccountNames) {
    # REVOKE / GRANT 里的账号语法：'ss_app'@'localhost'，整体不加外层引号
    $accountLiteral = "'$account'@'$AccountHost'"

    $globalNow = @(Get-ScopePrivileges -Account $account -Scope 'GLOBAL')
    if ($globalNow.Count -gt 0) {
        # 受限账号不该有任何全局授权；GRANT OPTION 要单独的 REVOKE 语法
        $plainGlobal = @($globalNow | Where-Object { $_ -ne 'GRANT OPTION' })
        if ($plainGlobal.Count -gt 0) {
            $revokeStatements.Add("REVOKE $($plainGlobal -join ', ') ON *.* FROM $accountLiteral;")
            $changes.Add("$account 全局：撤 $($plainGlobal -join ',')")
        }
        if ($globalNow -contains 'GRANT OPTION') {
            $revokeStatements.Add("REVOKE GRANT OPTION ON *.* FROM $accountLiteral;")
            $changes.Add("$account 全局：撤 GRANT OPTION")
        }
    }

    $schemaNow = @(Get-ScopePrivileges -Account $account -Scope 'SCHEMA')
    $schemaWanted = @(Get-DesiredPrivileges -Account $account -Scope 'SCHEMA')
    $schemaExcess = @($schemaNow | Where-Object { $_ -ne 'GRANT OPTION' -and $schemaWanted -notcontains $_ })
    if ($schemaExcess.Count -gt 0) {
        $revokeStatements.Add("REVOKE $($schemaExcess -join ', ') ON ``$Schema``.* FROM $accountLiteral;")
        $changes.Add("$account 库级：撤 $($schemaExcess -join ',')")
    }
    if ($schemaNow -contains 'GRANT OPTION' -and $account -ne 'ss_migrator') {
        $revokeStatements.Add("REVOKE GRANT OPTION ON ``$Schema``.* FROM $accountLiteral;")
        $changes.Add("$account 库级：撤 GRANT OPTION")
    }

    foreach ($table in $liveTables) {
        $tableNow = @(Get-ScopePrivileges -Account $account -Scope 'TABLE' -TableName $table)
        if ($tableNow.Count -eq 0) { continue }
        $tableWanted = @(Get-DesiredPrivileges -Account $account -Scope 'TABLE' -TableName $table)
        $tableExcess = @($tableNow | Where-Object { $_ -ne 'GRANT OPTION' -and $tableWanted -notcontains $_ })
        if ($tableExcess.Count -gt 0) {
            $revokeStatements.Add("REVOKE $($tableExcess -join ', ') ON ``$Schema``.``$table`` FROM $accountLiteral;")
            $changes.Add("$account $table：撤 $($tableExcess -join ',')")
        }
    }
}

if ($changes.Count -eq 0) {
    Write-Note '现网没有多余授权（首次执行时正常）'
} else {
    $changes | ForEach-Object { Write-Note $_ }
}

# -----------------------------------------------------------------------------
# 渲染模板
# -----------------------------------------------------------------------------
$rendered = Get-Content -Path $TemplatePath -Raw -Encoding UTF8
$rendered = $rendered.Replace('__ACCOUNT_HOST__', $AccountHost)
$rendered = $rendered.Replace('__MIGRATOR_PASSWORD__', $Resolved['ss_migrator'].Password)
$rendered = $rendered.Replace('__APP_PASSWORD__', $Resolved['ss_app'].Password)
$rendered = $rendered.Replace('__AUDIT_PASSWORD__', $Resolved['ss_audit'].Password)

if ($rendered -match '__[A-Z][A-Z_]*__') {
    throw "模板里还有未替换的占位符：$($Matches[0])"
}

# 不变量：每个口令在渲染结果里必须**恰好出现一次**（一次 CREATE USER）。
# 多于一次意味着模板把占位符写进了注释或重复语句——那会让明文口令出现在
# DryRun 输出和日志里。与其靠人眼复查，不如让这种情况直接拒跑。
foreach ($account in $AccountNames) {
    $secret = $Resolved[$account].Password
    $occurrences = ([regex]::Matches($rendered, [regex]::Escape($secret))).Count
    if ($occurrences -ne 1) {
        throw "$account 的口令在渲染结果里出现了 $occurrences 次（应为 1）；模板可能把占位符写进了注释，拒绝执行以免明文外泄。"
    }
}

if ($DryRun) {
    Write-Step 'DryRun：以下语句不会执行'
    if ($revokeStatements.Count -gt 0) {
        Write-Host '--- 1) 撤多余 ---'
        $revokeStatements | ForEach-Object { Write-Host $_ }
    } else {
        Write-Host '--- 1) 撤多余：无 ---'
    }
    Write-Host '--- 2) 建号 + 授到目标态 + 回执 ---'
    # DryRun 的输出经常被贴进工单或群聊，口令一律脱敏
    Write-Host ($rendered -replace "(IDENTIFIED BY ')[^']+(')", '$1***$2')
    return
}

# -----------------------------------------------------------------------------
# 执行：先撤、后授
# -----------------------------------------------------------------------------
if ($revokeStatements.Count -gt 0) {
    Write-Step "撤多余授权（$($revokeStatements.Count) 条）"
    Invoke-MySql -Sql ($revokeStatements -join "`n") -Label '撤销多余授权' | Out-Null
    Write-Note '完成'
}

Write-Step '建号并授到目标态'
$receipt = Invoke-MySql -Sql $rendered -Label '应用最小权限授权'
$receipt | ForEach-Object { if (("$_".Trim())) { Write-Host "   $_" } }

if ($Rotate) {
    Write-Step '轮换口令'
    foreach ($account in $AccountNames) {
        $newPassword = $Resolved[$account].Password
        Invoke-MySql -Sql "ALTER USER '$account'@'$AccountHost' IDENTIFIED BY '$newPassword';" -Label "轮换 $account 口令" | Out-Null
    }
    Write-Note '三个账号的口令已轮换；新值已随下方环境变量写入'
}

# -----------------------------------------------------------------------------
# 口令落位：写用户级环境变量，不回显值
# -----------------------------------------------------------------------------
$envPlan = @(
    @{ Name = 'DOCQA_APP_DB_USERNAME'; Value = 'ss_app' },
    @{ Name = 'DOCQA_APP_DB_PASSWORD'; Value = $Resolved['ss_app'].Password },
    @{ Name = 'DOCQA_MIGRATOR_DB_USERNAME'; Value = 'ss_migrator' },
    @{ Name = 'DOCQA_MIGRATOR_DB_PASSWORD'; Value = $Resolved['ss_migrator'].Password },
    @{ Name = 'DOCQA_AUDIT_DB_USERNAME'; Value = 'ss_audit' },
    @{ Name = 'DOCQA_AUDIT_DB_PASSWORD'; Value = $Resolved['ss_audit'].Password }
)

Write-Step '环境变量'
if ($NoPersistEnv) {
    Write-Note '已指定 -NoPersistEnv，未写入。需要设置的变量名：'
    foreach ($item in $envPlan) {
        if ($item.Name -like '*_USERNAME') { Write-Note "  $($item.Name)=$($item.Value)" }
        else { Write-Note "  $($item.Name)=<本次口令，不回显>" }
    }
} else {
    foreach ($item in $envPlan) {
        [Environment]::SetEnvironmentVariable($item.Name, $item.Value, 'User')
        [Environment]::SetEnvironmentVariable($item.Name, $item.Value, 'Process')
    }
    $envPlan | ForEach-Object { Write-Note "已写入用户级：$($_.Name)" }
}
Write-Note '口令值不回显；需要时执行 [Environment]::GetEnvironmentVariable(''DOCQA_APP_DB_PASSWORD'',''User'')'

# -----------------------------------------------------------------------------
# 收尾指引
# -----------------------------------------------------------------------------
Write-Step '下一步'
Write-Note '1) 重启服务让新账号生效：docs/00-infra/run-local.ps1 -Action stop，再 -Action start'
Write-Note '2) 打开审计只追加的强校验（违规即拒绝启动）：'
Write-Note '     setx DOCQA_AUDIT_APPEND_ONLY_GUARD enforce'
Write-Note '3) 行为级取证：pwsh -File ops/sql/verify-least-privilege.ps1'
Write-Note '4) 想退回 root：清空上表六个变量即可（服务侧有回退链，不会因缺变量起不来）'
