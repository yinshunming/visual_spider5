#!/usr/bin/env pwsh
# M6 smoke (Windows / pwsh) — 占位 + M6-2 双 JAR 失败步骤（docs/specs/m6.md §T3）。
#
# 本脚本在 M2-M5 smoke 之上叠加 M6 安全与可靠性加固验证。完整步骤见 m6.md §T3，
# 当前实现：
#   - Step 1: 启第一个 JAR（prod profile, 无 loopback 豁免）+ fixture server
#   - Step 2: 启第二个 JAR（同库, 不同端口）-> 断言启动失败退出 + 日志含指引
#             （M6-2 SingleInstanceGuard 验收）
#   - Step 3-11: M6-3 ~ M6-6 后续工单补齐（当前为占位）
#
# 退出码：0 = 当前已实现步骤全绿；非 0 = 任一步失败。

[CmdletBinding()]
param(
    [string]$BaseUrl = $Env:M6_BASE_URL ?? 'http://localhost:8080',
    [int]$SecondPort = 8081,
    [string]$FixtureDir = $Env:M6_FIXTURE_DIR ?? "$PSScriptRoot/../../src/test/resources",
    [int]$FixturePort = 8084,
    [int]$HealthTimeoutSec = 90
)

$ErrorActionPreference = 'Stop'
$ProjectRoot = Resolve-Path (Join-Path $PSScriptRoot '..' '..')
Set-Location $ProjectRoot

# ============================== 路径与全局变量 ==============================

$JarPath = Join-Path $ProjectRoot 'target/visual-spider5-0.0.1-SNAPSHOT.jar'
$LogDir = Join-Path $ProjectRoot 'logs'
$AppOutLog = Join-Path $LogDir 'm6-app.out.log'
$AppErrLog = Join-Path $LogDir 'm6-app.err.log'
$AppPidFile = Join-Path $LogDir 'm6-app.pid'
$SecondAppOutLog = Join-Path $LogDir 'm6-second-app.out.log'
$SecondAppErrLog = Join-Path $LogDir 'm6-second-app.err.log'
$SecondAppExitCodeFile = Join-Path $LogDir 'm6-second-app.exitcode'
$FixtureLog = Join-Path $LogDir 'm6-fixture.out.log'
$FixtureErrLog = Join-Path $LogDir 'm6-fixture.err.log'
$FixturePidFile = Join-Path $LogDir 'm6-fixture.pid'

New-Item -ItemType Directory -Force -Path $LogDir | Out-Null

$script:AppProc = $null
$script:FixtureProc = $null

# ============================== 工具函数 ==============================

function Step { param([int]$n, [string]$msg) Write-Host "`n[STEP $n] $msg" -ForegroundColor Cyan }
function Ok   { param([string]$m) Write-Host "[OK]   $m" -ForegroundColor Green }
function Fail { param([string]$m) Write-Host "[FAIL] $m" -ForegroundColor Red; throw $m }
function Info { param([string]$m) Write-Host "[INFO] $m" -ForegroundColor Gray }

function Wait-AppHealthy {
    param([string]$Url, [int]$TimeoutSec)
    for ($i = 0; $i -lt ($TimeoutSec * 2); $i++) {
        try {
            $r = Invoke-WebRequest "$Url/actuator/health" -UseBasicParsing -TimeoutSec 5
            if ($r.StatusCode -eq 200) {
                $body = ($r.Content | ConvertFrom-Json)
                if ($body.status -eq 'UP') { return }
            }
        } catch { Start-Sleep -Milliseconds 500 }
    }
    Fail "actuator/health 未在 ${TimeoutSec}s 内 UP"
}

function Cleanup {
    if ($script:FixtureProc -and -not $script:FixtureProc.HasExited) {
        Info '停止 fixture HTTP server'
        Stop-Process -Id $script:FixtureProc.Id -Force -ErrorAction SilentlyContinue
    }
    if ($script:AppProc -and -not $script:AppProc.HasExited) {
        Info '停止主 JAR'
        Stop-Process -Id $script:AppProc.Id -Force -ErrorAction SilentlyContinue
    }
}

trap {
    Cleanup
    Fail "未捕获异常: $_"
}

# ============================== 主流程 ==============================

try {

    # ----- Step 1: 启主 JAR（prod profile, 无 loopback 豁免）+ fixture HTTP server -----
    Step 1 "启主 JAR（prod profile, 无 loopback 豁免）+ fixture HTTP server"
    if (-not (Test-Path $JarPath)) {
        Info 'JAR 缺失,先 ./mvnw package -DskipTests 构建'
        & ./mvnw -q -o package -DskipTests | Out-Null
        if (-not (Test-Path $JarPath)) { Fail 'JAR 构建失败' }
    }
    $script:AppProc = Start-Process -FilePath 'java' -ArgumentList @(
        '-jar', $JarPath,
        '--spring.profiles.active=smoke',
        '--visualbrowser.target-url.allow-loopback=false',
        "--server.port=$($BaseUrl -replace 'http://localhost:', '')"
    ) -PassThru -RedirectStandardOutput $AppOutLog -RedirectStandardError $AppErrLog
    Set-Content -Path $AppPidFile -Value $script:AppProc.Id
    Info "主 JAR PID=$($script:AppProc.Id) 日志: $AppOutLog / $AppErrLog"

    $ssrfDir = Join-Path $FixtureDir 'ssrf'
    if (-not (Test-Path $ssrfDir)) { Fail "ssrf fixture 目录不存在: $ssrfDir" }
    $script:FixtureProc = Start-Process -FilePath 'python' -ArgumentList @(
        '-m', 'http.server', "$FixturePort", '--bind', '127.0.0.1'
    ) -PassThru -RedirectStandardOutput $FixtureLog -RedirectStandardError $FixtureErrLog `
        -WorkingDirectory $FixtureDir
    Set-Content -Path $FixturePidFile -Value $script:FixtureProc.Id
    Start-Sleep -Seconds 1
    try {
        $r = Invoke-WebRequest "http://127.0.0.1:$FixturePort/ssrf/direct-loopback.html" -UseBasicParsing
        if ($r.StatusCode -ne 200) { Fail "fixture server 未正确返回: $($r.StatusCode)" }
    } catch { Fail "fixture HTTP server 未启动: $_" }
    Ok 'fixture server 已起（主 JAR 启动中）'

    Wait-AppHealthy -Url $BaseUrl -TimeoutSec $HealthTimeoutSec
    Ok '主 JAR actuator/health UP'

    # ----- Step 2: 启第二个 JAR（同库, 不同端口） -> 断言启动失败退出 -----
    Step 2 "启第二个 JAR（同库, 不同端口） -> 断言启动失败退出 + 日志含指引文案"
    $secondProc = Start-Process -FilePath 'java' -ArgumentList @(
        '-jar', $JarPath,
        '--spring.profiles.active=smoke',
        '--visualbrowser.target-url.allow-loopback=false',
        "--server.port=$SecondPort"
    ) -PassThru -RedirectStandardOutput $SecondAppOutLog -RedirectStandardError $SecondAppErrLog `
        -Wait
    Set-Content -Path $SecondAppExitCodeFile -Value $secondProc.ExitCode
    Info "第二个 JAR 退出码=$($secondProc.ExitCode)"
    if ($secondProc.ExitCode -eq 0) {
        Fail "第二个 JAR 退出码为 0; 期望非 0 (SingleInstanceGuard 应阻止启动)"
    }
    # 检查日志含指引文案
    $combinedLog = if (Test-Path $SecondAppOutLog) {
        Get-Content $SecondAppOutLog -Raw -ErrorAction SilentlyContinue
    } else { '' }
    $combinedLog += "`n" + (if (Test-Path $SecondAppErrLog) {
        Get-Content $SecondAppErrLog -Raw -ErrorAction SilentlyContinue
    } else { '' })
    if ($combinedLog -notmatch '另一实例已持有调度锁') {
        Fail "第二个 JAR 日志未含指引文案 '另一实例已持有调度锁'; 实际日志前 30 行: $($combinedLog -split "`n" | Select-Object -First 30 | Out-String)"
    }
    Ok "第二个 JAR 退出码=$($secondProc.ExitCode) + 日志含指引文案"

    # ----- Step 3-11: M6-3 ~ M6-6 后续工单补齐 -----
    Step 3 "WS 加固 + legacy /ws/visual 删除（M6-3） — 占位, 后续工单实现"
    Step 4 "lane 崩溃检测/重建 + health 真实化（M6-4） — 占位"
    Step 5 "压测形态 + RunLimits 收敛（M6-5） — 占位"
    Step 6 "指标/日志/权限/保留审计（M6-6） — 占位"
    Step 7 "指标可查 + LogSanityIT + 权限矩阵 + retention.days admin REST — 占位"
    Step 8 "改 retention.days 后清理按新值执行 — 占位"
    Step 9 "日志尾部扫描哨兵字符串 — 占位"
    Step 10 "收尾进程数核对 — 占位"
    Step 11 "Linux 标注输出 — 占位"

    Ok 'M6 smoke 当前已实现步骤全绿'

} finally {
    Cleanup
}
