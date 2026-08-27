#!/usr/bin/env pwsh
# Visual Spider 5 — Windows 生产环境预检脚本（M7-1 / docs/specs/m7.md D2）
#
# 用途：在 start 之前核对 JDK、PG、端口、磁盘、Chromium 是否就绪。
#       部署资产,不依赖仓库源码。
#
# 检查项：
#   1) java >= 21
#   2) VISUALSPIDER_DATASOURCE_URL 可解析 + psql 可连通
#   3) server.port（默认 8080）未被占用
#   4) 安装根目录剩余磁盘空间 >= 500MB
#   5) Chromium 已安装（ms-playwright 目录存在）
#
# 退出码：
#   0 = 全通过
#   1 = 参数缺失或值非法
#   2 = JDK 不达标
#   3 = PostgreSQL 不达标（未配 / 不可达）
#   4 = 端口被占用
#   5 = 磁盘空间不足
#   6 = Chromium 未安装

[CmdletBinding()]
param(
    [string]$InstallDir = $Env:VISUALSPIDER_INSTALL_DIR ?? 'C:\visual-spider',
    [string]$DataSourceUrl = $Env:VISUALSPIDER_DATASOURCE_URL ?? 'jdbc:postgresql://localhost:5432/visualspider',
    [string]$DataSourceUsername = $Env:VISUALSPIDER_DATASOURCE_USERNAME ?? 'visualspider',
    [string]$DataSourcePassword = $Env:VISUALSPIDER_DATASOURCE_PASSWORD ?? '',
    [int]$ServerPort = $(if ($Env:SERVER_PORT) { [int]$Env:SERVER_PORT } else { 8080 }),
    [int]$MinDiskMb = 1024
)

$ErrorActionPreference = 'Stop'

function Step { param([string]$m) Write-Host "`n[STEP] $m" -ForegroundColor Cyan }
function Ok   { param([string]$m) Write-Host "[OK]   $m" -ForegroundColor Green }
function Warn { param([string]$m) Write-Host "[WARN] $m" -ForegroundColor Yellow }
function Fail { param([int]$code, [string]$m) Write-Host "[FAIL] $m" -ForegroundColor Red; exit $code }

# ============================== 0. 参数 ==============================

if ([string]::IsNullOrWhiteSpace($InstallDir))        { Fail 1 "InstallDir 不能为空" }
if ([string]::IsNullOrWhiteSpace($DataSourceUrl))     { Fail 1 "DataSourceUrl 不能为空" }
if ($ServerPort -le 0 -or $ServerPort -gt 65535)      { Fail 1 "ServerPort 必须 1..65535" }
if ($MinDiskMb -le 0)                                  { Fail 1 "MinDiskMb 必须 > 0" }

# ============================== 1. JDK ==============================

Step "java 版本"
if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    Fail 2 "未在 PATH 中找到 java。"
}
$javaLines = & java -version 2>&1
$javaExit = $LASTEXITCODE
$javaOut = ($javaLines | Select-Object -First 1)
if ($null -eq $javaOut) { $javaOut = '' } else { $javaOut = $javaOut.ToString() }
if ($javaExit -ne 0) { Fail 2 "java -version 执行失败: $javaOut" }
$javaMatch = [regex]::Match($javaOut, '"(\d+)(?:\.\d+)*"')
if (-not $javaMatch.Success) { Fail 2 "无法解析 java 版本: $javaOut" }
$javaMajor = [int]$javaMatch.Groups[1].Value
if ($javaMajor -lt 21) {
    Fail 2 "java 版本过低: $javaOut（需要 ≥ 21）"
}
Ok "java = $javaOut"

# ============================== 2. PostgreSQL ==============================

Step "PostgreSQL 连通性"
if (-not (Get-Command psql -ErrorAction SilentlyContinue)) {
    Fail 3 "未在 PATH 中找到 psql。请安装 PostgreSQL 16（推荐 EDB 安装器）。"
}
# 解析 jdbc:postgresql://host:port/db
if ($DataSourceUrl -notmatch '^jdbc:postgresql://([^/:]+)(?::(\d+))?(?:/([^?]+))?(\?.*)?$') {
    Fail 3 "DataSourceUrl 格式非法: $DataSourceUrl（期望 jdbc:postgresql://host:port/db）"
}
$pgHost = $Matches[1]
$pgPort = if ($Matches[2]) { [int]$Matches[2] } else { 5432 }
$pgDb   = if ($Matches[3]) { $Matches[3] }      else { 'postgres' }
Ok "解析连接: host=$pgHost port=$pgPort db=$pgDb"

# TCP 可达
$tcpTest = Test-NetConnection -ComputerName $pgHost -Port $pgPort -WarningAction SilentlyContinue -InformationLevel Quiet
if (-not $tcpTest) { Fail 3 "$pgHost`:$pgPort 不可达（TCP 连接失败）" }
Ok "$pgHost`:$pgPort TCP 可达"

# psql 登录验证（密码可能出现在 psql stderr 的某些错误消息中;失败时只报告退出码 + 行数,不打印原文）
$env:PGPASSWORD = $DataSourcePassword
$queryLines = & psql -h $pgHost -p $pgPort -U $DataSourceUsername -d $pgDb -tAc 'SELECT 1' 2>&1
$psqlExit = $LASTEXITCODE
Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue
$queryText = if ($null -eq $queryLines) { '' } else { ($queryLines -join "`n").Trim() }
if ($psqlExit -ne 0 -or $queryText -ne '1') {
    $lineCount = if ($null -eq $queryLines) { 0 } else { @($queryLines).Count }
    Fail 3 "psql 验证失败（用户 $DataSourceUsername → $pgHost`:$pgPort/$pgDb）: 退出码=$psqlExit,输出行数=$lineCount（密码等敏感字段已脱敏,请直接 psql 复现）"
}
Ok "psql 登录成功（用户 $DataSourceUsername → $pgDb）"

# ============================== 3. 端口 ==============================

Step "server.port = $ServerPort"
$portInUse = Get-NetTCPConnection -LocalPort $ServerPort -State Listen -ErrorAction SilentlyContinue
if ($portInUse) {
    Fail 4 "端口 $ServerPort 已被占用（pid=$($portInUse[0].OwningProcess)）。请改 SERVER_PORT 或先停占用者。"
}
Ok "端口 $ServerPort 空闲"

# ============================== 4. 磁盘 ==============================

Step "安装目录磁盘空间"
$driveLetter = (Resolve-Path -LiteralPath $InstallDir).Drive.Name
$freeBytes = [math]::Round((Get-PSDrive -Name $driveLetter.TrimEnd(':')).Free / 1MB, 0)
if ($freeBytes -lt $MinDiskMb) {
    Fail 5 "磁盘空间不足: ${freeBytes}MB（需要 ≥ ${MinDiskMb}MB）"
}
Ok "磁盘 $driveLetter 剩余 ${freeBytes}MB"

# ============================== 5. Chromium ==============================

Step "Chromium 安装"
$playwrightDir = Join-Path $env:USERPROFILE 'AppData\Local\ms-playwright'
if (-not (Test-Path -LiteralPath $playwrightDir)) {
    Warn "未在默认位置找到 ms-playwright（$playwrightDir）。如果用 systemd / 共享账户安装在其他位置,请忽略。`n       建议先运行 .\install.ps1。"
} else {
    $chromiumDirs = Get-ChildItem -LiteralPath $playwrightDir -Directory -Filter 'chromium-*' -ErrorAction SilentlyContinue
    if (-not $chromiumDirs) {
        Fail 6 "ms-playwright 目录存在但未找到 chromium-* 子目录。请运行 .\install.ps1。"
    }
    Ok "Chromium 已安装：$($chromiumDirs[0].Name)（共 $($chromiumDirs.Count) 个版本目录）"
}

# ============================== 完成 ==============================

Write-Host ""
Write-Host "环境预检通过。" -ForegroundColor Green
Write-Host "可执行：.\start.ps1"
exit 0
