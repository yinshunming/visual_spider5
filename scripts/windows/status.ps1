#!/usr/bin/env pwsh
# Visual Spider 5 — Windows 生产状态脚本（M7-1 / docs/specs/m7.md D2）
#
# 用途：报告 PID 存活 + /actuator/health。不会修改任何状态。
#
# 退出码：
#   0 = 进程在跑且 health UP
#   1 = 进程在跑但 health 异常 / 不通
#   2 = 未运行
#   3 = 参数缺失

[CmdletBinding()]
param(
    [string]$InstallDir = $Env:VISUALSPIDER_INSTALL_DIR ?? 'C:\visual-spider',
    [int]$HealthTimeoutSec = 5
)

$ErrorActionPreference = 'Stop'

function Step { param([string]$m) Write-Host "`n[STEP] $m" -ForegroundColor Cyan }
function Ok   { param([string]$m) Write-Host "[OK]   $m" -ForegroundColor Green }
function Warn { param([string]$m) Write-Host "[WARN] $m" -ForegroundColor Yellow }
function Fail { param([int]$code, [string]$m) Write-Host "[FAIL] $m" -ForegroundColor Red; exit $code }

if ([string]::IsNullOrWhiteSpace($InstallDir)) { Fail 3 "InstallDir 不能为空" }
if ($HealthTimeoutSec -le 0)                   { Fail 3 "HealthTimeoutSec 必须 > 0" }

$logsDir = Join-Path $InstallDir 'logs'
$pidFile = Join-Path $logsDir 'app.pid'
$serverPort = if ($Env:SERVER_PORT) { [int]$Env:SERVER_PORT } else { 8080 }
$healthUrl = "http://localhost:$serverPort/actuator/health"

# ============================== 进程 ==============================

if (-not (Test-Path -LiteralPath $pidFile)) {
    Write-Host "[STATUS] 未运行（无 PID 文件:$pidFile）" -ForegroundColor Yellow
    exit 2
}

$raw = Get-Content -LiteralPath $pidFile -Raw -ErrorAction SilentlyContinue
$pidStr = if ($raw) { $raw.Trim() } else { '' }
if ($pidStr -notmatch '^\d+$') {
    Write-Host "[STATUS] 未运行（PID 文件内容非法: '$pidStr'）" -ForegroundColor Yellow
    exit 2
}
$procId = [int]$pidStr
$proc = Get-Process -Id $procId -ErrorAction SilentlyContinue
if (-not $proc) {
    Write-Host "[STATUS] 未运行（PID 指向的进程不存在: pid=$procId）" -ForegroundColor Yellow
    exit 2
}
Write-Host "[STATUS] 运行中（pid=$procId, process=$($proc.ProcessName), startTime=$($proc.StartTime.ToString('s'))）" -ForegroundColor Green

# ============================== 健康 ==============================

$body = $null
try {
    $r = Invoke-WebRequest -Uri $healthUrl -UseBasicParsing -TimeoutSec $HealthTimeoutSec
    $body = if ($r.Content -is [byte[]]) { [System.Text.Encoding]::UTF8.GetString($r.Content) } else { [string]$r.Content }
    if ($r.StatusCode -eq 200 -and $body -match '"status":"UP"') {
        Write-Host "[STATUS] health UP（$healthUrl）" -ForegroundColor Green
        Write-Host "         body = $body"
        exit 0
    } else {
        Write-Host "[STATUS] health 异常（HTTP $($r.StatusCode): $body）" -ForegroundColor Yellow
        exit 1
    }
} catch {
    Write-Host "[STATUS] health 不可达（$($_.Exception.Message)）" -ForegroundColor Yellow
    exit 1
}
