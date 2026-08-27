#!/usr/bin/env pwsh
# Visual Spider 5 — Windows 生产停止脚本（M7-1 / docs/specs/m7.md D2）
#
# 用途：按 logs\app.pid 停止 java 进程。
#
# Windows 上对 console java.exe 的"优雅"语义有限（Spring Boot 无内置 /actuator/shutdown;
# taskkill 不带 /F 对非 GUI 控制台进程的 WM_CLOSE 处理依赖 OS 版本）。本脚本采取的最佳折中：
#   1) taskkill /pid X (无 /F)  发 WM_CLOSE;等 GracePeriodSec 默认 30s
#   2) 超时后 taskkill /pid X /F  强杀;等 ForceKillWaitSec 默认 10s
# 因此 JVM shutdown hook / Spring PreDestroy 不保证运行;SingleInstanceGuard 释放依赖 PG 会话超时
# （advisory lock 在连接断开后由 PG 回收,无需 JVM 主动调用 pg_advisory_unlock）。
#
# 退出码：
#   0 = 已停止（或未运行 / PID 陈旧）
#   1 = 参数缺失或 taskkill 不可用
#   2 = 强杀超时（仍残留）

[CmdletBinding()]
param(
    [string]$InstallDir = $Env:VISUALSPIDER_INSTALL_DIR ?? 'C:\visual-spider',
    [int]$GracePeriodSec = 30,
    [int]$ForceKillWaitSec = 10
)

$ErrorActionPreference = 'Stop'

function Step { param([string]$m) Write-Host "`n[STEP] $m" -ForegroundColor Cyan }
function Ok   { param([string]$m) Write-Host "[OK]   $m" -ForegroundColor Green }
function Info { param([string]$m) Write-Host "[INFO] $m" -ForegroundColor Gray }
function Fail { param([int]$code, [string]$m) Write-Host "[FAIL] $m" -ForegroundColor Red; exit $code }

if ([string]::IsNullOrWhiteSpace($InstallDir)) { Fail 1 "InstallDir 不能为空" }
if ($GracePeriodSec -le 0)                     { Fail 1 "GracePeriodSec 必须 > 0" }

$logsDir = Join-Path $InstallDir 'logs'
$pidFile = Join-Path $logsDir 'app.pid'

# ============================== 未运行 ==============================

if (-not (Test-Path -LiteralPath $pidFile)) {
    Ok "未运行（无 PID 文件）"
    exit 0
}

$raw = Get-Content -LiteralPath $pidFile -Raw -ErrorAction SilentlyContinue
$pidStr = if ($raw) { $raw.Trim() } else { '' }
if ($pidStr -notmatch '^\d+$') {
    Warn "PID 文件内容非法: '$pidStr'。清理后退出。"
    Remove-Item -LiteralPath $pidFile -Force -ErrorAction SilentlyContinue
    exit 0
}
$procId = [int]$pidStr

$proc = Get-Process -Id $procId -ErrorAction SilentlyContinue
if (-not $proc) {
    Ok "PID 文件指向的进程不存在（pid=$procId,陈旧文件）。已清理。"
    Remove-Item -LiteralPath $pidFile -Force -ErrorAction SilentlyContinue
    exit 0
}

# ============================== 优雅停止 ==============================
# Windows 上对 console java.exe 的"优雅"语义有限：
#   1) `taskkill /pid X` (无 /F) 发 WM_CLOSE,console java 默认不响应,Spring shutdown hook 不一定跑
#   2) Spring Boot 没有内置 /actuator/shutdown 端点（M6 已合 main）
#   3) 现实可达的最佳折中:先 taskkill 不带 /F,等 GracePeriodSec;再 /F 强杀
# 因此文档 / 脚本注释需诚实标注"非真正 graceful,Spring Boot advisory lock 释放依赖 PG 会话超时"。

Step "优雅尝试 taskkill /pid $procId（GracePeriodSec=$GracePeriodSec）"
$taskkillExe = (Get-Command taskkill.exe -ErrorAction SilentlyContinue).Source
if (-not $taskkillExe) {
    Fail 1 "taskkill.exe 未找到（应在 \$env:SystemRoot\System32 下）"
}
& taskkill.exe /pid $procId 2>&1 | Out-Null
$tkExit = $LASTEXITCODE
# exit 0 = signaled; 128 = already gone; 1 = not found; 非 0 且进程仍存 → 视为失败
if ($tkExit -ne 0 -and $tkExit -ne 128 -and (Get-Process -Id $procId -ErrorAction SilentlyContinue)) {
    Write-Host "[WARN] taskkill 退出码 $tkExit（仍存活）,将走强杀。" -ForegroundColor Yellow
}

$deadline = (Get-Date).AddSeconds($GracePeriodSec)
while ((Get-Date) -lt $deadline) {
    if (-not (Get-Process -Id $procId -ErrorAction SilentlyContinue)) { break }
    Start-Sleep -Seconds 1
}

if (Get-Process -Id $procId -ErrorAction SilentlyContinue) {
    Write-Host "[WARN] ${GracePeriodSec}s 内未退出,执行强杀（/F）。" -ForegroundColor Yellow
    & taskkill.exe /pid $procId /F 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0 -and $LASTEXITCODE -ne 128) {
        Fail 2 "强杀失败（taskkill 退出码 $LASTEXITCODE）"
    }
    $forceDeadline = (Get-Date).AddSeconds($ForceKillWaitSec)
    while ((Get-Date) -lt $forceDeadline) {
        if (-not (Get-Process -Id $procId -ErrorAction SilentlyContinue)) { break }
        Start-Sleep -Seconds 1
    }
    if (Get-Process -Id $procId -ErrorAction SilentlyContinue) {
        Fail 2 "强杀后 ${ForceKillWaitSec}s 仍存活（pid=$procId）。请人工检查。"
    }
}

Remove-Item -LiteralPath $pidFile -Force -ErrorAction SilentlyContinue
Ok "已停止 pid=$procId"
exit 0
