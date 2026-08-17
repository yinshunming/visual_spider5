#!/usr/bin/env pwsh
# Visual Spider 5 — Windows 开发启动脚本（M1-5）
# 用途：检查环境变量 + 启动 JAR 到后台，写日志到 logs/app.out.log / logs/app.err.log，PID 到 logs/app.pid

[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'

$ProjectRoot = Resolve-Path (Join-Path $PSScriptRoot '..' '..')
Set-Location $ProjectRoot

function Require-MinVersion {
    param([string]$Label, [string]$Current, [int]$MinMajor)
    if ($null -eq $Current) {
        Write-Host "[FAIL] $Label 未安装" -ForegroundColor Red
        exit 1
    }
    $firstDigit = ($Current -split '\.')[0] -replace '\D', ''
    if ([int]$firstDigit -lt $MinMajor) {
        Write-Host "[FAIL] $Label 版本过低: $Current（需要 ≥ $MinMajor）" -ForegroundColor Red
        exit 1
    }
    Write-Host "[OK]   $Label = $Current"
}

# 1. 检查 java 版本
$javaOut = (& java -version 2>&1 | Select-Object -First 1)
if ($LASTEXITCODE -ne 0) {
    Write-Host "[FAIL] java 未找到" -ForegroundColor Red
    exit 1
}
$javaVersion = ($javaOut -replace '.*"(\d+\.\d+[\.\d+]*)".*', '$1')
Require-MinVersion -Label 'java' -Current $javaVersion -MinMajor 21

# 2. 检查 pwsh 版本
$pwshVersion = $PSVersionTable.PSVersion.Major
Require-MinVersion -Label 'pwsh' -Current $pwshVersion -MinMajor 7

# 3. 检查环境变量
if (-not $env:VISUALSPIDER_ADMIN_USERNAME) {
    Write-Host "[FAIL] VISUALSPIDER_ADMIN_USERNAME 未设置" -ForegroundColor Red
    exit 1
}
if (-not $env:VISUALSPIDER_ADMIN_PASSWORD -or $env:VISUALSPIDER_ADMIN_PASSWORD.Trim().Length -lt 12) {
    Write-Host "[FAIL] VISUALSPIDER_ADMIN_PASSWORD 未设置或长度 < 12" -ForegroundColor Red
    exit 1
}
Write-Host "[OK]   admin 凭据已配置"

# 4. 准备日志目录
New-Item -ItemType Directory -Force -Path logs | Out-Null

# 5. 构建可执行 JAR（如不存在）
$jarPath = Join-Path $ProjectRoot 'target/visual-spider5-0.0.1-SNAPSHOT.jar'
if (-not (Test-Path $jarPath)) {
    Write-Host "[INFO] 未找到 JAR，开始构建..."
    & ./mvnw package -DskipTests 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) {
        Write-Host "[FAIL] mvn package 失败" -ForegroundColor Red
        exit 1
    }
}

# 6. 启动 JAR 到后台
Write-Host "[INFO] 启动应用..."
$proc = Start-Process -FilePath 'java' `
    -ArgumentList @('-jar', $jarPath) `
    -RedirectStandardOutput (Join-Path $ProjectRoot 'logs/app.out.log') `
    -RedirectStandardError (Join-Path $ProjectRoot 'logs/app.err.log') `
    -NoNewWindow -PassThru
$proc.Id | Out-File -FilePath (Join-Path $ProjectRoot 'logs/app.pid') -Encoding ascii

# 7. 等待启动并打印最近日志
Start-Sleep -Seconds 5
Write-Host "[INFO] 最近日志："
Get-Content (Join-Path $ProjectRoot 'logs/app.out.log') -Tail 200
Write-Host ""
Write-Host "[INFO] PID: $((Get-Content (Join-Path $ProjectRoot 'logs/app.pid')).Trim())"
Write-Host "[INFO] 健康检查： curl http://localhost:8080/actuator/health"
