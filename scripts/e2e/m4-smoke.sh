#!/usr/bin/env bash
# m4-smoke.sh — M4 列表识别端到端 smoke Linux/macOS bash 版。
#
# 与 m4-smoke.ps1 步骤一一对应；本脚本保持镜像占位结构（M3 .sh 先例）：
#   1) 当前只打印步骤，不发起真实请求；
#   2) 真实 Linux 跨平台验收延后到 M7（roadmap §7 / AGENTS 决策门）；
#   3) 不在本机静默执行（避免给"Linux 上 M4 全部通过"的假象）。
#
# 调用方一看注释就知道这是 M7 目标物，而不是 M4 完成证据。
# 真正执行请在 M7 阶段放开 set -e 与真实 HTTP/WS 调用，结构对齐 m4-smoke.ps1。

set -eu

BASE_URL="${M4_BASE_URL:-http://localhost:8080}"
FIXTURE_DIR="${M4_FIXTURE_DIR:-$(cd "$(dirname "$0")/../.." && pwd)/src/test/resources/list}"
FIXTURE_PORT="${M4_FIXTURE_PORT:-8082}"

note() { printf '# %s\n' "$1"; }
step() { printf '[m4-smoke step %s] %s\n' "$1" "$2"; }

note "Linux smoke: not executed in M4; see M7"
note "10 步骤如下；当前实现仅做注释占位，避免误以为 M4 已在 Linux 跑通。"
note "BASE_URL=$BASE_URL  FIXTURE_DIR=$FIXTURE_DIR  FIXTURE_PORT=$FIXTURE_PORT"

step 1  "启 JAR + fixture HTTP server（list/ 目录，端口 $FIXTURE_PORT）"
step 2  "admin 登录 + 创建 collector + 建 LIST 任务（standard-list fixture）至 READY"
step 3  "POST /api/runs -> 202 WAITING"
step 4  "WS /ws/runs/{runId} -> PROGRESS + EVENT(LIST_ITEM_EXTRACTED) + TERMINAL SUCCESS"
step 5  "GET /api/runs/{runId} -> raw>0, dedup=0, final>0, fail=0 + CSV 多行"
step 6  "with-duplicates fixture -> dedup>0, final<raw"
step 7  "partial-fail fixture -> PARTIAL_SUCCESS + fail>0"
step 8  "cancel 路径 -> CANCELLED + lane 释放（<15s）"
step 9  "pwsh 验证 0 个 ms-playwright / driver 残留（已在 .ps1 覆盖）"
step 10 "Linux smoke 标注：not executed in M4; see M7"

note "Linux smoke: not executed in M4; see M7"
exit 0
