#!/usr/bin/env bash
# Visual Spider 5 — Linux 生产停止脚本（M7-2 / docs/specs/m7.md D2）
#
# 用途：按 logs/app.pid 停止 java 进程。
#
# 退出码：
#   0 = 已停止（或未运行 / PID 陈旧）
#   1 = 参数缺失或值非法
#   2 = 强杀超时（仍残留）

set -euo pipefail

INSTALL_DIR="${INSTALL_DIR:-/opt/visual-spider}"
GRACE_PERIOD_SEC="${GRACE_PERIOD_SEC:-30}"
FORCE_KILL_WAIT_SEC="${FORCE_KILL_WAIT_SEC:-10}"

# ---- 参数校验 ----
[ -n "$INSTALL_DIR" ] || { echo "[FAIL] INSTALL_DIR 不能为空" >&2; exit 1; }
[[ "$GRACE_PERIOD_SEC" =~ ^[0-9]+$ ]] && [ "$GRACE_PERIOD_SEC" -gt 0 ] \
    || { echo "[FAIL] GRACE_PERIOD_SEC 必须 > 0" >&2; exit 1; }

step() { printf '\n\033[0;36m[STEP]\033[0m %s\n' "$*"; }
ok()   { printf '\033[0;32m[OK]  \033[0m %s\n' "$*"; }
info() { printf '\033[0;90m[INFO]\033[0m %s\n' "$*"; }
warn() { printf '\033[0;33m[WARN]\033[0m %s\n' "$*"; }
fail() { printf '\033[0;31m[FAIL]\033[0m %s\n' "$*" >&2; exit "${2:-1}"; }

LOGS_DIR="$INSTALL_DIR/logs"
PID_FILE="$LOGS_DIR/app.pid"

# ---- 未运行 ----
if [ ! -f "$PID_FILE" ]; then
    ok "未运行（无 PID 文件）"
    exit 0
fi

RAW=$(cat "$PID_FILE" 2>/dev/null || true)
PID="${RAW//[[:space:]]/}"
if ! [[ "$PID" =~ ^[0-9]+$ ]]; then
    warn "PID 文件内容非法：'$RAW'。清理后退出。"
    rm -f "$PID_FILE"
    exit 0
fi

if ! kill -0 "$PID" 2>/dev/null; then
    ok "PID 文件指向的进程不存在（pid=$PID，陈旧文件）。已清理。"
    rm -f "$PID_FILE"
    exit 0
fi

# ---- 优雅停止 ----
step "优雅尝试 SIGTERM（GracePeriodSec=$GRACE_PERIOD_SEC）"
kill -TERM "$PID" 2>/dev/null || true

END=$(( $(date +%s) + GRACE_PERIOD_SEC ))
while [ "$(date +%s)" -lt "$END" ]; do
    if ! kill -0 "$PID" 2>/dev/null; then break; fi
    sleep 1
done

if kill -0 "$PID" 2>/dev/null; then
    warn "${GRACE_PERIOD_SEC}s 内未退出，执行 SIGKILL。"
    kill -KILL "$PID" 2>/dev/null || fail 2 "强杀失败（kill -KILL 退出码 $?）" 2

    FORCE_END=$(( $(date +%s) + FORCE_KILL_WAIT_SEC ))
    while [ "$(date +%s)" -lt "$FORCE_END" ]; do
        if ! kill -0 "$PID" 2>/dev/null; then break; fi
        sleep 1
    done
    if kill -0 "$PID" 2>/dev/null; then
        fail 2 "强杀后 ${FORCE_KILL_WAIT_SEC}s 仍存活（pid=$PID）。请人工检查。" 2
    fi
fi

rm -f "$PID_FILE"
ok "已停止 pid=$PID"
exit 0