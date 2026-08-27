#!/usr/bin/env pwsh
# Visual Spider 5 — Windows 生产日志查看脚本（M7-1 / docs/specs/m7.md D2）
#
# 用途：tail logs\app.out.log 与 logs\app.err.log。
#
# 退出码：
#   0 = 成功
#   1 = 参数缺失
#   2 = 日志文件不存在

[CmdletBinding()]
param(
    [string]$InstallDir = $Env:VISUALSPIDER_INSTALL_DIR ?? 'C:\visual-spider',
    [int]$Lines = 100,
    [switch]$Follow
)

$ErrorActionPreference = 'Stop'

function Step { param([string]$m) Write-Host "`n[STEP] $m" -ForegroundColor Cyan }
function Ok   { param([string]$m) Write-Host "[OK]   $m" -ForegroundColor Green }
function Info { param([string]$m) Write-Host "[INFO] $m" -ForegroundColor Gray }
function Fail { param([int]$code, [string]$m) Write-Host "[FAIL] $m" -ForegroundColor Red; exit $code }

if ([string]::IsNullOrWhiteSpace($InstallDir)) { Fail 1 "InstallDir 不能为空" }
if ($Lines -le 0)                               { Fail 1 "Lines 必须 > 0" }

$logsDir = Join-Path $InstallDir 'logs'
$outLog  = Join-Path $logsDir 'app.out.log'
$errLog  = Join-Path $logsDir 'app.err.log'

if (-not (Test-Path -LiteralPath $outLog) -or -not (Test-Path -LiteralPath $errLog)) {
    Fail 2 "日志文件不存在。请先 .\start.ps1。"
}

if ($Follow) {
    Info "跟随模式（Ctrl+C 退出）。两个日志分别: $outLog / $errLog"
    # 后台 job 跟随 err.log,前台 Wait 跟随 out.log;Ctrl+C 退出。
    $errJob = Start-Job -ScriptBlock {
        param($p, $n)
        Get-Content -LiteralPath $p -Tail $n -Wait
    } -ArgumentList $errLog, $Lines
    try {
        Write-Host "--- app.out.log ---" -ForegroundColor Cyan
        Get-Content -LiteralPath $outLog -Tail $Lines -Wait
    } finally {
        Stop-Job -Job $errJob -ErrorAction SilentlyContinue
        Remove-Job -Job $errJob -ErrorAction SilentlyContinue
    }
} else {
    Write-Host "=== app.out.log (tail $Lines) ===" -ForegroundColor Cyan
    Get-Content -LiteralPath $outLog -Tail $Lines
    Write-Host ""
    Write-Host "=== app.err.log (tail $Lines) ===" -ForegroundColor Cyan
    Get-Content -LiteralPath $errLog -Tail $Lines
}
exit 0
