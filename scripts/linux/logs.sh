#!/usr/bin/env bash
# Visual Spider 5 — Linux 生产日志查看脚本（M7-2 / docs/specs/m7.md D2）
#
# 用途：tail logs/app.out.log 与 logs/app.err.log。
#
# 退出码：
#   0 = 成功
#   1 = 参数缺失
#   2 = 日志文件不存在

set -euo pipefail

INSTALL_DIR="${INSTALL_DIR:-/opt/visual-spider}"
LINES="${LINES:-100}"
FOLLOW=0
while [ $# -gt 0 ]; do
    case "$1" in
        --follow|-f) FOLLOW=1 ;;
        --lines|-n)  LINES="$2"; shift 2 ;;
        *)           shift ;;
    esac
done

# ---- 参数校验 ----
[ -n "$INSTALL_DIR" ] || { echo "[FAIL] INSTALL_DIR 不能为空" >&2; exit 1; }
[[ "$LINES" =~ ^[0-9]+$ ]] && [ "$LINES" -gt 0 ] \
    || { echo "[FAIL] LINES 必须 > 0" >&2; exit 1; }

LOGS_DIR="$INSTALL_DIR/logs"
OUT_LOG="$LOGS_DIR/app.out.log"
ERR_LOG="$LOGS_DIR/app.err.log"

if [ ! -f "$OUT_LOG" ] || [ ! -f "$ERR_LOG" ]; then
    echo "[FAIL] 日志文件不存在。请先 ./start.sh。" >&2
    exit 2
fi

if [ "$FOLLOW" -eq 1 ]; then
    echo "[INFO] 跟随模式（Ctrl+C 退出）。两个日志分别：$OUT_LOG / $ERR_LOG"
    # 后台 tail -F err.log，前台 tail -F out.log；Ctrl+C 一并退出
    tail -n "$LINES" -F "$ERR_LOG" &
    TAIL_PID=$!
    trap 'kill $TAIL_PID 2>/dev/null || true; wait $TAIL_PID 2>/dev/null || true' EXIT INT TERM
    tail -n "$LINES" -F "$OUT_LOG"
else
    printf '\n\033[0;36m=== app.out.log (tail %s) ===\033[0m\n' "$LINES"
    tail -n "$LINES" "$OUT_LOG"
    printf '\n\033[0;36m=== app.err.log (tail %s) ===\033[0m\n' "$LINES"
    tail -n "$LINES" "$ERR_LOG"
fi
exit 0