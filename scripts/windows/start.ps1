#!/usr/bin/env pwsh
# Visual Spider 5 — Windows 生产启动脚本（M7-1 / docs/specs/m7.md D2）
#
# 用途：后台启动 JAR,写 PID 到 logs\app.pid,stdout/stderr 到 logs\app.out.log / app.err.log,
#       启动后读最近日志确认。
#       部署资产,不依赖仓库源码;参数化 JAR 路径与端口。
#
# 环境变量：启动时若 <InstallDir>\config\visual-spider.env 存在,读取 KEY=VALUE 注入当前进程
#           （仅当同名变量未设时;空行 / # 注释 / 无 = 行忽略;值可用双引号包裹）。
#
# 退出码：
#   0 = 启动成功（actuator/health UP）
#   1 = 参数缺失或值非法
#   2 = 已有实例在跑（拒绝重复启动）
#   3 = 启动失败（健康检查超时 / 进程退出）

[CmdletBinding()]
param(
    [string]$InstallDir = $Env:VISUALSPIDER_INSTALL_DIR ?? 'C:\visual-spider',
    [string]$JarPath = $Env:VISUALSPIDER_JAR_PATH ?? (Join-Path $InstallDir 'app.jar'),
    [int]$HealthTimeoutSec = 90,
    [int]$TailLines = 80
)

$ErrorActionPreference = 'Stop'

function Step { param([string]$m) Write-Host "`n[STEP] $m" -ForegroundColor Cyan }
function Ok   { param([string]$m) Write-Host "[OK]   $m" -ForegroundColor Green }
function Info { param([string]$m) Write-Host "[INFO] $m" -ForegroundColor Gray }
function Fail { param([int]$code, [string]$m) Write-Host "[FAIL] $m" -ForegroundColor Red; exit $code }

# ============================== 参数校验 ==============================

if ([string]::IsNullOrWhiteSpace($InstallDir)) { Fail 1 "InstallDir 不能为空" }
if ([string]::IsNullOrWhiteSpace($JarPath))   { Fail 1 "JarPath 不能为空" }
if ($HealthTimeoutSec -le 0)                  { Fail 1 "HealthTimeoutSec 必须 > 0" }

# ============================== 加载 .env ==============================
# 从 $InstallDir\config\visual-spider.env 读取 KEY=VALUE,只设置当前进程未设的同名变量。
# 已存在的系统/会话环境变量优先;空行 / # 注释 / 无 = 行忽略。
$envFile = Join-Path $InstallDir 'config\visual-spider.env'
if (Test-Path -LiteralPath $envFile) {
    Get-Content -LiteralPath $envFile | ForEach-Object {
        $line = $_.Trim()
        if ([string]::IsNullOrEmpty($line)) { return }
        if ($line.StartsWith('#'))            { return }
        $eq = $line.IndexOf('=')
        if ($eq -le 0)                        { return }
        $key = $line.Substring(0, $eq).Trim()
        $val = $line.Substring($eq + 1).Trim()
        # 去引号
        if ($val.Length -ge 2 -and $val[0] -eq '"' -and $val[-1] -eq '"') { $val = $val.Substring(1, $val.Length - 2) }
        # 已存在的环境变量优先（系统 > 用户 > .env）
        if (-not [System.Environment]::GetEnvironmentVariable($key, 'Process')) {
            [System.Environment]::SetEnvironmentVariable($key, $val, 'Process')
        }
    }
    Info "已加载 .env: $envFile"
} else {
    Info "未找到 .env（$envFile）;依赖系统 / 会话环境变量。"
}

# ============================== 路径 ==============================

$logsDir = Join-Path $InstallDir 'logs'
$pidFile = Join-Path $logsDir 'app.pid'
$outLog  = Join-Path $logsDir 'app.out.log'
$errLog  = Join-Path $logsDir 'app.err.log'

New-Item -ItemType Directory -Force -Path $logsDir | Out-Null

if (-not (Test-Path -LiteralPath $JarPath)) {
    Fail 1 "未找到 JAR: $JarPath。请先运行 .\install.ps1。"
}

# ============================== 端口 ==============================

$serverPort = if ($Env:SERVER_PORT) { [int]$Env:SERVER_PORT } else { 8080 }

# ============================== 重复启动检查 ==============================

if (Test-Path -LiteralPath $pidFile) {
    $existing = Get-Content -LiteralPath $pidFile -Raw -ErrorAction SilentlyContinue
    $existing = if ($existing) { $existing.Trim() } else { '' }
    if ($existing -match '^\d+$') {
        $existingProc = Get-Process -Id ([int]$existing) -ErrorAction SilentlyContinue
        if ($existingProc -and ($existingProc.ProcessName -eq 'java' -or $existingProc.Path -like '*java*')) {
            Fail 2 "已有实例在跑（pid=$existing）。如需重启请先 .\stop.ps1。"
        } else {
            Info "陈旧 PID 文件（pid=$existing 已不存在）,清理后继续。"
            Remove-Item -LiteralPath $pidFile -Force -ErrorAction SilentlyContinue
        }
    }
}

# ============================== 启动 ==============================

Step "启动 java -jar $JarPath（端口 $serverPort）"
$proc = Start-Process -FilePath 'java' `
    -ArgumentList @('-jar', $JarPath) `
    -RedirectStandardOutput $outLog `
    -RedirectStandardError  $errLog `
    -NoNewWindow -PassThru
$proc.Id | Out-File -FilePath $pidFile -Encoding ascii -NoNewline
Info "已启动 pid=$($proc.Id),日志: $outLog / $errLog"

# ============================== 健康检查 ==============================

Step "等待 actuator/health UP（最长 ${HealthTimeoutSec}s）"
$healthUrl = "http://localhost:$serverPort/actuator/health"
$deadline = (Get-Date).AddSeconds($HealthTimeoutSec)
$up = $false
while ((Get-Date) -lt $deadline) {
    try {
        $r = Invoke-WebRequest -Uri $healthUrl -UseBasicParsing -TimeoutSec 5
        $body = if ($r.Content -is [byte[]]) { [System.Text.Encoding]::UTF8.GetString($r.Content) } else { [string]$r.Content }
        if ($r.StatusCode -eq 200 -and $body -match '"status":"UP"') {
            $up = $true; break
        }
    } catch {
        # 进程可能仍在启动,继续轮询
    }
    if ($proc.HasExited) {
        Fail 3 "java 进程已退出（exit code=$($proc.ExitCode)）。最近日志:`n$((Get-Content -LiteralPath $errLog -Tail 50) -join "`n")"
    }
    Start-Sleep -Seconds 1
}
if (-not $up) {
    Fail 3 "actuator/health 在 ${HealthTimeoutSec}s 内未 UP（url=$healthUrl）。最近日志:`n$((Get-Content -LiteralPath $errLog -Tail 50) -join "`n")"
}
Ok "actuator/health UP"

# ============================== 最近日志 ==============================

Info "最近日志（tail $TailLines 行,out.log）:"
Get-Content -LiteralPath $outLog -Tail $TailLines
exit 0
