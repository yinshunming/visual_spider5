#!/usr/bin/env pwsh
# Visual Spider 5 — Windows 生产环境一次性安装脚本（M7-1 / docs/specs/m7.md D1/D3）
#
# 用途：把 JAR + 运行依赖一次性装好。本脚本是部署资产,不依赖仓库源码。
#       默认安装根目录 = C:\visual-spider（可用 -InstallDir 覆盖）。
#
# 步骤：
#   1) 检查 JDK >= 21（PATH 里 java 可用）
#   2) 检查 PostgreSQL 16 客户端（psql 可用;连通性不在此处做,留给 check-env）
#   3) 在安装根目录创建 app.jar + config\ + logs\
#   4) 用 Playwright CLI 安装 Chromium,把实际 revision 写入 logs/install.log
#
# 退出码：
#   0 = 成功
#   1 = 参数缺失或值非法
#   2 = 前置条件不满足（缺 java / 缺 psql / 缺 JAR）
#   3 = 安装失败（Playwright CLI 失败）

[CmdletBinding()]
param(
    [string]$InstallDir = $Env:VISUALSPIDER_INSTALL_DIR ?? 'C:\visual-spider',
    [string]$JarPath = $Env:VISUALSPIDER_JAR_PATH ?? (Join-Path $InstallDir 'app.jar'),
    [int]$PlaywrightTimeoutSec = 600
)

$ErrorActionPreference = 'Stop'

function Step { param([string]$m) Write-Host "`n[STEP] $m" -ForegroundColor Cyan }
function Ok   { param([string]$m) Write-Host "[OK]   $m" -ForegroundColor Green }
function Info { param([string]$m) Write-Host "[INFO] $m" -ForegroundColor Gray }
function Fail { param([int]$code, [string]$m) Write-Host "[FAIL] $m" -ForegroundColor Red; exit $code }

# ============================== 参数校验 ==============================

if ([string]::IsNullOrWhiteSpace($InstallDir)) { Fail 1 "InstallDir 不能为空" }
if ([string]::IsNullOrWhiteSpace($JarPath))   { Fail 1 "JarPath 不能为空" }
if ($PlaywrightTimeoutSec -le 0)              { Fail 1 "PlaywrightTimeoutSec 必须 > 0" }

# ============================== 1. JDK ==============================

Step "检查 JDK（需要 Temurin 21 LTS 或同主版本兼容发行版）"
if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    Fail 2 "未在 PATH 中找到 java。请先安装 Eclipse Temurin 21 LTS 并加入 PATH,或设置 JAVA_HOME。`n  下载: https://adoptium.net/temurin/releases/?version=21"
}
# 2>&1 合并输出;直接调用避免管道把 $LASTEXITCODE 重置
$javaLines = & java -version 2>&1
$javaExit = $LASTEXITCODE
$javaOut = ($javaLines | Select-Object -First 1)
if ($null -eq $javaOut) { $javaOut = '' } else { $javaOut = $javaOut.ToString() }
if ($javaExit -ne 0) { Fail 2 "java -version 执行失败: $javaOut" }
$javaMatch = [regex]::Match($javaOut, '"(\d+)(?:\.\d+)*"')
if (-not $javaMatch.Success) { Fail 2 "无法解析 java 版本: $javaOut" }
$javaVersion = $javaMatch.Groups[1].Value
if ([int]$javaVersion -lt 21) { Fail 2 "java 版本过低: $javaOut（需要 ≥ 21）" }
Ok "java = $javaOut"

# ============================== 2. PostgreSQL 客户端 ==============================

Step "检查 PostgreSQL 16 客户端（psql）"
if (-not (Get-Command psql -ErrorAction SilentlyContinue)) {
    Write-Host "[WARN] 未在 PATH 中找到 psql。请确认 PostgreSQL 16 已安装（推荐 EDB 安装器 https://www.enterprisedb.com/downloads/postgres-postgresql-downloads）。" -ForegroundColor Yellow
    Write-Host "       本脚本不强制 psql,但 check-env.ps1 会拒绝通过。" -ForegroundColor Yellow
} else {
    $psqlOut = (& psql --version 2>&1 | Select-Object -First 1).ToString()
    Ok "psql = $psqlOut"
}

# ============================== 3. 安装目录 ==============================

Step "准备安装目录：$InstallDir"
$configDir = Join-Path $InstallDir 'config'
$logsDir   = Join-Path $InstallDir 'logs'
New-Item -ItemType Directory -Force -Path $InstallDir | Out-Null
New-Item -ItemType Directory -Force -Path $configDir | Out-Null
New-Item -ItemType Directory -Force -Path $logsDir   | Out-Null
Ok "目录就绪: $InstallDir"

# ============================== 4. JAR ==============================

Step "校验 JAR：$JarPath"
if (-not (Test-Path -LiteralPath $JarPath)) {
    Fail 2 "未找到 JAR: $JarPath。请把 visual-spider5-*.jar 拷到该路径,或通过 -JarPath 指定。"
}
$expectedDest = Join-Path $InstallDir 'app.jar'
if ((Resolve-Path -LiteralPath $JarPath).Path -ne $expectedDest) {
    Copy-Item -LiteralPath $JarPath -Destination $expectedDest -Force
    Ok "JAR 已拷贝到: $expectedDest"
} else {
    Ok "JAR 已就位: $expectedDest"
}

# ============================== 5. Chromium ==============================

Step "安装 Chromium（Playwright CLI）并把 revision 写入 logs/install.log"
$installLog = Join-Path $logsDir 'install.log'
$stamp = Get-Date -Format 'yyyy-MM-dd HH:mm:ss'
"=== install.ps1 @ $stamp ===" | Out-File -FilePath $installLog -Append -Encoding utf8
"java: $javaOut"                                  | Out-File -FilePath $installLog -Append -Encoding utf8
"jar:  $expectedDest"                             | Out-File -FilePath $installLog -Append -Encoding utf8

$env:PLAYWRIGHT_BROWSERS_PATH = '0'  # 0 = 装到 JAR 同源 ms-playwright（默认）

$pwCliOut = & java -cp $expectedDest com.microsoft.playwright.CLI install chromium 2>&1
$pwExit = $LASTEXITCODE
$pwCliOut | Out-File -FilePath $installLog -Append -Encoding utf8
if ($pwExit -ne 0) {
    Fail 3 "Playwright CLI install chromium 失败（退出码 $pwExit）。详情见 $installLog"
}

# 解析实际 Chromium revision 并打印
$revision = $null
foreach ($line in ($pwCliOut -split "`r?`n")) {
    if ($line -match '(?i)chromium[- ](\d+)') { $revision = $Matches[1]; break }
}
if ($revision) {
    "chromium revision: $revision" | Out-File -FilePath $installLog -Append -Encoding utf8
    Ok "Chromium 安装完成（revision=$revision,详见 $installLog）"
} else {
    Ok "Chromium 安装完成（未捕获到 revision 行,详见 $installLog）"
}

# ============================== 完成 ==============================

Write-Host ""
Write-Host "安装完成。下一步：" -ForegroundColor Green
Write-Host "  1) 编辑 $configDir\visual-spider.env（参考 docs/deploy/configuration.md）"
Write-Host "  2) .\check-env.ps1"
Write-Host "  3) .\start.ps1"
Write-Host "  4) 浏览器访问 http://<host>:8080/  用 VISUALSPIDER_ADMIN_USERNAME / VISUALSPIDER_ADMIN_PASSWORD 登录"
exit 0
