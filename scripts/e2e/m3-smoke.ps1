#!/usr/bin/env pwsh
# M3 smoke (Windows / pwsh) — 13 步主链路冒烟（issue #29 / spec §T4）。
#
# 前置：
#   1) PostgreSQL 16 已运行（VISUALSPIDER_DATASOURCE_URL/USERNAME/PASSWORD 已设置）；
#   2) Playwright Chromium 已安装（`mvn exec:java -Dexec.args="install chromium"`）；
#   3) Python 3 可用（起 fixture HTTP server）。
#
# 行为：
#   启动 JAR + fixture HTTP server；collector 登录 -> 建单页任务至 READY ->
#   POST /api/runs -> WS 收 PROGRESS/TERMINAL -> 查结果/导出/快照 ->
#   USER_RUN_LIMIT 校验 -> 取消路径 -> 强制重启验证 INTERRUPTED + 可导出 ->
#   admin 跨用户访问 -> 0 个 ms-playwright / driver 残留。
#
# 退出码：0 = 全绿；非 0 = 任一步失败。

[CmdletBinding()]
param(
    [string]$BaseUrl = $Env:M3_BASE_URL ?? 'http://localhost:8080',
    [string]$FixtureDir = $Env:M3_FIXTURE_DIR ?? "$PSScriptRoot/../../src/test/resources/fixtures",
    [int]$FixturePort = 8081,
    [int]$HealthTimeoutSec = 90,
    [int]$RunTimeoutSec = 120
)

$ErrorActionPreference = 'Stop'
$ProjectRoot = Resolve-Path (Join-Path $PSScriptRoot '..' '..')
Set-Location $ProjectRoot

# ============================== 路径与全局变量 ==============================

$JarPath = Join-Path $ProjectRoot 'target/visual-spider5-0.0.1-SNAPSHOT.jar'
$LogDir = Join-Path $ProjectRoot 'logs'
$AppOutLog = Join-Path $LogDir 'app.out.log'
$AppErrLog = Join-Path $LogDir 'app.err.log'
$AppPidFile = Join-Path $LogDir 'app.pid'
$FixtureLog = Join-Path $LogDir 'fixture.out.log'
$FixturePidFile = Join-Path $LogDir 'fixture.pid'

New-Item -ItemType Directory -Force -Path $LogDir | Out-Null

# WebSession：每个角色独立（admin 创号、collector 跑、admin 跨看）
$adminJar = New-TemporaryFile
$collectorJar = New-TemporaryFile
$admin2Jar = New-TemporaryFile

$AppProc = $null
$FixtureProc = $null
$Ws = $null

# ============================== 工具函数 ==============================

function Step { param([int]$n, [string]$msg) Write-Host "`n[STEP $n] $msg" -ForegroundColor Cyan }
function Ok   { param([string]$m) Write-Host "[OK]   $m" -ForegroundColor Green }
function Fail { param([string]$m) Write-Host "[FAIL] $m" -ForegroundColor Red; throw $m }
function Info { param([string]$m) Write-Host "[INFO] $m" -ForegroundColor Gray }

# 从 Netscape 风格 cookie jar 提取 XSRF-TOKEN
function Get-XsrfToken {
    param([string]$JarPath)
    foreach ($line in Get-Content $JarPath -ErrorAction SilentlyContinue) {
        if ($line -match 'XSRF-TOKEN\s+([A-Za-z0-9._-]+)') {
            return $Matches[1]
        }
    }
    return $null
}

# 从 Netscape 风格 cookie jar 提取 JSESSIONID
function Get-JSessionId {
    param([string]$JarPath)
    foreach ($line in Get-Content $JarPath -ErrorAction SilentlyContinue) {
        if ($line -match 'JSESSIONID\s+([A-Za-z0-9._-]+)') {
            return $Matches[1]
        }
    }
    return $null
}

# 等待 /actuator/health = UP
function Wait-HealthUp {
    param([int]$TimeoutSec)
    for ($i = 0; $i -lt $TimeoutSec; $i++) {
        Start-Sleep -Seconds 1
        try {
            $r = Invoke-WebRequest -Uri "$BaseUrl/actuator/health" -UseBasicParsing -TimeoutSec 5 -ErrorAction Stop
            if ($r.StatusCode -eq 200) {
                $body = ($r.Content | ConvertFrom-Json)
                if ($body.status -eq 'UP') { return $true }
            }
        } catch {
            # 启动中
        }
    }
    return $false
}

# 启动 JAR（后台 + 重定向日志到 logs/）
function Start-AppJar {
    if (-not (Test-Path $JarPath)) {
        Info "JAR 不存在，开始构建..."
        & ./mvnw package -DskipTests | Out-Null
        if ($LASTEXITCODE -ne 0) { Fail "mvn package 失败" }
    }
    $script:AppProc = Start-Process -FilePath 'java' `
        -ArgumentList @('-jar', $JarPath) `
        -RedirectStandardOutput $AppOutLog `
        -RedirectStandardError $AppErrLog `
        -NoNewWindow -PassThru
    $script:AppProc.Id | Out-File -FilePath $AppPidFile -Encoding ascii
    Info "JAR PID = $($script:AppProc.Id)"
}

# 强制停止 JAR（kill 等价）
function Force-StopAppJar {
    if ($script:AppProc -ne $null -and -not $script:AppProc.HasExited) {
        Info "Force kill JAR PID=$($script:AppProc.Id)"
        $script:AppProc | Stop-Process -Force -ErrorAction SilentlyContinue
    } else {
        # 通过 PID 文件兜底
        if (Test-Path $AppPidFile) {
            $pidFromFile = (Get-Content $AppPidFile).Trim()
            if ($pidFromFile -match '^\d+$') {
                Stop-Process -Id ([int]$pidFromFile) -Force -ErrorAction SilentlyContinue
            }
        }
    }
}

# 启动 fixture HTTP server（python -m http.server）
function Start-FixtureServer {
    $py = (Get-Command python3 -ErrorAction SilentlyContinue) ?? (Get-Command python -ErrorAction SilentlyContinue)
    if ($null -eq $py) { Fail "未找到 python / python3" }
    $resolvedFixtureDir = Resolve-Path $FixtureDir -ErrorAction SilentlyContinue
    if ($null -eq $resolvedFixtureDir) { Fail "fixture 目录不存在: $FixtureDir" }
    $script:FixtureProc = Start-Process -FilePath $py.Source `
        -ArgumentList @('-m', 'http.server', $FixturePort.ToString()) `
        -WorkingDirectory $resolvedFixtureDir `
        -RedirectStandardOutput $FixtureLog `
        -RedirectStandardError $FixtureLog `
        -NoNewWindow -PassThru
    $script:FixtureProc.Id | Out-File -FilePath $FixturePidFile -Encoding ascii
    Info "Fixture server PID=$($script:FixtureProc.Id) port=$FixturePort dir=$resolvedFixtureDir"
    Start-Sleep -Seconds 2
    try {
        $r = Invoke-WebRequest -Uri "http://localhost:$FixturePort/single-page.html" -UseBasicParsing -TimeoutSec 5 -ErrorAction Stop
        if ($r.StatusCode -ne 200) { Fail "fixture server 返回 $($r.StatusCode)" }
    } catch { Fail "fixture server 不可达: $_" }
    Ok "fixture HTTP server 已就绪 (port $FixturePort)"
}

function Stop-FixtureServer {
    if ($script:FixtureProc -ne $null -and -not $script:FixtureProc.HasExited) {
        $script:FixtureProc | Stop-Process -Force -ErrorAction SilentlyContinue
    }
    if (Test-Path $FixturePidFile) {
        $pidFromFile = (Get-Content $FixturePidFile).Trim()
        if ($pidFromFile -match '^\d+$') {
            Stop-Process -Id ([int]$pidFromFile) -Force -ErrorAction SilentlyContinue
        }
    }
}

# 通过 .NET ClientWebSocket 连接 /ws/runs/{runId}，带 cookie（CookieContainer）+ Origin（自定义头）
# 同步等待直到收到 TERMINAL 消息；中间记录是否收到过 PROGRESS，返回 hash：
#   { 'terminal' = $msg / 'CLOSED', 'sawProgress' = $bool }
function Connect-RunWsAndWaitTerminal {
    param(
        [long]$RunId,
        [string]$Jsid,
        [string]$XsrfToken,
        [int]$TimeoutSec
    )
    $ws = [System.Net.WebSockets.ClientWebSocket]::new()
    $sawProgress = $false
    try {
        $cookieContainer = [System.Net.CookieContainer]::new()
        $cookieUri = [System.Uri]::new($BaseUrl)
        $cookieContainer.Add($cookieUri, [System.Net.Cookie]::new('JSESSIONID', $Jsid, '/', 'localhost'))
        $cookieContainer.Add($cookieUri, [System.Net.Cookie]::new('XSRF-TOKEN', $XsrfToken, '/', 'localhost'))
        $ws.Options.CookieContainer = $cookieContainer
        $ws.Options.KeepAliveInterval = [TimeSpan]::FromSeconds(30)
        # Origin 必须与 requestUri 同源；服务端握手校验 OriginMatcher
        $ws.Options.SetRequestHeader('Origin', $BaseUrl)

        $wsUrl = "ws://localhost:8080/ws/runs/$RunId`?csrfToken=$XsrfToken"
        $uri = [System.Uri]::new($wsUrl)
        $ct = [System.Threading.CancellationToken]::new()
        $connectTask = $ws.ConnectAsync($uri, $ct)
        if (-not $connectTask.Wait($TimeoutSec * 1000)) {
            throw "WS 连接超时 ($TimeoutSec s)"
        }
        if ($ws.State -ne [System.Net.WebSockets.WebSocketState]::Open) {
            throw "WS 未进入 Open 状态: $($ws.State)"
        }

        $deadline = (Get-Date).AddSeconds($TimeoutSec)
        $buf = [System.Byte[]]::new(64 * 1024)
        while ((Get-Date) -lt $deadline) {
            $remainingMs = [int]([math]::Max(1000, ($deadline - (Get-Date)).TotalMilliseconds))
            $ms = [System.IO.MemoryStream]::new()
            while ($true) {
                $seg = [System.ArraySegment[Byte]]::new($buf)
                $recvTask = $ws.ReceiveAsync($seg, $ct)
                if (-not $recvTask.Wait($remainingMs)) {
                    $ms.Dispose()
                    throw "WS 收消息超时"
                }
                $res = $recvTask.Result
                if ($res.MessageType -eq [System.Net.WebSockets.WebSocketMessageType]::Close) {
                    $ms.Dispose()
                    return @{ terminal = $null; sawProgress = $sawProgress }
                }
                $ms.Write($buf, 0, $res.Count)
                if ($res.EndOfMessage) { break }
            }
            $text = [System.Text.Encoding]::UTF8.GetString($ms.ToArray())
            $ms.Dispose()
            if ([string]::IsNullOrEmpty($text)) { continue }
            $msg = $text | ConvertFrom-Json
            Write-Host "  ws <- type=$($msg.type) status=$($msg.status) stage=$($msg.stage)" -ForegroundColor DarkGray
            if ($msg.type -eq 'PROGRESS') { $sawProgress = $true }
            if ($msg.type -eq 'TERMINAL') { return @{ terminal = $msg; sawProgress = $sawProgress } }
        }
        throw "等待 TERMINAL 超时"
    } finally {
        try {
            if ($null -ne $ws -and $ws.State -eq [System.Net.WebSockets.WebSocketState]::Open) {
                $ws.CloseAsync([System.Net.WebSockets.WebSocketStatus]::NormalClosure, 'done',
                    [System.Threading.CancellationToken]::None).Wait(2000) | Out-Null
            }
        } catch { }
        if ($null -ne $ws) { $ws.Dispose() }
    }
}

# ============================== 主流程 ==============================

$Cleanup = {
    if ($script:Ws -ne $null) { try { $script:Ws.Dispose() } catch { } ; $script:Ws = $null }
    Stop-FixtureServer
    Force-StopAppJar
    Remove-Item $adminJar, $collectorJar, $admin2Jar -Force -ErrorAction SilentlyContinue
}
trap { & $Cleanup; throw $_ }

try {
    # ----- Step 1: 启动 JAR + fixture HTTP server -----
    Step 1 '启动 JAR + fixture HTTP server'
    if (-not $env:VISUALSPIDER_ADMIN_USERNAME) { $env:VISUALSPIDER_ADMIN_USERNAME = 'admin' }
    if (-not $env:VISUALSPIDER_ADMIN_PASSWORD) { $env:VISUALSPIDER_ADMIN_PASSWORD = 'change-me-please-12+' }
    Start-AppJar
    if (-not (Wait-HealthUp -TimeoutSec $HealthTimeoutSec)) { Fail '健康检查超时' }
    Ok '/actuator/health = UP'
    Start-FixtureServer

    # ----- Step 2: collector 登录 + 建单页任务至 READY -----
    Step 2 'admin 登录 + 创建 collector + collector 登录 + 建单页任务至 READY'
    $loginAdmin = @{ username = $env:VISUALSPIDER_ADMIN_USERNAME; password = $env:VISUALSPIDER_ADMIN_PASSWORD } | ConvertTo-Json
    try {
        Invoke-WebRequest -Uri "$BaseUrl/api/auth/login" -Method POST -Body $loginAdmin -ContentType 'application/json' `
            -WebSession $adminJar -UseBasicParsing | Out-Null
    } catch { Fail "admin 登录失败: $_" }
    $xsrfAdmin = Get-XsrfToken -JarPath $adminJar.FullName
    if (-not $xsrfAdmin) { Fail 'admin 登录后未找到 XSRF token' }
    Ok "admin 已登录 XSRF=$($xsrfAdmin.Substring(0, [Math]::Min(8, $xsrfAdmin.Length)))..."

    $collectorName = 'm3coll-' + (Get-Random -Maximum 99999)
    $collectorPwd  = 'm3coll-pwd-12+'
    $createColl = @{ username = $collectorName; password = $collectorPwd; role = 'COLLECTOR' } | ConvertTo-Json
    try {
        Invoke-WebRequest -Uri "$BaseUrl/api/admin/users" -Method POST -Body $createColl -ContentType 'application/json' `
            -Headers @{ 'X-XSRF-TOKEN' = $xsrfAdmin } -WebSession $adminJar -UseBasicParsing | Out-Null
    } catch { Fail "创建 collector 失败: $_" }
    Ok "collector 已创建: $collectorName"

    $loginColl = @{ username = $collectorName; password = $collectorPwd } | ConvertTo-Json
    try {
        Invoke-WebRequest -Uri "$BaseUrl/api/auth/login" -Method POST -Body $loginColl -ContentType 'application/json' `
            -WebSession $collectorJar -UseBasicParsing | Out-Null
    } catch { Fail "collector 登录失败: $_" }
    $xsrfColl = Get-XsrfToken -JarPath $collectorJar.FullName
    Ok 'collector 已登录'

    # 创建任务草稿（包含字段定义，触发 M2 校验通过 -> READY）
    $taskBody = @{
        name = 'm3-smoke-task'
        definition = @{
            schemaVersion = 1
            mode = 'SINGLE_PAGE'
            startUrl = "http://localhost:$FixturePort/single-page.html"
            viewport = @{ width = 1280; height = 720 }
            waitPolicy = @{ extraWaitSeconds = 0 }
            fields = @(
                @{ name = 'title'; source = 'VISIBLE_TEXT'; selector = 'h1'; selectorType = 'CSS'; resultType = 'TEXT'; trim = 'TRIM'; required = $true }
                @{ name = 'score'; source = 'VISIBLE_TEXT'; selector = 'span'; selectorType = 'CSS'; resultType = 'TEXT'; trim = 'TRIM'; required = $false }
            )
        }
    } | ConvertTo-Json -Depth 8
    $createResp = Invoke-WebRequest -Uri "$BaseUrl/api/tasks" -Method POST -Body $taskBody -ContentType 'application/json' `
        -Headers @{ 'X-XSRF-TOKEN' = $xsrfColl } -WebSession $collectorJar -UseBasicParsing
    $taskId = ($createResp.Content | ConvertFrom-Json).id
    Info "taskId=$taskId"

    # 触发一次保存让 M2 校验通过 -> READY
    $saveBody = $taskBody | ConvertFrom-Json | ConvertTo-Json -Depth 8
    $saveResp = Invoke-WebRequest -Uri "$BaseUrl/api/tasks/$taskId" -Method PUT -Body $saveBody -ContentType 'application/json' `
        -Headers @{ 'X-XSRF-TOKEN' = $xsrfColl } -WebSession $collectorJar -UseBasicParsing
    $saved = $saveResp.Content | ConvertFrom-Json
    if ($saved.status -ne 'READY') { Fail "任务应为 READY，实际 $($saved.status)" }
    Ok "任务已就绪 taskId=$taskId status=READY"

    # ----- Step 3: POST /api/runs -> 202 {runId, WAITING} -----
    Step 3 'POST /api/runs -> 202 {runId, WAITING}'
    $runStartBody = @{ taskId = $taskId } | ConvertTo-Json
    $startResp = Invoke-WebRequest -Uri "$BaseUrl/api/runs" -Method POST -Body $runStartBody -ContentType 'application/json' `
        -Headers @{ 'X-XSRF-TOKEN' = $xsrfColl } -WebSession $collectorJar -UseBasicParsing
    if ($startResp.StatusCode -ne 202) { Fail "POST /api/runs 期望 202，实际 $($startResp.StatusCode)" }
    $startJson = $startResp.Content | ConvertFrom-Json
    $runId = [long]$startJson.runId
    if ($startJson.status -ne 'WAITING') { Fail "启动后状态应为 WAITING，实际 $($startJson.status)" }
    Ok "runId=$runId status=WAITING"

    # ----- Step 4 + 5: WS /ws/runs/{runId} -> 收到 PROGRESS + TERMINAL {SUCCESS} -----
    Step 4 'WS /ws/runs/{runId} (带 CSRF) -> 收到 PROGRESS'
    Step 5 '运行推进 -> 收到 TERMINAL {status: SUCCESS}'

    $jsid = Get-JSessionId -JarPath $collectorJar.FullName
    if (-not $jsid) { Fail 'collector jar 未找到 JSESSIONID' }
    $wsResult = Connect-RunWsAndWaitTerminal -RunId $runId -Jsid $jsid -XsrfToken $xsrfColl -TimeoutSec $RunTimeoutSec
    if (-not $wsResult.sawProgress) { Fail 'WS 推送序列中未出现 PROGRESS' }
    if ($null -eq $wsResult.terminal) { Fail 'WS 在 TERMINAL 之前被关闭' }
    if ($wsResult.terminal.status -ne 'SUCCESS') {
        Fail "终态期望 SUCCESS，实际 $($wsResult.terminal.status) stopReason=$($wsResult.terminal.stopReason)"
    }
    Ok "WS 收到 PROGRESS + TERMINAL status=SUCCESS stopReason=$($wsResult.terminal.stopReason)"

    # ----- Step 6: GET /api/runs/{runId}/results -> 1 条结果 -----
    Step 6 'GET /api/runs/{runId}/results -> 1 条结果'
    $resResp = Invoke-WebRequest -Uri "$BaseUrl/api/runs/$runId/results?page=1&size=50" -WebSession $collectorJar -UseBasicParsing
    $resJson = $resResp.Content | ConvertFrom-Json
    if ($resJson.total -lt 1) { Fail "期望至少 1 条结果，实际 $($resJson.total)" }
    Ok "结果条数 = $($resJson.total)"

    # ----- Step 7: GET .../results/export?format=csv -> 1 行 CSV -----
    Step 7 'GET /api/runs/{runId}/results/export?format=csv -> 1 行 CSV'
    $csvResp = Invoke-WebRequest -Uri "$BaseUrl/api/runs/$runId/results/export?format=csv" -WebSession $collectorJar -UseBasicParsing
    $csvLines = ($csvResp.Content -split "`n") | Where-Object { $_.Length -gt 0 }
    if ($csvLines.Count -lt 2) { Fail "CSV 行数 < 2（应有 1 表头 + 1 数据），实际 $($csvLines.Count)" }
    if ($csvLines[0] -notmatch 'title') { Fail "CSV 表头不包含 title: $($csvLines[0])" }
    Ok "CSV 导出 $($csvLines.Count) 行（1 表头 + $($csvLines.Count - 1) 数据）"

    # ----- Step 8: GET /api/runs/{runId}/snapshot -> 固化定义 -----
    Step 8 'GET /api/runs/{runId}/snapshot -> 固化定义'
    $snapResp = Invoke-WebRequest -Uri "$BaseUrl/api/runs/$runId/snapshot" -WebSession $collectorJar -UseBasicParsing
    $snapJson = $snapResp.Content | ConvertFrom-Json
    if ($null -eq $snapJson.definition) { Fail 'snapshot.definition 为空' }
    if ($snapJson.definition.startUrl -notlike "*single-page.html") {
        Fail "snapshot startUrl 与期望不一致: $($snapJson.definition.startUrl)"
    }
    Ok "snapshot 已固化 startUrl=$($snapJson.definition.startUrl)"

    # ----- Step 9: USER_RUN_LIMIT -----
    # 第 1 个 run 已是 SUCCESS 终态；新增第 2 个 run（保持 W+R≥1），第 3 个 start 应 409
    Step 9 '第 2 个 run 启动后，第 3 个 run -> USER_RUN_LIMIT 409'
    $secondBody = @{ taskId = $taskId } | ConvertTo-Json
    $secondStart = Invoke-WebRequest -Uri "$BaseUrl/api/runs" -Method POST -Body $secondBody -ContentType 'application/json' `
        -Headers @{ 'X-XSRF-TOKEN' = $xsrfColl } -WebSession $collectorJar -UseBasicParsing
    if ($secondStart.StatusCode -ne 202) { Fail "第 2 个 start 期望 202，实际 $($secondStart.StatusCode)" }
    $secondRunId = [long]($secondStart.Content | ConvertFrom-Json).runId
    $thirdStatus = 0
    try {
        $thirdResp = Invoke-WebRequest -Uri "$BaseUrl/api/runs" -Method POST -Body $secondBody -ContentType 'application/json' `
            -Headers @{ 'X-XSRF-TOKEN' = $xsrfColl } -WebSession $collectorJar -UseBasicParsing
        $thirdStatus = $thirdResp.StatusCode
    } catch {
        $thirdStatus = $_.Exception.Response.StatusCode.value__
    }
    if ($thirdStatus -ne 409) { Fail "第 3 个 start 期望 409 USER_RUN_LIMIT，实际 $thirdStatus" }
    Ok "USER_RUN_LIMIT 验证通过 (409) (runId=$secondRunId)"

    # ----- Step 10: cancel 路径：start -> POST /cancel -> CANCELLED -----
    Step 10 'cancel 路径：start -> POST /cancel -> CANCELLED'
    try {
        Invoke-WebRequest -Uri "$BaseUrl/api/runs/$secondRunId/cancel" -Method POST `
            -Headers @{ 'X-XSRF-TOKEN' = $xsrfColl } -WebSession $collectorJar -UseBasicParsing | Out-Null
    } catch { Fail "cancel 请求失败: $_" }
    $cancelState = $null
    for ($i = 0; $i -lt 20; $i++) {
        Start-Sleep -Seconds 1
        try {
            $get = Invoke-WebRequest -Uri "$BaseUrl/api/runs/$secondRunId" -WebSession $collectorJar -UseBasicParsing
            $cancelState = ($get.Content | ConvertFrom-Json).status
            if ($cancelState -eq 'CANCELLED') { break }
        } catch { }
    }
    if ($cancelState -ne 'CANCELLED') { Fail "cancel 后状态应为 CANCELLED，实际 $cancelState" }
    Ok "CANCELLED 终态 (runId=$secondRunId)"

    # ----- Step 11: 强制停止 JAR -> 重启 -> 遗留 INTERRUPTED + 结果可导出 -----
    Step 11 '强制停止 JAR -> 重启 -> 遗留 INTERRUPTED + 结果可导出'
    $preRunId = $runId
    Force-StopAppJar
    Info 'JAR 已 kill；等待端口释放...'
    Start-Sleep -Seconds 3
    Start-AppJar
    if (-not (Wait-HealthUp -TimeoutSec $HealthTimeoutSec)) { Fail '重启后健康检查超时' }
    Ok 'JAR 已重启并就绪'

    # collector 重新登录（session 没了）
    try {
        Invoke-WebRequest -Uri "$BaseUrl/api/auth/login" -Method POST -Body $loginColl -ContentType 'application/json' `
            -WebSession $collectorJar -UseBasicParsing | Out-Null
    } catch { Fail "重启后 collector 重新登录失败: $_" }
    $xsrfColl = Get-XsrfToken -JarPath $collectorJar.FullName
    $jsid = Get-JSessionId -JarPath $collectorJar.FullName

    # RunRecovery 在启动时把上次的 WAITING/RUNNING 标 INTERRUPTED；我们 step 3-9 的 run 都已终态
    # 但 step 9 创建的 $secondRunId 可能是 WAITING/RUNNING -> INTERRUPTED
    # 这里验证：之前写入的结果在重启后仍可导出
    try {
        $csvAfter = Invoke-WebRequest -Uri "$BaseUrl/api/runs/$preRunId/results/export?format=csv" -WebSession $collectorJar -UseBasicParsing
        if ($csvAfter.StatusCode -ne 200) { Fail "重启后 CSV 导出失败 $($csvAfter.StatusCode)" }
        Ok "重启后 runId=$preRunId 的结果仍可导出"
    } catch { Fail "重启后导出异常: $_" }
    # 同时检查列表中至少能见到之前的 run
    $runsList = Invoke-WebRequest -Uri "$BaseUrl/api/runs" -WebSession $collectorJar -UseBasicParsing
    $runsJson = $runsList.Content | ConvertFrom-Json
    Info "重启后 collector 列表 run 总数 = $($runsJson.total)"

    # ----- Step 12: admin 跨用户访问 -> 能看到 collector 的 run + 导出 -----
    Step 12 'admin 跨用户访问 -> 能看到 collector 的 run + 导出'
    # admin 也需要重新登录
    try {
        Invoke-WebRequest -Uri "$BaseUrl/api/auth/login" -Method POST -Body $loginAdmin -ContentType 'application/json' `
            -WebSession $admin2Jar -UseBasicParsing | Out-Null
    } catch { Fail "重启后 admin 重新登录失败: $_" }
    $adminList = Invoke-WebRequest -Uri "$BaseUrl/api/runs" -WebSession $admin2Jar -UseBasicParsing
    $adminListJson = $adminList.Content | ConvertFrom-Json
    if ($adminListJson.total -lt 1) { Fail "admin 列表为空" }
    if (-not ($adminListJson.items | Where-Object { $_.runId -eq $preRunId })) {
        Fail "admin 列表看不到 collector 的 runId=$preRunId"
    }
    Ok "admin 可见 collector 的 runId=$preRunId"
    $adminExport = Invoke-WebRequest -Uri "$BaseUrl/api/runs/$preRunId/results/export?format=json" -WebSession $admin2Jar -UseBasicParsing
    if ($adminExport.StatusCode -ne 200) { Fail "admin 导出失败 $($adminExport.StatusCode)" }
    Ok 'admin 导出 JSON 成功'

    # ----- Step 13: pwsh 验证 0 个 ms-playwright / driver 残留 -----
    Step 13 'pwsh 验证 0 个 ms-playwright / driver 残留'
    $chromium = Get-CimInstance Win32_Process -Filter "Name='chrome.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -like '*ms-playwright*' -or $_.CommandLine -like '*playwright*driver*' }
    if ($chromium) {
        Write-Warning 'Chromium 子进程未回收，详情：'
        $chromium | Format-Table ProcessId, Name, CommandLine
        Fail 'Chromium 残留'
    } else {
        Ok 'Chromium 子进程已清空。'
    }

    Write-Host "`n[OK] M3 smoke 13 步全部通过" -ForegroundColor Green
} catch {
    Write-Host "`n[FAIL] M3 smoke: $_" -ForegroundColor Red
    exit 1
} finally {
    & $Cleanup
}
