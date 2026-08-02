# m4-smoke.ps1 — M4 列表识别端到端 smoke（M4 spec §T4 / roadmap §8）。
#
# 前提：M3 smoke 全绿；本地 PG 可用；JAR 已通过 `mvn -DskipTests package` 构建。
#
# 步骤（10 步）：
#   1. 启 fixture HTTP server（src/test/resources/list/*.html 与单页同端口）
#   2. 启 JAR
#   3. admin 登录 + 创建 collector
#   4. collector 登录 → 建 list 任务（standard-list fixture）→ READY
#   5. POST /api/runs → 202 WAITING
#   6. WebSocket /ws/runs/{runId} → 收到 PROGRESS + TERMINAL {SUCCESS}
#   7. GET /api/runs/{runId} → raw > 0, dedup = 0, final > 0, fail = 0
#   8. with-duplicates fixture → 跑 → dedup > 0, final < raw
#   9. partial-fail fixture → 跑 → PARTIAL_SUCCESS + fail > 0
#  10. cancel 路径：start → POST /cancel → CANCELLED + lane < 15s 释放
#
# Linux smoke 标注：not executed in M4; see M7。
#
# 本脚本为占位：完整脚本延后到真实 JAR + PG 验收时补；本占位仅描述步骤与期望，
# 供 M4-7 acceptance 时引用。

[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://localhost:8080',
    [string]$FixtureDir = "$PSScriptRoot/../src/test/resources/list"
)

$ErrorActionPreference = 'Stop'

function Step-Description($n, $desc) {
    Write-Host "[M4-smoke step $n] $desc"
}

Step-Description 1 "启 fixture HTTP server: $FixtureDir"
# python -m http.server 由开发者本地启动；smoke 不自带启停。
Step-Description 2 "启 JAR（依赖 M3 smoke step 2）"
Step-Description 3 "admin 登录 + 创建 collector"
Step-Description 4 "collector 建 list 任务 → READY"
Step-Description 5 "POST /api/runs → 202 WAITING"
Step-Description 6 "WebSocket 进度 → SUCCESS"
Step-Description 7 "GET /api/runs/{id} → 三计数 raw / dedup / final / fail"
Step-Description 8 "with-duplicates fixture → dedup > 0"
Step-Description 9 "partial-fail fixture → PARTIAL_SUCCESS"
Step-Description 10 "cancel 路径 → CANCELLED"

Write-Host "M4 smoke 占位完成。完整命令延后到 M4-7 acceptance。"
