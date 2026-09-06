#!/usr/bin/env pwsh
# Visual Spider 5 — M7 acceptance（Windows / pwsh）
#
# 文档依据：docs/specs/m7.md D6。
# 与 docs/e2e/m7-acceptance.sh（Linux 端）步骤一一对应；脚本自管 JAR 启动与 fixture server。
#
# 前置：本地 PG（视觉蜘蛛业务库 visualspider + 用户 visualspider）、JDK 21、
#       Playwright Chromium 已装、Python 3（仅 fixture HTTP server）。
#
# 参数：
#   -BaseUrl          默认 http://localhost:8080
#   -JarPath          默认 target/visual-spider5-0.1.0.jar（可由 $Env:VISUALSPIDER_JAR_PATH 覆盖）
#   -FixturePort      默认 18080
#   -FixtureDir       默认 src/test/resources（包含 pagination/ + content-page/ + ssrf/）
#   -LoopbackAllowed  默认 true（脚本注释与 docs/deploy/configuration.md §3 标注"仅测试"）
#   -HealthTimeoutSec 默认 120
#   -RunWaitSec       默认 180（运行最长等待）
#
# 退出码：
#   0 = 全链路通过
#   1 = 通用失败（带 STEP N 前缀）
#   2 = 前置条件缺失（PG / python / jar / chromium）

[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://localhost:8080',
    [string]$JarPath = $Env:VISUALSPIDER_JAR_PATH ?? (Join-Path (Get-Location) 'target/visual-spider5-0.1.0.jar'),
    [int]$FixturePort = 18080,
    [string]$FixtureDir = (Join-Path (Get-Location) 'src/test/resources'),
    [bool]$LoopbackAllowed = $true,
    [int]$HealthTimeoutSec = 120,
    [int]$RunWaitSec = 180
)

$ErrorActionPreference = 'Stop'
$ProjectRoot = (Resolve-Path (Get-Location)).Path
Set-Location $ProjectRoot

# 必要目录
$LogDir = Join-Path $ProjectRoot 'logs'
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null
$AppOutLog = Join-Path $LogDir 'm7-acceptance-app.out.log'
$AppErrLog = Join-Path $LogDir 'm7-acceptance-app.err.log'
$AppPidFile = Join-Path $LogDir 'm7-acceptance-app.pid'
$FixOutLog = Join-Path $LogDir 'm7-acceptance-fix.out.log'
$FixErrLog = Join-Path $LogDir 'm7-acceptance-fix.err.log'
$FixPidFile = Join-Path $LogDir 'm7-acceptance-fix.pid'

# 会话隔离：每个角色独立 cookie jar
$adminJar = New-TemporaryFile
$collectorJar = New-TemporaryFile
$otherJar = New-TemporaryFile
$AppProc = $null
$FixProc = $null

function Step { param([int]$n, [string]$m) Write-Host "`n[STEP $n] $m" -ForegroundColor Cyan }
function Ok   { param([string]$m) Write-Host "[OK]   $m" -ForegroundColor Green }
function Info { param([string]$m) Write-Host "[INFO] $m" -ForegroundColor Gray }
function Fail { param([int]$n, [string]$m) Write-Host "[FAIL step $n] $m" -ForegroundColor Red; exit $n }

function Get-Xsrf {
    param([string]$JarPath)
    if (-not (Test-Path $JarPath)) { return $null }
    foreach ($line in Get-Content $JarPath) {
        if ($line -match 'XSRF-TOKEN\s+([A-Za-z0-9._-]+)') { return $Matches[1] }
    }
    return $null
}

function Stop-All {
    if ($null -ne $FixProc -and -not $FixProc.HasExited) { Stop-Process -Id $FixProc.Id -Force -ErrorAction SilentlyContinue }
    if ($null -ne $AppProc -and -not $AppProc.HasExited) { Stop-Process -Id $AppProc.Id -Force -ErrorAction SilentlyContinue }
    Remove-Item $adminJar, $collectorJar, $otherJar -Force -ErrorAction SilentlyContinue
    Remove-Item $AppPidFile, $FixPidFile -Force -ErrorAction SilentlyContinue
}

# ---- 前置条件 ----
Step 2 '检查前置条件（jar / python / fixture 目录）'
if (-not (Test-Path $JarPath)) { Fail 2 "JAR 缺失：$JarPath。请先 ./mvnw package -DskipTests" }
if (-not (Get-Command python -ErrorAction SilentlyContinue)) { Fail 2 "未找到 python。请安装 Python 3（仅 fixture HTTP server 用，非运行时依赖）" }
if (-not (Test-Path (Join-Path $FixtureDir 'pagination'))) { Fail 2 "fixture 目录缺 pagination/：$FixtureDir" }
if (-not (Test-Path (Join-Path $FixtureDir 'content-page'))) { Fail 2 "fixture 目录缺 content-page/：$FixtureDir" }

# ---- 启 fixture HTTP server ----
Step 3 '启动 fixture HTTP server（python -m http.server，端口 {0}）' -f $FixturePort
$FixProc = Start-Process -FilePath 'python' -ArgumentList @(
    '-m', 'http.server', "$FixturePort", '--bind', '127.0.0.1'
) -PassThru -RedirectStandardOutput $FixOutLog -RedirectStandardError $FixErrLog `
    -WorkingDirectory $FixtureDir
$FixProc.Id | Out-File -FilePath $FixPidFile -Encoding ascii -NoNewline
Start-Sleep -Seconds 1
try {
    $r = Invoke-WebRequest "http://127.0.0.1:$FixturePort/pagination/next-page.html" -UseBasicParsing -TimeoutSec 5
    if ($r.StatusCode -ne 200) { Fail 3 "fixture server 未正确返回：$($r.StatusCode)" }
} catch { Fail 3 "fixture HTTP server 未启动：$_" }
Ok "fixture server 已起 (pid=$($FixProc.Id))"

# ---- 启应用 ----
Step 4 '启动应用（loopback-豁免={0}，端口 {1}）' -f $LoopbackAllowed, ($BaseUrl -replace 'http://localhost:', '')
$serverPort = $BaseUrl -replace 'http://localhost:', ''
$jvmArgs = @('-jar', $JarPath, "--server.port=$serverPort")
if ($LoopbackAllowed) { $jvmArgs += '--visualbrowser.target-url.allow-loopback=true' }
$AppProc = Start-Process -FilePath 'java' -ArgumentList $jvmArgs `
    -PassThru -RedirectStandardOutput $AppOutLog -RedirectStandardError $AppErrLog
$AppProc.Id | Out-File -FilePath $AppPidFile -Encoding ascii -NoNewline

$up = $false
for ($i = 0; $i -lt $HealthTimeoutSec; $i++) {
    Start-Sleep -Seconds 1
    try {
        $r = Invoke-WebRequest "$BaseUrl/actuator/health" -UseBasicParsing -TimeoutSec 5 -ErrorAction Stop
        if ($r.StatusCode -eq 200 -and ($r.Content | ConvertFrom-Json).status -eq 'UP') {
            $up = $true; break
        }
    } catch {}
    if ($AppProc.HasExited) { Fail 4 "JVM 已退出（exit=$($AppProc.ExitCode)）。最近日志：$(Get-Content $AppErrLog -Tail 30)" }
}
if (-not $up) { Fail 4 "actuator/health 在 ${HealthTimeoutSec}s 内未 UP。" }
Ok 'actuator/health UP'

# ---- 5. admin 登录 ----
Step 5 'admin 登录'
if (-not $env:VISUALSPIDER_ADMIN_USERNAME) { $env:VISUALSPIDER_ADMIN_USERNAME = 'admin' }
if (-not $env:VISUALSPIDER_ADMIN_PASSWORD) { $env:VISUALSPIDER_ADMIN_PASSWORD = 'change-me-please-12+' }
try {
    $body = @{ username = $env:VISUALSPIDER_ADMIN_USERNAME; password = $env:VISUALSPIDER_ADMIN_PASSWORD } | ConvertTo-Json
    Invoke-WebRequest "$BaseUrl/api/auth/login" -Method POST -Body $body -ContentType 'application/json' -WebSession $adminJar -UseBasicParsing | Out-Null
} catch { Fail 5 "admin 登录失败：$_" }
$xsrfAdmin = Get-Xsrf $adminJar.FullName
if (-not $xsrfAdmin) { Fail 5 '未拿到 admin XSRF token' }
Ok "admin 已登录 (XSRF len=$($xsrfAdmin.Length))"

# ---- 6. 创建 collector + 登录 ----
Step 6 '创建 collector + 登录'
$collectorUser = 'm7-coll-' + (Get-Random -Maximum 99999)
$collectorPwd  = 'm7-coll-pwd-12+'
try {
    $b = @{ username = $collectorUser; password = $collectorPwd; role = 'COLLECTOR' } | ConvertTo-Json
    Invoke-WebRequest "$BaseUrl/api/admin/users" -Method POST -Body $b -ContentType 'application/json' `
        -Headers @{ 'X-XSRF-TOKEN' = $xsrfAdmin } -WebSession $adminJar -UseBasicParsing | Out-Null
} catch { Fail 6 "创建 collector 失败：$_" }
try {
    $b = @{ username = $collectorUser; password = $collectorPwd } | ConvertTo-Json
    Invoke-WebRequest "$BaseUrl/api/auth/login" -Method POST -Body $b -ContentType 'application/json' `
        -WebSession $collectorJar -UseBasicParsing | Out-Null
} catch { Fail 6 "collector 登录失败：$_" }
$xsrfColl = Get-Xsrf $collectorJar.FullName
Ok "collector 已登录：$collectorUser"

# ---- 7. 单页任务：建任务 + 切 READY + 切正式运行 + 等待 ----
Step 7 '单页任务：建任务 + 配置会话 + 切正式运行 + 等待 TERMINAL'
$singleTaskBody = @{
    name = 'm7-acc-single'
    definition = @{
        schemaVersion = 4
        mode = 'SINGLE_PAGE'
        startUrl = "http://127.0.0.1:$FixturePort/pagination/next-page.html"
        viewport = @{ width = 1280; height = 720 }
        fields = @(
            @{ name = 'title'; selectorType = 'CSS'; selector = 'h1'; resultType = 'TEXT'; trim = 'TRIM'; required = $true }
        )
    }
} | ConvertTo-Json -Depth 8
$taskResp = Invoke-WebRequest "$BaseUrl/api/tasks" -Method POST -Body $singleTaskBody -ContentType 'application/json' `
    -Headers @{ 'X-XSRF-TOKEN' = $xsrfColl } -WebSession $collectorJar -UseBasicParsing
$singleTask = $taskResp.Content | ConvertFrom-Json
$singleTaskId = $singleTask.id
Ok "单页任务已建：id=$singleTaskId"

# 切 READY（PUT 一遍触发 TaskReadiness 校验）
try {
    $saveBody = @{ expectedVersion = $singleTask.version; definition = $singleTask.definition } | ConvertTo-Json -Depth 8
    Invoke-WebRequest "$BaseUrl/api/tasks/$singleTaskId" -Method PUT -Body $saveBody -ContentType 'application/json' `
        -Headers @{ 'X-XSRF-TOKEN' = $xsrfColl } -WebSession $collectorJar -UseBasicParsing | Out-Null
} catch { Fail 7 "单页任务切 READY 失败：$_" }

# 启运行
$runResp = Invoke-WebRequest "$BaseUrl/api/runs" -Method POST `
    -Body (@{ taskId = $singleTaskId } | ConvertTo-Json) -ContentType 'application/json' `
    -Headers @{ 'X-XSRF-TOKEN' = $xsrfColl } -WebSession $collectorJar -UseBasicParsing
$run1 = $runResp.Content | ConvertFrom-Json
$run1Id = $run1.runId
$run1Status = $run1.status
if ($run1Status -ne 'WAITING') { Fail 7 "单页运行创建后状态应为 WAITING，实际 $run1Status" }
Ok "单页运行已创建：id=$run1Id status=WAITING"

# 轮询直到 TERMINAL（最多 RunWaitSec）
$end = (Get-Date).AddSeconds($RunWaitSec)
while ((Get-Date) -lt $end) {
    Start-Sleep -Seconds 2
    try {
        $r = Invoke-WebRequest "$BaseUrl/api/runs/$run1Id" -UseBasicParsing -WebSession $collectorJar
        $st = ($r.Content | ConvertFrom-Json).status
        if ($st -in @('SUCCESS', 'PARTIAL_SUCCESS', 'FAILED', 'CANCELLED')) {
            $run1Final = $st
            break
        }
    } catch {}
}
if (-not $run1Final) { Fail 7 "单页运行在 ${RunWaitSec}s 内未终态" }
if ($run1Final -ne 'SUCCESS') { Fail 7 "单页运行终态应为 SUCCESS，实际 $run1Final" }
Ok "单页运行 SUCCESS"

# ---- 8. 列表任务：分页 ----
Step 8 '列表任务：分页翻页（pagination/next-page.html fixture）'
$listTaskBody = @{
    name = 'm7-acc-list'
    definition = @{
        schemaVersion = 4
        mode = 'LIST'
        startUrl = "http://127.0.0.1:$FixturePort/pagination/next-page.html"
        viewport = @{ width = 1280; height = 720 }
        pagination = @{ type = 'NEXT_PAGE'; selector = 'a.next' }
        fields = @(
            @{ name = 'title'; selectorType = 'CSS'; selector = 'h1'; resultType = 'TEXT'; trim = 'TRIM'; required = $true }
        )
    }
} | ConvertTo-Json -Depth 8
$resp = Invoke-WebRequest "$BaseUrl/api/tasks" -Method POST -Body $listTaskBody -ContentType 'application/json' `
    -Headers @{ 'X-XSRF-TOKEN' = $xsrfColl } -WebSession $collectorJar -UseBasicParsing
$listTask = $resp.Content | ConvertFrom-Json
$listTaskId = $listTask.id
try {
    $sb = @{ expectedVersion = $listTask.version; definition = $listTask.definition } | ConvertTo-Json -Depth 8
    Invoke-WebRequest "$BaseUrl/api/tasks/$listTaskId" -Method PUT -Body $sb -ContentType 'application/json' `
        -Headers @{ 'X-XSRF-TOKEN' = $xsrfColl } -WebSession $collectorJar -UseBasicParsing | Out-Null
} catch { Fail 8 "列表任务切 READY 失败：$_" }
$resp = Invoke-WebRequest "$BaseUrl/api/runs" -Method POST `
    -Body (@{ taskId = $listTaskId } | ConvertTo-Json) -ContentType 'application/json' `
    -Headers @{ 'X-XSRF-TOKEN' = $xsrfColl } -WebSession $collectorJar -UseBasicParsing
$run2Id = ($resp.Content | ConvertFrom-Json).runId
$end = (Get-Date).AddSeconds($RunWaitSec)
$run2Final = $null
while ((Get-Date) -lt $end) {
    Start-Sleep -Seconds 2
    try {
        $r = Invoke-WebRequest "$BaseUrl/api/runs/$run2Id" -UseBasicParsing -WebSession $collectorJar
        $st = ($r.Content | ConvertFrom-Json).status
        if ($st -in @('SUCCESS', 'PARTIAL_SUCCESS', 'FAILED', 'CANCELLED')) { $run2Final = $st; break }
    } catch {}
}
if (-not $run2Final -or $run2Final -notin @('SUCCESS', 'PARTIAL_SUCCESS')) {
    Fail 8 "列表翻页运行终态应为 SUCCESS/PARTIAL_SUCCESS，实际 $run2Final"
}
Ok "列表翻页运行 $run2Final"

# ---- 9. 内容页任务 ----
Step 9 '内容页任务：内容页前 3 条 + 字段合并（content-page fixture）'
$contentTaskBody = @{
    name = 'm7-acc-content'
    definition = @{
        schemaVersion = 4
        mode = 'LIST'
        startUrl = "http://127.0.0.1:$FixturePort/pagination/next-page.html"
        viewport = @{ width = 1280; height = 720 }
        pagination = @{ type = 'NEXT_PAGE'; selector = 'a.next' }
        content = @{
            selector = 'a.content-link'
            limit = 3
            fields = @(
                @{ name = 'body'; selectorType = 'CSS'; selector = 'p'; resultType = 'TEXT'; trim = 'TRIM'; required = $false }
            )
        }
        fields = @(
            @{ name = 'title'; selectorType = 'CSS'; selector = 'h1'; resultType = 'TEXT'; trim = 'TRIM'; required = $true }
        )
    }
} | ConvertTo-Json -Depth 10
$resp = Invoke-WebRequest "$BaseUrl/api/tasks" -Method POST -Body $contentTaskBody -ContentType 'application/json' `
    -Headers @{ 'X-XSRF-TOKEN' = $xsrfColl } -WebSession $collectorJar -UseBasicParsing
$contentTask = $resp.Content | ConvertFrom-Json
$contentTaskId = $contentTask.id
try {
    $sb = @{ expectedVersion = $contentTask.version; definition = $contentTask.definition } | ConvertTo-Json -Depth 10
    Invoke-WebRequest "$BaseUrl/api/tasks/$contentTaskId" -Method PUT -Body $sb -ContentType 'application/json' `
        -Headers @{ 'X-XSRF-TOKEN' = $xsrfColl } -WebSession $collectorJar -UseBasicParsing | Out-Null
} catch { Fail 9 "内容页任务切 READY 失败：$_" }
$resp = Invoke-WebRequest "$BaseUrl/api/runs" -Method POST `
    -Body (@{ taskId = $contentTaskId } | ConvertTo-Json) -ContentType 'application/json' `
    -Headers @{ 'X-XSRF-TOKEN' = $xsrfColl } -WebSession $collectorJar -UseBasicParsing
$run3Id = ($resp.Content | ConvertFrom-Json).runId
$end = (Get-Date).AddSeconds($RunWaitSec)
$run3Final = $null
while ((Get-Date) -lt $end) {
    Start-Sleep -Seconds 2
    try {
        $r = Invoke-WebRequest "$BaseUrl/api/runs/$run3Id" -UseBasicParsing -WebSession $collectorJar
        $st = ($r.Content | ConvertFrom-Json).status
        if ($st -in @('SUCCESS', 'PARTIAL_SUCCESS', 'FAILED', 'CANCELLED')) { $run3Final = $st; break }
    } catch {}
}
if (-not $run3Final -or $run3Final -notin @('SUCCESS', 'PARTIAL_SUCCESS')) {
    Fail 9 "内容页运行终态应为 SUCCESS/PARTIAL_SUCCESS，实际 $run3Final"
}
Ok "内容页运行 $run3Final"

# ---- 10. 取消运行：建一个新运行，立刻 cancel ----
Step 10 '取消运行：建任务 → 启运行 → 立刻 POST /cancel → 断言 CANCELLED'
$cancelTaskBody = @{
    name = 'm7-acc-cancel'
    definition = @{
        schemaVersion = 4
        mode = 'LIST'
        startUrl = "http://127.0.0.1:$FixturePort/pagination/next-page.html"
        viewport = @{ width = 1280; height = 720 }
        pagination = @{ type = 'NEXT_PAGE'; selector = 'a.next' }
        fields = @(@{ name = 'title'; selectorType = 'CSS'; selector = 'h1'; resultType = 'TEXT'; trim = 'TRIM'; required = $true })
    }
} | ConvertTo-Json -Depth 8
$resp = Invoke-WebRequest "$BaseUrl/api/tasks" -Method POST -Body $cancelTaskBody -ContentType 'application/json' `
    -Headers @{ 'X-XSRF-TOKEN' = $xsrfColl } -WebSession $collectorJar -UseBasicParsing
$cancelTask = $resp.Content | ConvertFrom-Json
$cancelTaskId = $cancelTask.id
try {
    $sb = @{ expectedVersion = $cancelTask.version; definition = $cancelTask.definition } | ConvertTo-Json -Depth 8
    Invoke-WebRequest "$BaseUrl/api/tasks/$cancelTaskId" -Method PUT -Body $sb -ContentType 'application/json' `
        -Headers @{ 'X-XSRF-TOKEN' = $xsrfColl } -WebSession $collectorJar -UseBasicParsing | Out-Null
} catch { Fail 10 "cancel 任务切 READY 失败：$_" }
$resp = Invoke-WebRequest "$BaseUrl/api/runs" -Method POST `
    -Body (@{ taskId = $cancelTaskId } | ConvertTo-Json) -ContentType 'application/json' `
    -Headers @{ 'X-XSRF-TOKEN' = $xsrfColl } -WebSession $collectorJar -UseBasicParsing
$run4Id = ($resp.Content | ConvertFrom-Json).runId
Start-Sleep -Seconds 1
try {
    Invoke-WebRequest "$BaseUrl/api/runs/$run4Id/cancel" -Method POST `
        -Headers @{ 'X-XSRF-TOKEN' = $xsrfColl } -WebSession $collectorJar -UseBasicParsing | Out-Null
} catch { Fail 10 "cancel 调用失败：$_" }
$end = (Get-Date).AddSeconds(60)
$run4Final = $null
while ((Get-Date) -lt $end) {
    Start-Sleep -Seconds 1
    try {
        $r = Invoke-WebRequest "$BaseUrl/api/runs/$run4Id" -UseBasicParsing -WebSession $collectorJar
        $st = ($r.Content | ConvertFrom-Json).status
        if ($st -in @('SUCCESS', 'PARTIAL_SUCCESS', 'FAILED', 'CANCELLED', 'INTERRUPTED')) { $run4Final = $st; break }
    } catch {}
}
if ($run4Final -ne 'CANCELLED') { Fail 10 "cancel 运行终态应为 CANCELLED，实际 $run4Final" }
Ok "cancel 路径终态 CANCELLED"

# ---- 11. 导出 CSV / JSON ----
Step 11 '导出 CSV / JSON 行数校验（run1 单页）'
try {
    $csv = Invoke-WebRequest "$BaseUrl/api/runs/$run1Id/results/export?format=csv" -UseBasicParsing -WebSession $collectorJar
} catch { Fail 11 "CSV 导出失败：$_" }
$csvLines = ($csv.Content -split "`n" | Where-Object { $_.Trim() }).Count
if ($csvLines -lt 2) { Fail 11 "CSV 行数 < 2（应 ≥ 1 header + ≥ 1 data）：$csvLines" }
try {
    $json = Invoke-WebRequest "$BaseUrl/api/runs/$run1Id/results/export?format=json" -UseBasicParsing -WebSession $collectorJar
} catch { Fail 11 "JSON 导出失败：$_" }
$jsonBody = $json.Content | ConvertFrom-Json
if (-not ($jsonBody -is [array]) -or $jsonBody.Count -lt 1) { Fail 11 "JSON 导出为空或非数组" }
Ok "CSV=$csvLines 行; JSON=$($jsonBody.Count) 条"

# ---- 12. 跨用户权限负向 ----
Step 12 '跨用户权限负向：second collector 访问 run1 / task1 → 404 或 403'
$otherUser = 'm7-other-' + (Get-Random -Maximum 99999)
$otherPwd  = 'm7-other-pwd-12+'
try {
    $b = @{ username = $otherUser; password = $otherPwd; role = 'COLLECTOR' } | ConvertTo-Json
    Invoke-WebRequest "$BaseUrl/api/admin/users" -Method POST -Body $b -ContentType 'application/json' `
        -Headers @{ 'X-XSRF-TOKEN' = $xsrfAdmin } -WebSession $adminJar -UseBasicParsing | Out-Null
    $b = @{ username = $otherUser; password = $otherPwd } | ConvertTo-Json
    Invoke-WebRequest "$BaseUrl/api/auth/login" -Method POST -Body $b -ContentType 'application/json' `
        -WebSession $otherJar -UseBasicParsing | Out-Null
} catch { Fail 12 "第二个 collector 准备失败：$_" }
foreach ($target in @("$BaseUrl/api/tasks/$singleTaskId", "$BaseUrl/api/runs/$run1Id/results")) {
    try {
        $r = Invoke-WebRequest $target -UseBasicParsing -WebSession $otherJar
        Fail 12 "跨用户访问应被拒（403/404），实际 $($r.StatusCode)"
    } catch {
        $st = $_.Exception.Response.StatusCode.value__
        if ($st -ne 403 -and $st -ne 404) { Fail 12 "跨用户访问应 403/404，实际 $st" }
    }
}
Ok '跨用户访问被拒（403/404）'

# ---- 13. retention 触发 ----
Step 13 '触发 retention（admin REST PUT /api/admin/settings/retention.days）'
try {
    $b = @{ value = 30 } | ConvertTo-Json
    Invoke-WebRequest "$BaseUrl/api/admin/settings/retention.days" -Method PUT -Body $b -ContentType 'application/json' `
        -Headers @{ 'X-XSRF-TOKEN' = $xsrfAdmin } -WebSession $adminJar -UseBasicParsing | Out-Null
} catch { Fail 13 "retention.days 设置失败（端点是否就绪）：$_" }
Ok 'retention.days=30 PUT 成功'

# ---- 收尾 ----
Write-Host "`n[OK] m7-acceptance 13 步全部通过" -ForegroundColor Green
Stop-All
exit 0