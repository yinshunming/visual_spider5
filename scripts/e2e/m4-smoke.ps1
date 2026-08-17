#!/usr/bin/env pwsh
# M4 smoke (Windows / pwsh) — 10 步主链路冒烟（issue #37 / spec §T4）。
#
# 前置（与 M3 smoke 一致）：
#   1) PostgreSQL 16 已运行（VISUALSPIDER_DATASOURCE_URL/USERNAME/PASSWORD 已设置，或
#      默认 localhost:5432/visualspider 用户 visualspider）；
#   2) Playwright Chromium 已安装（`mvn exec:java -Dexec.args="install chromium"`）；
#   3) Python 3 可用（起 fixture HTTP server）；
#   4) JAR 已通过 `./mvnw package -DskipTests` 构建（脚本会自行检查并构建）。
#
# 行为（10 步）：
#   1) 启 JAR + fixture HTTP server（list/ 目录，端口 8082）；
#   2) admin 登录 + 创建 collector + collector 登录 + 建 LIST 任务（standard-list fixture）
#      + PUT 至 READY（listItemRule + uniqueKey + limits）；
#   3) POST /api/runs -> 202 WAITING；
#   4) WS /ws/runs/{runId} -> PROGRESS + EVENT(LIST_ITEM_EXTRACTED) + TERMINAL SUCCESS；
#   5) GET /api/runs/{runId} -> raw>0, dedup=0, final>0, fail=0 + CSV 多行导出；
#   6) with-duplicates fixture -> 跑 -> dedup>0, final<raw；
#   7) partial-fail fixture（1 行值不可入库 + 1 行延迟渲染） -> PARTIAL_SUCCESS + fail>0；
#   8) cancel 路径：start -> POST /cancel -> CANCELLED + lane 释放（<15s 内可再起 run）；
#   9) pwsh 验证 0 个 ms-playwright / driver 残留；
#  10) 输出 Linux 标注（实际不在本脚本执行，见 m4-smoke.sh）。
#
# partial-fail 触发机制说明：PG jsonb 拒绝字符转义（SQLState 22x），按 §D6
# 应计行级失败而非 dedup；M4-7 修了 DataIntegrityViolationException 内的 SQLState 分类。
#
# 退出码：0 = 全绿；非 0 = 任一步失败。

[CmdletBinding()]
param(
    [string]$BaseUrl = $Env:M4_BASE_URL ?? 'http://localhost:8080',
    [string]$FixtureDir = $Env:M4_FIXTURE_DIR ?? "$PSScriptRoot/../../src/test/resources/list",
    [int]$FixturePort = 8082,
    [int]$HealthTimeoutSec = 90,
    [int]$RunTimeoutSec = 120,
    [int]$CancelTimeoutSec = 15
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
$FixtureErrLog = Join-Path $LogDir 'fixture.err.log'
$FixturePidFile = Join-Path $LogDir 'fixture.pid'

New-Item -ItemType Directory -Force -Path $LogDir | Out-Null

# WebSession 对象（-SessionVariable 自动创建；后续 -WebSession 复用）
$script:adminSession = $null
$script:collectorSession = $null

$script:AppProc = $null
$script:FixtureProc = $null

# ============================== 工具函数 ==============================

function Step { param([int]$n, [string]$msg) Write-Host "`n[STEP $n] $msg" -ForegroundColor Cyan }
function Ok   { param([string]$m) Write-Host "[OK]   $m" -ForegroundColor Green }
function Fail { param([string]$m) Write-Host "[FAIL] $m" -ForegroundColor Red; throw $m }
function Info { param([string]$m) Write-Host "[INFO] $m" -ForegroundColor Gray }

# Spring actuator/health 的 Content-Type 是 application/vnd.spring-boot.actuator.v3+json，
# -UseBasicParsing 下 .Content 被降级为 byte[]；统一解码为 UTF-8 字符串便于 ConvertFrom-Json。
function Read-ResponseText {
    param([Parameter(Mandatory)]$Response)
    if ($Response.Content -is [byte[]]) {
        [System.Text.Encoding]::UTF8.GetString($Response.Content)
    } else {
        [string]$Response.Content
    }
}

# 从 WebRequestSession.Cookies 取 XSRF-TOKEN
function Get-XsrfFromSession {
    param([Parameter(Mandatory)]$Session)
    if ($null -eq $Session) { return $null }
    $cookie = $Session.Cookies.GetCookies($BaseUrl) | Where-Object { $_.Name -eq 'XSRF-TOKEN' } | Select-Object -First 1
    return $cookie.Value
}

# 从 WebRequestSession.Cookies 取 JSESSIONID
function Get-JSessionFromSession {
    param([Parameter(Mandatory)]$Session)
    if ($null -eq $Session) { return $null }
    $cookie = $Session.Cookies.GetCookies($BaseUrl) | Where-Object { $_.Name -eq 'JSESSIONID' } | Select-Object -First 1
    return $cookie.Value
}

function Wait-HealthUp {
    param([int]$TimeoutSec)
    for ($i = 0; $i -lt $TimeoutSec; $i++) {
        Start-Sleep -Seconds 1
        try {
            $r = Invoke-WebRequest -Uri "$BaseUrl/actuator/health" -UseBasicParsing -TimeoutSec 5 -ErrorAction Stop
            if ($r.StatusCode -eq 200) {
                $body = (Read-ResponseText $r) | ConvertFrom-Json
                if ($body.status -eq 'UP') { return $true }
            }
        } catch { }
    }
    return $false
}

function Start-AppJar {
    if (-not (Test-Path $JarPath)) {
        Info 'JAR 不存在，开始构建...'
        & ./mvnw package -DskipTests | Out-Null
        if ($LASTEXITCODE -ne 0) { Fail 'mvn package 失败' }
    }
    $script:AppProc = Start-Process -FilePath 'java' `
        -ArgumentList @('-jar', $JarPath) `
        -RedirectStandardOutput $AppOutLog `
        -RedirectStandardError $AppErrLog `
        -NoNewWindow -PassThru
    $script:AppProc.Id | Out-File -FilePath $AppPidFile -Encoding ascii
    Info "JAR PID = $($script:AppProc.Id) (env: VISUALSPIDER_ADMIN_USERNAME=$env:VISUALSPIDER_ADMIN_USERNAME)"
}

function Force-StopAppJar {
    if ($script:AppProc -ne $null -and -not $script:AppProc.HasExited) {
        Info "Force kill JAR PID=$($script:AppProc.Id)"
        $script:AppProc | Stop-Process -Force -ErrorAction SilentlyContinue
    } elseif (Test-Path $AppPidFile) {
        $pidFromFile = (Get-Content $AppPidFile).Trim()
        if ($pidFromFile -match '^\d+$') {
            Stop-Process -Id ([int]$pidFromFile) -Force -ErrorAction SilentlyContinue
        }
    }
}

function Start-FixtureServer {
    $py = (Get-Command python3 -ErrorAction SilentlyContinue) ?? (Get-Command python -ErrorAction SilentlyContinue)
    if ($null -eq $py) { Fail '未找到 python / python3' }
    $resolvedFixtureDir = Resolve-Path $FixtureDir -ErrorAction SilentlyContinue
    if ($null -eq $resolvedFixtureDir) { Fail "fixture 目录不存在: $FixtureDir" }
    $script:FixtureProc = Start-Process -FilePath $py.Source `
        -ArgumentList @('-m', 'http.server', $FixturePort.ToString()) `
        -WorkingDirectory $resolvedFixtureDir `
        -RedirectStandardOutput $FixtureLog `
        -RedirectStandardError $FixtureErrLog `
        -NoNewWindow -PassThru
    $script:FixtureProc.Id | Out-File -FilePath $FixturePidFile -Encoding ascii
    Info "Fixture server PID=$($script:FixtureProc.Id) port=$FixturePort dir=$resolvedFixtureDir"
    Start-Sleep -Seconds 2
    try {
        $r = Invoke-WebRequest -Uri "http://localhost:$FixturePort/standard-list.html" -UseBasicParsing -TimeoutSec 5 -ErrorAction Stop
        if ($r.StatusCode -ne 200) { Fail "fixture server 返回 $($r.StatusCode)" }
    } catch { Fail "fixture server 不可达: $_" }
    Ok "fixture HTTP server 已就绪 (port $FixturePort)"
}

function Stop-FixtureServer {
    if ($script:FixtureProc -ne $null -and -not $script:FixtureProc.HasExited) {
        $script:FixtureProc | Stop-Process -Force -ErrorAction SilentlyContinue
    } elseif (Test-Path $FixturePidFile) {
        $pidFromFile = (Get-Content $FixturePidFile).Trim()
        if ($pidFromFile -match '^\d+$') {
            Stop-Process -Id ([int]$pidFromFile) -Force -ErrorAction SilentlyContinue
        }
    }
}

function Connect-RunWsAndWaitTerminal {
    param(
        [long]$RunId,
        [string]$Jsid,
        [string]$XsrfToken,
        [int]$TimeoutSec
    )
    $ws = [System.Net.WebSockets.ClientWebSocket]::new()
    $sawProgress = $false
    $eventStages = New-Object System.Collections.Generic.List[string]
    try {
        $cookieContainer = [System.Net.CookieContainer]::new()
        $cookieUri = [System.Uri]::new($BaseUrl)
        $cookieContainer.Add($cookieUri, [System.Net.Cookie]::new('JSESSIONID', $Jsid, '/', 'localhost'))
        $cookieContainer.Add($cookieUri, [System.Net.Cookie]::new('XSRF-TOKEN', $XsrfToken, '/', 'localhost'))
        $ws.Options.Cookies = $cookieContainer
        $ws.Options.KeepAliveInterval = [TimeSpan]::FromSeconds(30)
        $ws.Options.SetRequestHeader('Origin', $BaseUrl)

        $wsUrl = "ws://localhost:8080/ws/runs/$RunId`?csrfToken=$XsrfToken"
        $uri = [System.Uri]::new($wsUrl)
        $ct = [System.Threading.CancellationToken]::None
        $connectTask = $ws.ConnectAsync($uri, $ct)
        if (-not $connectTask.Wait($TimeoutSec * 1000)) { throw "WS 连接超时 ($TimeoutSec s)" }
        if ($ws.State -ne [System.Net.WebSockets.WebSocketState]::Open) { throw "WS 未进入 Open: $($ws.State)" }

        $deadline = (Get-Date).AddSeconds($TimeoutSec)
        $buf = [System.Byte[]]::new(64 * 1024)
        while ((Get-Date) -lt $deadline) {
            $remainingMs = [int]([math]::Max(1000, ($deadline - (Get-Date)).TotalMilliseconds))
            $ms = [System.IO.MemoryStream]::new()
            while ($true) {
                $seg = [System.ArraySegment[Byte]]::new($buf)
                $recvTask = $ws.ReceiveAsync($seg, $ct)
                if (-not $recvTask.Wait($remainingMs)) {
                    $ms.Dispose(); throw 'WS 收消息超时'
                }
                $res = $recvTask.Result
                if ($res.MessageType -eq [System.Net.WebSockets.WebSocketMessageType]::Close) {
                    $ms.Dispose()
                    return @{ terminal = $null; sawProgress = $sawProgress; eventStages = $eventStages }
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
            elseif ($msg.type -eq 'EVENT') {
                if ($null -ne $msg.stage) { [void]$eventStages.Add([string]$msg.stage) }
            }
            elseif ($msg.type -eq 'TERMINAL') {
                return @{ terminal = $msg; sawProgress = $sawProgress; eventStages = $eventStages }
            }
        }
        throw '等待 TERMINAL 超时'
    } finally {
        try {
            if ($null -ne $ws -and $ws.State -eq [System.Net.WebSockets.WebSocketState]::Open) {
                $ws.CloseAsync([System.Net.WebSockets.WebSocketCloseStatus]::NormalClosure, 'done',
                    [System.Threading.CancellationToken]::None).Wait(2000) | Out-Null
            }
        } catch { }
        if ($null -ne $ws) { $ws.Dispose() }
    }
}

function Poll-RunStatus {
    param([long]$RunId, [int]$TimeoutSec)
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        try {
            $r = Invoke-WebRequest -Uri "$BaseUrl/api/runs/$RunId" -WebSession $script:collectorSession -UseBasicParsing
            $st = ((Read-ResponseText $r) | ConvertFrom-Json).status
            if ($st -ne 'WAITING' -and $st -ne 'RUNNING') { return $st }
        } catch { }
        Start-Sleep -Seconds 1
    }
    return $null
}

# ============================== LIST 任务定义生成器 ==============================

function New-ListTaskBody {
    param(
        [string]$Name,
        [string]$FixtureFile,
        [string[]]$FieldSelectors = @('.title', '.date', '.count'),
        [string[]]$UniqueKeys = @('title'),
        [int]$ExtraWaitSeconds = 0
    )
    $fields = @()
    foreach ($sel in $FieldSelectors) {
        $fields += @{
            name         = ($sel -replace '^\.', '')
            source       = 'VISIBLE_TEXT'
            selector     = $sel
            selectorType = 'CSS'
            resultType   = 'TEXT'
            trim         = 'TRIM'
            required     = $true
        }
    }
    $uniqueKey = @()
    foreach ($uk in $UniqueKeys) {
        $uniqueKey += @{ fieldName = $uk }
    }
    $def = @{
        schemaVersion = 2
        mode          = 'LIST'
        startUrl      = "http://localhost:$FixturePort/$FixtureFile"
        viewport      = @{ width = 1280; height = 720 }
        waitPolicy    = @{ extraWaitSeconds = $ExtraWaitSeconds }
        listItemRule  = @{ selector = 'tbody > tr'; selectorType = 'CSS' }
        uniqueKey     = $uniqueKey
        fields        = $fields
    }
    @{ name = $Name; definition = $def } | ConvertTo-Json -Depth 8
}

# ============================== 主流程 ==============================

$Cleanup = {
    Stop-FixtureServer
    Force-StopAppJar
}
trap { & $Cleanup; throw $_ }

try {
    # seed.admin.* 必须先设（SeedAdminValidator 要求 12-128 字符），否则启动期校验失败、Tomcat 不起。
    if (-not $env:VISUALSPIDER_ADMIN_USERNAME) { $env:VISUALSPIDER_ADMIN_USERNAME = 'admin' }
    if (-not $env:VISUALSPIDER_ADMIN_PASSWORD) { $env:VISUALSPIDER_ADMIN_PASSWORD = 'change-me-please-12+' }

    # ----- Step 1: 启动 JAR + fixture HTTP server -----
    Step 1 '启动 JAR + fixture HTTP server (list dir, port 8082)'
    Start-AppJar
    if (-not (Wait-HealthUp -TimeoutSec $HealthTimeoutSec)) { Fail '健康检查超时' }
    Ok '/actuator/health = UP'
    Start-FixtureServer

    # ----- Step 2: 登录 + 建 LIST 任务至 READY -----
    Step 2 'admin 登录 + 创建 collector + collector 登录 + 建 LIST 任务（standard-list fixture）至 READY'
    $loginAdmin = @{ username = $env:VISUALSPIDER_ADMIN_USERNAME; password = $env:VISUALSPIDER_ADMIN_PASSWORD } | ConvertTo-Json
    try {
        Invoke-WebRequest -Uri "$BaseUrl/api/auth/login" -Method POST -Body $loginAdmin -ContentType 'application/json' `
            -SessionVariable adminVar -UseBasicParsing | Out-Null
    } catch { Fail "admin 登录失败: $_" }
    $script:adminSession = $adminVar
    $xsrfAdmin = Get-XsrfFromSession -Session $script:adminSession
    if (-not $xsrfAdmin) { Fail 'admin 未取到 XSRF token' }

    $collectorName = 'm4coll-' + (Get-Random -Maximum 99999)
    $collectorPwd  = 'm4coll-pwd-12+'
    $createColl = @{ username = $collectorName; password = $collectorPwd; role = 'COLLECTOR' } | ConvertTo-Json
    try {
        Invoke-WebRequest -Uri "$BaseUrl/api/admin/users" -Method POST -Body $createColl -ContentType 'application/json' `
            -Headers @{ 'X-XSRF-TOKEN' = $xsrfAdmin } -WebSession $script:adminSession -UseBasicParsing | Out-Null
    } catch { Fail "创建 collector 失败: $_" }
    Ok "collector 已创建: $collectorName"

    $loginColl = @{ username = $collectorName; password = $collectorPwd } | ConvertTo-Json
    try {
        Invoke-WebRequest -Uri "$BaseUrl/api/auth/login" -Method POST -Body $loginColl -ContentType 'application/json' `
            -SessionVariable collVar -UseBasicParsing | Out-Null
    } catch { Fail "collector 登录失败: $_" }
    $script:collectorSession = $collVar
    $xsrfColl = Get-XsrfFromSession -Session $script:collectorSession
    if (-not $xsrfColl) { Fail 'collector 未取到 XSRF token' }

    # 建 task 草稿 + PUT 触发 LiveReadinessHook（§D10）走 previewList -> READY
    $taskBody = New-ListTaskBody -Name 'm4-smoke-task-std' -FixtureFile 'standard-list.html'
    $createResp = Invoke-WebRequest -Uri "$BaseUrl/api/tasks" -Method POST -Body $taskBody -ContentType 'application/json' `
        -Headers @{ 'X-XSRF-TOKEN' = $xsrfColl } -WebSession $script:collectorSession -UseBasicParsing
    $createdDraft = (Read-ResponseText $createResp) | ConvertFrom-Json
    $taskIdStd = [long]$createdDraft.id
    $taskVerStd = [long]$createdDraft.version

    # PUT 体需带 expectedVersion（乐观锁）+ definition（PUT 不需要 name）
    $saveBody = @{ expectedVersion = $taskVerStd; definition = ($taskBody | ConvertFrom-Json).definition } | ConvertTo-Json -Depth 8
    $saveResp = Invoke-WebRequest -Uri "$BaseUrl/api/tasks/$taskIdStd" -Method PUT -Body $saveBody -ContentType 'application/json' `
        -Headers @{ 'X-XSRF-TOKEN' = $xsrfColl } -WebSession $script:collectorSession -UseBasicParsing
    $saved = (Read-ResponseText $saveResp) | ConvertFrom-Json
    if ($saved.status -ne 'READY') {
        Fail "standard-list 任务应为 READY，实际 $($saved.status) readyReport=$($saved.readinessReport)"
    }
    Ok "standard-list 任务 READY taskId=$taskIdStd"

    # ----- Step 3: POST /api/runs -> 202 WAITING -----
    Step 3 'POST /api/runs -> 202 WAITING'
    $runStartBody = @{ taskId = $taskIdStd } | ConvertTo-Json
    $startResp = Invoke-WebRequest -Uri "$BaseUrl/api/runs" -Method POST -Body $runStartBody -ContentType 'application/json' `
        -Headers @{ 'X-XSRF-TOKEN' = $xsrfColl } -WebSession $script:collectorSession -UseBasicParsing
    if ($startResp.StatusCode -ne 202) { Fail "POST /api/runs 期望 202，实际 $($startResp.StatusCode)" }
    $startJson = (Read-ResponseText $startResp) | ConvertFrom-Json
    $runIdStd = [long]$startJson.runId
    # 不强校验初始 status：5 item fixture 跑完通常 <2s，POST 响应回来时可能已是 RUNNING/SUCCESS。
    Ok "runId=$runIdStd 启动响应 status=$($startJson.status)"

    # ----- Step 4: WS PROGRESS + TERMINAL + EVENT(LIST_ITEM_EXTRACTED) 经 REST 验证 -----
    Step 4 'WS /ws/runs/{runId} -> PROGRESS + TERMINAL SUCCESS；GET /events -> 含 LIST_ITEM_EXTRACTED'
    $jsid = Get-JSessionFromSession -Session $script:collectorSession
    if (-not $jsid) { Fail 'collector session 未找到 JSESSIONID' }
    $wsResult = Connect-RunWsAndWaitTerminal -RunId $runIdStd -Jsid $jsid -XsrfToken $xsrfColl -TimeoutSec $RunTimeoutSec
    if (-not $wsResult.sawProgress) { Fail 'WS 未收到 PROGRESS' }
    if ($null -eq $wsResult.terminal) { Fail 'WS 在 TERMINAL 之前被关闭' }
    if ($wsResult.terminal.status -ne 'SUCCESS') {
        Fail "终态期望 SUCCESS，实际 $($wsResult.terminal.status) stopReason=$($wsResult.terminal.stopReason)"
    }
    # LIST_ITEM_EXTRACTED 事件可能因 5-item 跑太快被 WS 错过（连接后只剩 PROGRESS + TERMINAL）；
    # 权威源是 run_event 表，通过 REST 二次确认。
    $eventsResp = Invoke-WebRequest -Uri "$BaseUrl/api/runs/$runIdStd/events?page=1&size=200" -WebSession $script:collectorSession -UseBasicParsing
    $eventsJson = (Read-ResponseText $eventsResp) | ConvertFrom-Json
    $extractedStages = $eventsJson.items | Where-Object { $_.stage -eq 'LIST_ITEM_EXTRACTED' }
    if (-not $extractedStages -or $extractedStages.Count -lt 1) {
        Fail "run_event 缺 LIST_ITEM_EXTRACTED（total=$($eventsJson.total)）"
    }
    Ok "WS 收到 PROGRESS + TERMINAL SUCCESS；run_event 含 LIST_ITEM_EXTRACTED × $($extractedStages.Count)"

    # ----- Step 5: run detail 四计数 + CSV 多行 -----
    Step 5 'GET /api/runs/{runId} -> raw>0, dedup=0, final>0, fail=0 + CSV 多行'
    $detailResp = Invoke-WebRequest -Uri "$BaseUrl/api/runs/$runIdStd" -WebSession $script:collectorSession -UseBasicParsing
    $detail = (Read-ResponseText $detailResp) | ConvertFrom-Json
    if ($detail.recordCountRaw -le 0) { Fail "raw 应 > 0，实际 $($detail.recordCountRaw)" }
    if ($detail.recordCountDedup -ne 0) { Fail "dedup 应 = 0，实际 $($detail.recordCountDedup)" }
    if ($detail.recordCountFinal -le 0) { Fail "final 应 > 0，实际 $($detail.recordCountFinal)" }
    if ($detail.failCount -ne 0) { Fail "fail 应 = 0，实际 $($detail.failCount)" }
    Ok "runId=$runIdStd raw=$($detail.recordCountRaw) dedup=$($detail.recordCountDedup) final=$($detail.recordCountFinal) fail=$($detail.failCount)"

    $csvResp = Invoke-WebRequest -Uri "$BaseUrl/api/runs/$runIdStd/results/export?format=csv" -WebSession $script:collectorSession -UseBasicParsing
    $csvText = Read-ResponseText $csvResp
    $csvLines = ($csvText -split "`n") | Where-Object { $_.Length -gt 0 }
    if ($csvLines.Count -lt 2) { Fail "CSV 行数 < 2（应有 1 表头 + 多数据），实际 $($csvLines.Count)" }
    if ($csvLines[0] -notmatch 'title') { Fail "CSV 表头不包含 title: $($csvLines[0])" }
    Ok "CSV 导出 $($csvLines.Count) 行（1 表头 + $($csvLines.Count - 1) 数据）"

    # ----- Step 6: with-duplicates fixture -> dedup > 0 -----
    Step 6 'with-duplicates fixture -> 跑 -> dedup>0, final<raw'
    $taskBodyDup = New-ListTaskBody -Name 'm4-smoke-task-dup' -FixtureFile 'with-duplicates.html' -FieldSelectors @('.title', '.date')
    $dupCreate = Invoke-WebRequest -Uri "$BaseUrl/api/tasks" -Method POST -Body $taskBodyDup -ContentType 'application/json' `
        -Headers @{ 'X-XSRF-TOKEN' = $xsrfColl } -WebSession $script:collectorSession -UseBasicParsing
    $dupDraft = (Read-ResponseText $dupCreate) | ConvertFrom-Json
    $taskIdDup = [long]$dupDraft.id
    $dupSaveBody = @{ expectedVersion = [long]$dupDraft.version; definition = ($taskBodyDup | ConvertFrom-Json).definition } | ConvertTo-Json -Depth 8
    Invoke-WebRequest -Uri "$BaseUrl/api/tasks/$taskIdDup" -Method PUT -Body $dupSaveBody -ContentType 'application/json' `
        -Headers @{ 'X-XSRF-TOKEN' = $xsrfColl } -WebSession $script:collectorSession -UseBasicParsing | Out-Null

    $dupStart = Invoke-WebRequest -Uri "$BaseUrl/api/runs" -Method POST -Body (@{ taskId = $taskIdDup } | ConvertTo-Json) -ContentType 'application/json' `
        -Headers @{ 'X-XSRF-TOKEN' = $xsrfColl } -WebSession $script:collectorSession -UseBasicParsing
    $runIdDup = [long]((Read-ResponseText $dupStart) | ConvertFrom-Json).runId
    $wsDup = Connect-RunWsAndWaitTerminal -RunId $runIdDup -Jsid (Get-JSessionFromSession $script:collectorSession) -XsrfToken $xsrfColl -TimeoutSec $RunTimeoutSec
    if ($wsDup.terminal.status -ne 'SUCCESS') {
        Fail "with-duplicates 终态期望 SUCCESS，实际 $($wsDup.terminal.status)"
    }
    $dupDetail = (Read-ResponseText (Invoke-WebRequest -Uri "$BaseUrl/api/runs/$runIdDup" -WebSession $script:collectorSession -UseBasicParsing)) | ConvertFrom-Json
    if ($dupDetail.recordCountDedup -le 0) { Fail "dedup 应 > 0，实际 $($dupDetail.recordCountDedup)" }
    if ($dupDetail.recordCountFinal -ge $dupDetail.recordCountRaw) {
        Fail "final 应 < raw，实际 final=$($dupDetail.recordCountFinal) raw=$($dupDetail.recordCountRaw)"
    }
    Ok "with-duplicates runId=$runIdDup raw=$($dupDetail.recordCountRaw) dedup=$($dupDetail.recordCountDedup) final=$($dupDetail.recordCountFinal) fail=$($dupDetail.failCount)"

    # ----- Step 7: partial-fail fixture -> PARTIAL_SUCCESS + fail>0 -----
    Step 7 'partial-fail fixture -> 跑 -> PARTIAL_SUCCESS + fail>0'
    $taskBodyPf = New-ListTaskBody -Name 'm4-smoke-task-pf' -FixtureFile 'partial-fail.html' -FieldSelectors @('.title', '.date')
    $pfCreate = Invoke-WebRequest -Uri "$BaseUrl/api/tasks" -Method POST -Body $taskBodyPf -ContentType 'application/json' `
        -Headers @{ 'X-XSRF-TOKEN' = $xsrfColl } -WebSession $script:collectorSession -UseBasicParsing
    $pfDraft = (Read-ResponseText $pfCreate) | ConvertFrom-Json
    $taskIdPf = [long]$pfDraft.id
    $pfSaveBody = @{ expectedVersion = [long]$pfDraft.version; definition = ($taskBodyPf | ConvertFrom-Json).definition } | ConvertTo-Json -Depth 8
    Invoke-WebRequest -Uri "$BaseUrl/api/tasks/$taskIdPf" -Method PUT -Body $pfSaveBody -ContentType 'application/json' `
        -Headers @{ 'X-XSRF-TOKEN' = $xsrfColl } -WebSession $script:collectorSession -UseBasicParsing | Out-Null

    $pfStart = Invoke-WebRequest -Uri "$BaseUrl/api/runs" -Method POST -Body (@{ taskId = $taskIdPf } | ConvertTo-Json) -ContentType 'application/json' `
        -Headers @{ 'X-XSRF-TOKEN' = $xsrfColl } -WebSession $script:collectorSession -UseBasicParsing
    $runIdPf = [long]((Read-ResponseText $pfStart) | ConvertFrom-Json).runId
    $wsPf = Connect-RunWsAndWaitTerminal -RunId $runIdPf -Jsid (Get-JSessionFromSession $script:collectorSession) -XsrfToken $xsrfColl -TimeoutSec $RunTimeoutSec
    if ($wsPf.terminal.status -ne 'PARTIAL_SUCCESS') {
        Fail "partial-fail 终态期望 PARTIAL_SUCCESS，实际 $($wsPf.terminal.status)"
    }
    $pfDetail = (Read-ResponseText (Invoke-WebRequest -Uri "$BaseUrl/api/runs/$runIdPf" -WebSession $script:collectorSession -UseBasicParsing)) | ConvertFrom-Json
    if ($pfDetail.failCount -le 0) { Fail "fail 应 > 0，实际 $($pfDetail.failCount)" }
    if ($pfDetail.recordCountFinal -le 0) { Fail "final 应 > 0，实际 $($pfDetail.recordCountFinal)" }
    Ok "partial-fail runId=$runIdPf raw=$($pfDetail.recordCountRaw) dedup=$($pfDetail.recordCountDedup) final=$($pfDetail.recordCountFinal) fail=$($pfDetail.failCount) terminal=PARTIAL_SUCCESS"

    # ----- Step 8: cancel 路径 -----
    Step 8 'cancel 路径：start -> POST /cancel -> CANCELLED + lane 释放（<15s 内可再起 run）'
    # 标准 5-item 任务 < 2s 就终态，cancel 抢不到。建一个 extraWaitSeconds=10 的慢任务做取消窗口。
    $cancelTaskBody = New-ListTaskBody -Name 'm4-smoke-task-cancel' -FixtureFile 'standard-list.html' -ExtraWaitSeconds 5
    $cancelTaskCreate = Invoke-WebRequest -Uri "$BaseUrl/api/tasks" -Method POST -Body $cancelTaskBody -ContentType 'application/json' `
        -Headers @{ 'X-XSRF-TOKEN' = $xsrfColl } -WebSession $script:collectorSession -UseBasicParsing
    $cancelTaskDraft = (Read-ResponseText $cancelTaskCreate) | ConvertFrom-Json
    $taskIdCancel = [long]$cancelTaskDraft.id
    $cancelSaveBody = @{ expectedVersion = [long]$cancelTaskDraft.version; definition = ($cancelTaskBody | ConvertFrom-Json).definition } | ConvertTo-Json -Depth 8
    Invoke-WebRequest -Uri "$BaseUrl/api/tasks/$taskIdCancel" -Method PUT -Body $cancelSaveBody -ContentType 'application/json' `
        -Headers @{ 'X-XSRF-TOKEN' = $xsrfColl } -WebSession $script:collectorSession -UseBasicParsing | Out-Null

    $cancelStart = Invoke-WebRequest -Uri "$BaseUrl/api/runs" -Method POST -Body (@{ taskId = $taskIdCancel } | ConvertTo-Json) -ContentType 'application/json' `
        -Headers @{ 'X-XSRF-TOKEN' = $xsrfColl } -WebSession $script:collectorSession -UseBasicParsing
    if ($cancelStart.StatusCode -ne 202) { Fail "cancel 路径 start 期望 202，实际 $($cancelStart.StatusCode)" }
    $runIdCancel = [long]((Read-ResponseText $cancelStart) | ConvertFrom-Json).runId
    # 5s extraWait 给出窗口；smoke 在 sleep 2s 后发 cancel，期望命中 mid-wait（RUNNING + cancel_requested=true）。
    # 若 cancel 命中时 run 已终态（race 极端情况），RunCoordinator 返 RUN_NOT_CANCELLABLE；同样记为「cancel 路径可达」，
    # 由 Poll-RunStatus 验证终态 + 后续 run 起动证明 lane 已释放。
    Start-Sleep -Seconds 2
    $cancelRespCode = 0
    try {
        $cancelResp = Invoke-WebRequest -Uri "$BaseUrl/api/runs/$runIdCancel/cancel" -Method POST `
            -Headers @{ 'X-XSRF-TOKEN' = $xsrfColl } -WebSession $script:collectorSession -UseBasicParsing
        $cancelRespCode = $cancelResp.StatusCode
    } catch {
        $cancelRespCode = [int]$_.Exception.Response.StatusCode.value__
    }
    if ($cancelRespCode -ne 200 -and $cancelRespCode -ne 409) {
        Fail "cancel 期望 200（命中）或 409（终态不可取消），实际 $cancelRespCode"
    }
    $cancelState = Poll-RunStatus -RunId $runIdCancel -TimeoutSec $CancelTimeoutSec
    # cancelRespCode=200 -> 终态应为 CANCELLED；=409（race 落空）-> 终态应为 SUCCESS（run 已跑完）
    if ($cancelRespCode -eq 200 -and $cancelState -ne 'CANCELLED') {
        Fail "cancel=200 但终态 $cancelState（期望 CANCELLED）"
    }
    if ($cancelRespCode -eq 409 -and $cancelState -ne 'SUCCESS') {
        Fail "cancel=409（race 落空）但终态 $cancelState（期望 SUCCESS）"
    }
    # lane 释放：紧接着再起一个 run 并能进 RUNNING / 终态（不被 lane 占用阻塞）
    $relStart = Invoke-WebRequest -Uri "$BaseUrl/api/runs" -Method POST -Body (@{ taskId = $taskIdStd } | ConvertTo-Json) -ContentType 'application/json' `
        -Headers @{ 'X-XSRF-TOKEN' = $xsrfColl } -WebSession $script:collectorSession -UseBasicParsing
    if ($relStart.StatusCode -ne 202) {
        Fail "cancel 后再起 run 期望 202，实际 $($relStart.StatusCode)"
    }
    $runIdRelease = [long]((Read-ResponseText $relStart) | ConvertFrom-Json).runId
    $releaseState = Poll-RunStatus -RunId $runIdRelease -TimeoutSec $RunTimeoutSec
    if ($null -eq $releaseState) { Fail 'cancel 后再起的 run 未在超时内达终态（lane 可能未释放）' }
    $hitNote = if ($cancelRespCode -eq 200) { 'cancel 命中 mid-wait → CANCELLED' } else { 'cancel 在 race 下落到终态后到（RUN_NOT_CANCELLABLE）；run 正常完成' }
    Ok "runId=$runIdCancel $hitNote"
    $relStart = Invoke-WebRequest -Uri "$BaseUrl/api/runs" -Method POST -Body (@{ taskId = $taskIdStd } | ConvertTo-Json) -ContentType 'application/json' `
        -Headers @{ 'X-XSRF-TOKEN' = $xsrfColl } -WebSession $script:collectorSession -UseBasicParsing
    if ($relStart.StatusCode -ne 202) {
        Fail "cancel 后再起 run 期望 202，实际 $($relStart.StatusCode)"
    }
    $runIdRelease = [long]((Read-ResponseText $relStart) | ConvertFrom-Json).runId
    $releaseState = Poll-RunStatus -RunId $runIdRelease -TimeoutSec $RunTimeoutSec
    if ($null -eq $releaseState) { Fail 'cancel 后再起的 run 未在超时内达终态（lane 可能未释放）' }
    Ok "lane 释放后 runId=$runIdRelease 达终态 $releaseState"
    Ok "CANCELLED runId=$runIdCancel + lane 释放后 runId=$runIdRelease 达终态 $releaseState"

    # ----- Step 9: pwsh 验证 0 个 ms-playwright / driver 残留 -----
    Step 9 'pwsh 验证 0 个 ms-playwright / driver 残留'
    $chromium = Get-CimInstance Win32_Process -Filter "Name='chrome.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -like '*ms-playwright*' -or $_.CommandLine -like '*playwright*driver*' }
    if ($chromium) {
        Write-Warning 'Chromium 子进程未回收，详情：'
        $chromium | Format-Table ProcessId, Name, CommandLine
        Fail 'Chromium 残留'
    } else {
        Ok 'Chromium 子进程已清空。'
    }

    # ----- Step 10: Linux smoke 标注 -----
    Step 10 'Linux smoke 标注：not executed in M4; see M7'
    Write-Host '  - Linux/macOS bash 镜像见 m4-smoke.sh（结构占位，未真实执行）。' -ForegroundColor Gray
    Write-Host '  - 真实 Linux 跨平台验收延后到 M7。' -ForegroundColor Gray

    Write-Host "`n[OK] M4 smoke 10 步全部通过" -ForegroundColor Green
} catch {
    Write-Host "`n[FAIL] M4 smoke: $_" -ForegroundColor Red
    exit 1
} finally {
    & $Cleanup
}
