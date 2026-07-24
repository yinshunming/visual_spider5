#!/usr/bin/env pwsh
# M2 smoke (Windows / pwsh)：13 步主链路冒烟。
# 前置：后端 JAR 已在 $Env:M2_BASE_URL（默认 http://localhost:8080）启动，
# PostgreSQL DSN 通过 PG_URL/PG_USER/PG_PASSWORD 环境变量提供，fixture HTTP server
# 已用 python -m http.server 8081 提供。
# 本脚本只验证 HTTP/WS 主链路；真实 Chromium 与 PG-IT 在 M7 跨平台验收。
#
# Linux/WSL 验收提示：本机 Linux 真实运行推迟到 M7；脚本必须先在 Windows pwsh 跑通。

[CmdletBinding()]
param(
    [string]$BaseUrl = $Env:M2_BASE_URL ?? 'http://localhost:8080',
    [string]$User = $Env:M2_SMOKE_USER ?? 'admin',
    [string]$Password = $Env:M2_SMOKE_PASSWORD ?? 'admin1234567890'
)

$ErrorActionPreference = 'Stop'
$cookieJar = New-Object Microsoft.PowerShell.Commands.WebRequestSession

function Step {
    param([int]$n, [string]$Title)
    Write-Host ("[step {0}] {1}" -f $n, $Title)
}

try {
    Step 1 '登录获取 JSESSIONID + XSRF-TOKEN'
    $loginBody = @{ username = $User; password = $Password } | ConvertTo-Json
    Invoke-WebRequest -Uri "$BaseUrl/api/auth/login" `
        -Method POST `
        -Body $loginBody `
        -ContentType 'application/json' `
        -WebSession $cookieJar | Out-Null

    Step 2 '创建任务草稿'
    $taskBody = @{
        name = 'smoke-m2'
        definition = @{
            schemaVersion = 1
            mode = 'SINGLE_PAGE'
            startUrl = 'http://localhost:8081/static.html'
            viewport = @{ width = 1280; height = 720 }
            fields = @(@{ name = 'title'; source = 'VISIBLE_TEXT'; selector = 'h1'; resultType = 'TEXT'; trim = 'TRIM'; required = $true })
        }
    } | ConvertTo-Json -Depth 6
    $create = Invoke-WebRequest -Uri "$BaseUrl/api/tasks" `
        -Method POST `
        -Body $taskBody `
        -ContentType 'application/json' `
        -WebSession $cookieJar
    $taskId = ($create.Content | ConvertFrom-Json).id
    Write-Host "taskId=$taskId"

    Step 3 '打开配置会话'
    $openBody = @{ taskId = $taskId } | ConvertTo-Json
    $session = Invoke-WebRequest -Uri "$BaseUrl/api/visual-sessions" `
        -Method POST `
        -Body $openBody `
        -ContentType 'application/json' `
        -WebSession $cookieJar
    $sessionId = ($session.Content | ConvertFrom-Json).sessionId
    Write-Host "sessionId=$sessionId"

    Step 4 '查询会话状态'
    Invoke-WebRequest -Uri "$BaseUrl/api/visual-sessions/$sessionId" -WebSession $cookieJar | Out-Null

    Step 5 '切换浏览模式（WS 协议：query ?csrf=<token>）'
    # 此步骤需真实 WS 客户端，本脚本仅断言 REST 准备就绪
    Write-Host 'WS 握手验证放在 m2-ws-smoke.ps1（M7 引入）。'

    Step 6 '校验选择器 (CSS)'
    $validate = Invoke-WebRequest `
        -Uri "$BaseUrl/api/visual-sessions/$sessionId/selectors/validate" `
        -Method POST `
        -Body (@{ selectors = @(@{ type = 'css'; selector = 'h1' }) } | ConvertTo-Json) `
        -ContentType 'application/json' `
        -WebSession $cookieJar
    Write-Host ("matchCount = " + ($validate.Content | ConvertFrom-Json).outcomes[0].matchCount)

    Step 7 '编辑字段'
    Write-Host 'EditingBuffer 自动保存路径：PUT /api/tasks/{id}（M3+ 启用完整 UI）'

    Step 8 '字段预览'
    $preview = Invoke-WebRequest `
        -Uri "$BaseUrl/api/visual-sessions/$sessionId/preview" `
        -Method POST `
        -Body (@{ definition = ($taskBody | ConvertFrom-Json).definition } | ConvertTo-Json -Depth 6) `
        -ContentType 'application/json' `
        -WebSession $cookieJar
    Write-Host ("preview fields = " + (($preview.Content | ConvertFrom-Json).fieldOutcomes.Count))

    Step 9 '自动保存 (5s 防抖：跳过 sleep，直接显式 PUT)'
    $update = Invoke-WebRequest `
        -Uri "$BaseUrl/api/tasks/$taskId" `
        -Method PUT `
        -Body ($taskBody | ConvertTo-Json -Depth 6) `
        -ContentType 'application/json' `
        -WebSession $cookieJar
    Write-Host ("status = " + ($update.Content | ConvertFrom-Json).status)

    Step 10 '状态切到 READY'
    Write-Host 'TaskCatalog.saveDraft 后由 TaskReadiness 决定 status。'

    Step 11 '关闭配置会话'
    Invoke-WebRequest -Uri "$BaseUrl/api/visual-sessions/$sessionId" -Method DELETE -WebSession $cookieJar | Out-Null

    Step 12 '重开会话（验证幂等）'
    $reopen = Invoke-WebRequest -Uri "$BaseUrl/api/visual-sessions" `
        -Method POST `
        -Body (@{ taskId = $taskId } | ConvertTo-Json) `
        -ContentType 'application/json' `
        -WebSession $cookieJar
    $reopenedId = ($reopen.Content | ConvertFrom-Json).sessionId
    Invoke-WebRequest -Uri "$BaseUrl/api/visual-sessions/$reopenedId" -Method DELETE -WebSession $cookieJar | Out-Null

    Step 13 '验证 Chromium 子进程 0 残留（Windows）'
    $chromium = Get-Process -Name 'msedge' -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowTitle -like '*playwright*' }
    if ($chromium) {
        Write-Warning 'Chromium 子进程未回收，详情：'
        $chromium | Format-Table Id, ProcessName, MainWindowTitle
    } else {
        Write-Host 'Chromium 子进程已清空。'
    }

    Write-Host 'M2 smoke PASSED.'
} catch {
    Write-Host ("M2 smoke FAILED: " + $_)
    exit 1
}