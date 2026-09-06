#!/usr/bin/env bash
# Visual Spider 5 — Linux 生产状态脚本（M7-2 / docs/specs/m7.md D2）
#
# 用途：报告 PID 存活 + /actuator/health。不会修改任何状态。
#
# 退出码：
#   0 = 进程在跑且 health UP
#   1 = 进程在跑但 health 异常 / 不通
#   2 = 未运行
#   3 = 参数缺失

set -euo pipefail

INSTALL_DIR="${INSTALL_DIR:-/opt/visual-spider}"
HEALTH_TIMEOUT_SEC="${HEALTH_TIMEOUT_SEC:-5}"

# ---- 参数校验 ----
[ -n "$INSTALL_DIR" ] || { echo "[FAIL] INSTALL_DIR 不能为空" >&2; exit 3; }
[[ "$HEALTH_TIMEOUT_SEC" =~ ^[0-9]+$ ]] && [ "$HEALTH_TIMEOUT_SEC" -gt 0 ] \
    || { echo "[FAIL] HEALTH_TIMEOUT_SEC 必须 > 0" >&2; exit 3; }

step() { printf '\n\033[0;36m[STEP]\033[0m %s\n' "$*"; }
ok()   { printf '\033[0;32m[OK]  \033[0m %s\n' "$*"; }
warn() { printf '\033[0;33m[WARN]\033[0m %s\n' "$*"; }

LOGS_DIR="$INSTALL_DIR/logs"
PID_FILE="$LOGS_DIR/app.pid"
SERVER_PORT="${SERVER_PORT:-8080}"
HEALTH_URL="http://localhost:$SERVER_PORT/actuator/health"

# ---- 进程 ----
if [ ! -f "$PID_FILE" ]; then
    printf '\033[0;33m[STATUS] 未运行（无 PID 文件：%s）\033[0m\n' "$PID_FILE"
    exit 2
fi

RAW=$(cat "$PID_FILE" 2>/dev/null || true)
PID="${RAW//[[:space:]]/}"
if ! [[ "$PID" =~ ^[0-9]+$ ]]; then
    printf '\033[0;33m[STATUS] 未运行（PID 文件内容非法：%q）\033[0m\n' "$RAW"
    exit 2
fi

if ! kill -0 "$PID" 2>/dev/null; then
    printf '\033[0;33m[STATUS] 未运行（PID 指向的进程不存在：pid=%s）\033[0m\n' "$PID"
    exit 2
fi

PROC_INFO=$(ps -p "$PID" -o pid=,comm=,lstart= 2>/dev/null || true)
printf '\033[0;32m[STATUS] 运行中（%s）\033[0m\n' "$PROC_INFO"

# ---- 健康 ----
BODY=$(curl -fsS -m "$HEALTH_TIMEOUT_SEC" "$HEALTH_URL" 2>/dev/null || true)
if [ -n "$BODY" ] && printf '%s' "$BODY" | grep -q '"status":"UP"'; then
    printf '\033[0;32m[STATUS] health UP（%s）\n         body = %s\033[0m\n' "$HEALTH_URL" "$BODY"
    exit 0
else
    printf '\033[0;33m[STATUS] health 异常或不可达（%s）\033[0m\n' "$HEALTH_URL"
    exit 1
fi