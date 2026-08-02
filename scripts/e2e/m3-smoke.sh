#!/usr/bin/env bash
# M3 smoke (Linux/macOS bash) — 13 步主链路冒烟
#
# 依赖：bash + curl + 可执行 JAR（已构建）+ Python3（起 fixture HTTP server）。
# 前置：PG 已就绪（VISUALSPIDER_DATASOURCE_URL/USERNAME/PASSWORD）、
#       Playwright Chromium 已安装（`mvn exec:java -Dexec.args="install chromium"`）。
#
# 范围：与 m2-smoke.sh 镜像；M3 新增 13 步覆盖运行 -> 结果 -> 导出 -> 中断恢复。
# 端点：见 docs/specs/m3.md §D17；WS 握手：spec §D16。
#
# Linux smoke: not executed in M3; see M7
# ----------------------------------------------------------------------------
# M3 端到端在 Linux 真机延迟到 M7 跨平台验收（roadmap §7 / AGENTS 决策门）：
#  - M3 阶段只保证脚本在 Windows pwsh 上完整跑通；
#  - 本脚本与 .ps1 同源对照，结构完全镜像；
#  - 不在本机静默执行（避免给"Linux 上 M3 全部通过"的假象）。
# 真正执行请在 M7 阶段放开 set -e 与真实调用；当前仅保留占位步骤与头部说明，
# 调用方一看注释就知道这是 M7 目标物，而不是 M3 完成证据。

set -euo pipefail

BASE_URL="${M3_BASE_URL:-http://localhost:8080}"
COOKIE_ADMIN=$(mktemp)
COOKIE_COLL=$(mktemp)
COOKIE_ADMIN2=$(mktemp)
trap 'rm -f "$COOKIE_ADMIN" "$COOKIE_COLL" "$COOKIE_ADMIN2"' EXIT

step() { printf '[step %s] %s\n' "$1" "$2"; }
note() { printf '# %s\n' "$1"; }

note "Linux smoke: not executed in M3; see M7"
note "13 步骤如下；当前实现仅做注释占位，避免误以为 M3 已在 Linux 跑通。"

# 步骤定义严格对应 m3-smoke.ps1；当前每步只打印，不发起真实请求。
step 1  "启动 JAR + fixture HTTP server（M7 真机执行；当前占位）"
step 2  "collector 登录 + 建单页任务至 READY"
step 3  "POST /api/runs -> 202 {runId, WAITING}"
step 4  "WS /ws/runs/{runId} 带 CSRF -> 收到 PROGRESS"
step 5  "运行推进 -> 收到 TERMINAL {status: SUCCESS}"
step 6  "GET /api/runs/{runId}/results -> 1 条结果"
step 7  "GET /api/runs/{runId}/results/export?format=csv -> 1 行 CSV"
step 8  "GET /api/runs/{runId}/snapshot -> 固化定义"
step 9  "第 2 个 run（同用户第 1 个未终态）-> USER_RUN_LIMIT 409"
step 10 "cancel: start -> POST /cancel -> CANCELLED + lane 释放"
step 11 "强制停止 JAR -> 重启 -> 遗留 INTERRUPTED + 结果可导出"
step 12 "admin 登录 -> 能看到 collector 的 run + 导出"
step 13 "pwsh 验证 0 个 ms-playwright / driver 残留（M3-7 已在 .ps1 覆盖）"

note "Linux smoke: not executed in M3; see M7"
exit 0
