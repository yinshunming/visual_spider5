#!/usr/bin/env pwsh
# Visual Spider 5 — Windows 端到端 smoke（M1-5 spec T3）
# 8 步：
#   1. 启动 PG（要求本机已运行）+ 跑 mvnw -Ppg-it package
#   2. 启动 JAR，等待 /actuator/health 返回 UP
#   3. admin 登录 → 拿到 JSESSIONID + XSRF-TOKEN
#   4. 创建采集人员账号 + 采集人员登录
#   5. 创建任务草稿 + 列出 + 保存
#   6. 第二个采集人员访问任务 → 403
#   7. 第一个 session 第二次保存同任务（带旧 expectedVersion）→ 409
#   8. 删除任务 → listMine 不再列出

[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://localhost:8080',
    [int]$HealthTimeoutSec = 60
)

$ErrorActionPreference = 'Stop'
$ProjectRoot = Resolve-Path (Join-Path $PSScriptRoot '..' '..')
Set-Location $ProjectRoot

$cookieJar = New-TemporaryFile
$cookieJar2 = New-TemporaryFile

function Step { param([int]$n, [string]$msg) Write-Host "`n[STEP $n] $msg" -ForegroundColor Cyan }
function Ok   { Write-Host "[OK]   $args" -ForegroundColor Green }
function Fail { param([string]$m) Write-Host "[FAIL] $m" -ForegroundColor Red; throw $m }
function Info { Write-Host "[INFO] $args" -ForegroundColor Gray }

function Get-XsrfToken {
    param([string]$JarPath)
    foreach ($line in Get-Content $JarPath -ErrorAction SilentlyContinue) {
        if ($line -match 'XSRF-TOKEN\s+([A-Za-z0-9._-]+)') {
            return $Matches[1]
        }
    }
    return $null
}

# ---- 1. 检查 PG ----
Step 1 '检查 PostgreSQL 与构建 JAR'
if (-not $env:VISUALSPIDER_ADMIN_USERNAME) { $env:VISUALSPIDER_ADMIN_USERNAME = 'admin' }
if (-not $env:VISUALSPIDER_ADMIN_PASSWORD) { $env:VISUALSPIDER_ADMIN_PASSWORD = 'change-me-please-12+' }
if (-not $env:VISUALSPIDER_DATASOURCE_URL) { $env:VISUALSPIDER_DATASOURCE_URL = 'jdbc:postgresql://localhost:5432/visualspider_test' }
if (-not $env:VISUALSPIDER_DATASOURCE_USERNAME) { $env:VISUALSPIDER_DATASOURCE_USERNAME = 'visualspider' }
if (-not $env:VISUALSPIDER_DATASOURCE_PASSWORD) { $env:VISUALSPIDER_DATASOURCE_PASSWORD = 'visualspider' }
Info "PG DSN: $env:VISUALSPIDER_DATASOURCE_URL"

if (-not (Test-Path 'target/visual-spider5-0.0.1-SNAPSHOT.jar')) {
    Info '构建 JAR...'
    & ./mvnw package -DskipTests | Out-Null
}

# ---- 2. 启动 JAR ----
Step 2 '启动应用并等待健康检查'
New-Item -ItemType Directory -Force -Path logs | Out-Null
$proc = Start-Process -FilePath 'java' -ArgumentList @('-jar', 'target/visual-spider5-0.0.1-SNAPSHOT.jar') `
    -RedirectStandardOutput 'logs/app.out.log' -RedirectStandardError 'logs/app.err.log' -NoNewWindow -PassThru
Info "PID: $($proc.Id)"

$healthOk = $false
for ($i = 0; $i -lt $HealthTimeoutSec; $i++) {
    Start-Sleep -Seconds 1
    try {
        $r = Invoke-WebRequest -Uri "$BaseUrl/actuator/health" -UseBasicParsing -TimeoutSec 5 -ErrorAction Stop
        if ($r.StatusCode -eq 200) {
            $body = ($r.Content | ConvertFrom-Json)
            if ($body.status -eq 'UP') { $healthOk = $true; break }
        }
    } catch {
        # 启动中；继续等待
    }
}
if (-not $healthOk) {
    Fail '健康检查超时'
}
Ok '/actuator/health = UP'

# ---- 3. admin 登录 ----
Step 3 'admin 登录'
$body = @{ username = $env:VISUALSPIDER_ADMIN_USERNAME; password = $env:VISUALSPIDER_ADMIN_PASSWORD } | ConvertTo-Json
try {
    Invoke-WebRequest -Uri "$BaseUrl/api/auth/login" -Method POST -Body $body -ContentType 'application/json' `
        -WebSession $cookieJar -UseBasicParsing | Out-Null
} catch { Fail "admin 登录失败: $_" }
$xsrf = Get-XsrfToken -JarPath $cookieJar.FullName
if (-not $xsrf) { Fail '未找到 XSRF token' }
Ok "admin 已登录，XSRF 长度 = $($xsrf.Length)"

# ---- 4. 创建采集人员 + 采集人员登录 ----
Step 4 '创建采集人员 + 登录'
$collectorUser = 'collector-' + (Get-Random -Maximum 9999)
$collectorPwd  = 'collector-pwd-12+'
$createBody = @{ username = $collectorUser; password = $collectorPwd; role = 'COLLECTOR' } | ConvertTo-Json
try {
    Invoke-WebRequest -Uri "$BaseUrl/api/admin/users" -Method POST -Body $createBody -ContentType 'application/json' `
        -Headers @{ 'X-XSRF-TOKEN' = $xsrf } -WebSession $cookieJar -UseBasicParsing | Out-Null
} catch { Fail "创建采集人员失败: $_" }
Ok "创建采集人员: $collectorUser"

$loginBody = @{ username = $collectorUser; password = $collectorPwd } | ConvertTo-Json
try {
    Invoke-WebRequest -Uri "$BaseUrl/api/auth/login" -Method POST -Body $loginBody -ContentType 'application/json' `
        -WebSession $cookieJar2 -UseBasicParsing | Out-Null
} catch { Fail "采集人员登录失败: $_" }
$xsrf2 = Get-XsrfToken -JarPath $cookieJar2.FullName
Ok '采集人员已登录'

# ---- 5. 创建任务 + 列出 + 保存 ----
Step 5 '创建任务 + 列出 + 保存'
$taskBody = @{
    name = 'smoke-task'
    definition = @{
        schemaVersion = 1
        mode = 'SinglePage'
        startUrl = 'https://example.com'
        viewport = @{ width = 1280; height = 720 }
        fields = @()
    }
} | ConvertTo-Json -Depth 6
$taskResp = Invoke-WebRequest -Uri "$BaseUrl/api/tasks" -Method POST -Body $taskBody -ContentType 'application/json' `
    -Headers @{ 'X-XSRF-TOKEN' = $xsrf2 } -WebSession $cookieJar2 -UseBasicParsing
$task = $taskResp.Content | ConvertFrom-Json
Ok "创建任务 id=$($task.id) version=$($task.version)"

$listResp = Invoke-WebRequest -Uri "$BaseUrl/api/tasks" -Method GET -WebSession $cookieJar2 -UseBasicParsing
$list = $listResp.Content | ConvertFrom-Json
if ($list.Count -lt 1) { Fail 'listMine 未返回刚创建的任务' }
Ok "listMine 返回 $($list.Count) 条"

$saveBody = @{ expectedVersion = $task.version; definition = $task.definition } | ConvertTo-Json -Depth 6
$saveResp = Invoke-WebRequest -Uri "$BaseUrl/api/tasks/$($task.id)" -Method PUT -Body $saveBody -ContentType 'application/json' `
    -Headers @{ 'X-XSRF-TOKEN' = $xsrf2 } -WebSession $cookieJar2 -UseBasicParsing
$saved = $saveResp.Content | ConvertFrom-Json
if ($saved.version -le $task.version) { Fail '保存后 version 未递增' }
Ok "保存成功 version=$($saved.version)"

# ---- 6. 第二个采集人员访问 → 403 ----
Step 6 '跨用户访问 → 403'
$otherUser = 'other-' + (Get-Random -Maximum 9999)
$otherPwd  = 'other-user-pwd-12+'
$createOther = @{ username = $otherUser; password = $otherPwd; role = 'COLLECTOR' } | ConvertTo-Json
Invoke-WebRequest -Uri "$BaseUrl/api/admin/users" -Method POST -Body $createOther -ContentType 'application/json' `
    -Headers @{ 'X-XSRF-TOKEN' = $xsrf } -WebSession $cookieJar -UseBasicParsing | Out-Null
$cookieJar3 = New-TemporaryFile
$loginOther = @{ username = $otherUser; password = $otherPwd } | ConvertTo-Json
Invoke-WebRequest -Uri "$BaseUrl/api/auth/login" -Method POST -Body $loginOther -ContentType 'application/json' `
    -WebSession $cookieJar3 -UseBasicParsing | Out-Null
$xsrf3 = Get-XsrfToken -JarPath $cookieJar3.FullName
try {
    $resp = Invoke-WebRequest -Uri "$BaseUrl/api/tasks/$($task.id)" -Method GET -WebSession $cookieJar3 -UseBasicParsing
    Fail "预期 403，实际 $($resp.StatusCode)"
} catch {
    $status = $_.Exception.Response.StatusCode.value__
    if ($status -ne 403) { Fail "预期 403，实际 $status" }
}
Ok '跨用户访问被拒（403）'

# ---- 7. 旧 expectedVersion 保存 → 409 ----
Step 7 '乐观锁：旧版本保存 → 409'
$staleSave = @{ expectedVersion = $task.version; definition = $task.definition } | ConvertTo-Json -Depth 6
try {
    $resp = Invoke-WebRequest -Uri "$BaseUrl/api/tasks/$($task.id)" -Method PUT -Body $staleSave -ContentType 'application/json' `
        -Headers @{ 'X-XSRF-TOKEN' = $xsrf2 } -WebSession $cookieJar2 -UseBasicParsing
    Fail "预期 409，实际 $($resp.StatusCode)"
} catch {
    $status = $_.Exception.Response.StatusCode.value__
    if ($status -ne 409) { Fail "预期 409，实际 $status" }
}
Ok '乐观锁冲突正确返回 409'

# ---- 8. 删除任务 → listMine 不再列出 ----
Step 8 '删除任务'
Invoke-WebRequest -Uri "$BaseUrl/api/tasks/$($task.id)" -Method DELETE `
    -Headers @{ 'X-XSRF-TOKEN' = $xsrf2 } -WebSession $cookieJar2 -UseBasicParsing | Out-Null
$listAfter = Invoke-WebRequest -Uri "$BaseUrl/api/tasks" -Method GET -WebSession $cookieJar2 -UseBasicParsing
$afterList = $listAfter.Content | ConvertFrom-Json
if ($afterList | Where-Object { $_.id -eq $task.id }) { Fail '删除后任务仍在 listMine' }
Ok '删除成功，listMine 不再列出'

# ---- 清理 ----
Write-Host "`n[OK] smoke 8 步全部通过" -ForegroundColor Green
try { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue } catch {}
Remove-Item $cookieJar, $cookieJar2, $cookieJar3 -Force -ErrorAction SilentlyContinue
