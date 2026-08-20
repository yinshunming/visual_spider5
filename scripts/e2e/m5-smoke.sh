#!/usr/bin/env bash
# m5-smoke.sh — M5 翻页/加载更多/内容页/限速/停止检测 端到端 smoke Linux/macOS bash 版。
#
# 与 m5-smoke.ps1 步骤一一对应；本脚本保持镜像占位结构（M3/M4 .sh 先例）：
#   1) 当前只打印步骤，不发起真实请求；
#   2) 真实 Linux 跨平台验收延后到 M7（roadmap §7 / AGENTS 决策门）；
#   3) 不在本机静默执行（避免给"Linux 上 M5 全部通过"的假象）。
#
# 调用方一看注释就知道这是 M7 目标物，而不是 M5 完成证据。
# 真正执行请在 M7 阶段放开 set -e 与真实 HTTP/WS 调用，结构对齐 m5-smoke.ps1。

set -eu

BASE_URL="${M5_BASE_URL:-http://localhost:8080}"
FIXTURE_DIR="${M5_FIXTURE_DIR:-$(cd "$(dirname "$0")/../.." && pwd)/src/test/resources}"
FIXTURE_PORT="${M5_FIXTURE_PORT:-8083}"

note() { printf '# %s\n' "$1"; }
step() { printf '[m5-smoke step %s] %s\n' "$1" "$2"; }

note "Linux smoke: not executed in M5; see M7"
note "12 步骤如下；当前实现仅做注释占位，避免误以为 M5 已在 Linux 跑通。"
note "BASE_URL=$BASE_URL  FIXTURE_DIR=$FIXTURE_DIR  FIXTURE_PORT=$FIXTURE_PORT"

step 1  "启 JAR + fixture HTTP server（pagination/ + content-page/ 目录，端口 $FIXTURE_PORT）"
step 2  "admin 登录 + 创建 collector + 登录 + 建 LIST 任务（pagination/next-page.html fixture）至 READY"
step 3  "POST /api/runs -> 202 WAITING"
step 4  "WS /ws/runs/{runId} -> PROGRESS + EVENT + TERMINAL SUCCESS；REST 二次确认 LIST_PAGE_LOADED ×2 + PAGINATION_CLICKED ×1"
step 5  "/api/runs/{runId} 五计数：raw>0, dedup=0, final>0, fail=0, contentFail=0 + CSV 多行导出"
step 6  "pagination/load-more.html fixture -> STOP_PAGINATION_NO_NEW_ITEMS"
step 7  "pagination/duplicate-page.html fixture -> STOP_DUPLICATE_PAGE"
step 8  "content-blank.html fixture -> contentFail = final > 0（list 字段保留）"
step 9  "pagination/stop-429.html fixture -> STOP_HTTP_429（需专用 mock server 返 429）"
step 10 "pagination/pacing-test.html fixture -> 同域连续 navigate 间隔 ≥ 1s（从 PAGINATION_CLICKED 时间戳测量）"
step 11 "cancel 路径 + 残留进程检查"
step 12 "输出 Linux 标注（实际不在本脚本执行；m5-smoke.ps1 已做 Windows pwsh 12 步）"

note "M5 fixture 拓扑（共 12 文件）："
note "  pagination/ 5: next-page.html / load-more.html / no-new-items.html / duplicate-page.html / pagination-disappeared.html"
note "  content-page/ 7: standard-list-with-content.html / content-blank.html / content-link-no-match.html /"
note "                   content-link-multi-match.html / stop-429.html / stop-403.html / stop-captcha.html"
note "  + pagination/pacing-test.html（M5-5 限速断言用）"

note "M5 acceptance 在 pwsh 脚本中通过 ./scripts/e2e/m5-smoke.ps1 全绿。"
note "Linux bash 等价物将在 M7 跨平台验收阶段落地；当前仅做占位与目录标注。"