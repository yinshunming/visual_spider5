#!/usr/bin/env bash
# Visual Spider 5 — Linux 生产启动脚本（M7-2 / docs/specs/m7.md D2）
#
# 用途：后台启动 JAR，写 PID 到 logs/app.pid，stdout/stderr 到 logs/app.out.log / app.err.log，
#       启动后读最近日志确认。
#       部署资产，不依赖仓库源码；参数化 JAR 路径与端口。
#
# 环境变量：启动时若 <InstallDir>/config/visual-spider.env 存在，读取 KEY=VALUE 注入当前进程
#           （仅当同名变量未设时；空行 / # 注释 / 无 = 行忽略；值可用双引号包裹）。
#
# 推荐运行身份：visualspider（systemd 用同一个用户；root 运行时会自动 runuser 切换）。
#
# 退出码：
#   0 = 启动成功（actuator/health UP）
#   1 = 参数缺失或值非法
#   2 = 已有实例在跑（拒绝重复启动）
#   3 = 启动失败（健康检查超时 / 进程退出）

set -euo pipefail

INSTALL_DIR="${INSTALL_DIR:-/opt/visual-spider}"
JAR_PATH="${JAR_PATH:-$INSTALL_DIR/app.jar}"
HEALTH_TIMEOUT_SEC="${HEALTH_TIMEOUT_SEC:-90}"
TAIL_LINES="${TAIL_LINES:-80}"
APP_USER="${APP_USER:-visualspider}"

# ---- 参数校验 ----
[ -n "$INSTALL_DIR" ]        || { echo "[FAIL] INSTALL_DIR 不能为空" >&2; exit 1; }
[ -n "$JAR_PATH" ]           || { echo "[FAIL] JAR_PATH 不能为空" >&2; exit 1; }
[[ "$HEALTH_TIMEOUT_SEC" =~ ^[0-9]+$ ]] && [ "$HEALTH_TIMEOUT_SEC" -gt 0 ] \
    || { echo "[FAIL] HEALTH_TIMEOUT_SEC 必须 > 0" >&2; exit 1; }

step() { printf '\n\033[0;36m[STEP]\033[0m %s\n' "$*"; }
ok()   { printf '\033[0;32m[OK]  \033[0m %s\n' "$*"; }
info() { printf '\033[0;90m[INFO]\033[0m %s\n' "$*"; }
fail() { printf '\033[0;31m[FAIL]\033[0m %s\n' "$*" >&2; exit "${2:-1}"; }

# ---- 加载 .env ----
# 从 $INSTALL_DIR/config/visual-spider.env 读取 KEY=VALUE，只设置当前进程未设的同名变量。
# 已存在的环境变量优先；空行 / # 注释 / 无 = 行忽略；值可用双引号包裹。
ENV_FILE="$INSTALL_DIR/config/visual-spider.env"
if [ -f "$ENV_FILE" ]; then
    while IFS= read -r line || [ -n "$line" ]; do
        line="${line#"${line%%[![:space:]]*}"}"   # ltrim
        line="${line%"${line##*[![:space:]]}"}"   # rtrim
        [ -z "$line" ] && continue
        [[ "$line" == \#* ]] && continue
        [[ "$line" != *=* ]] && continue
        KEY="${line%%=*}"
        KEY="${KEY%"${KEY##*[[:space:]]}"}"       # rtrim key
        VAL="${line#*=}"
        VAL="${VAL#"${VAL%%[![:space:]]*}"}"      # ltrim val
        VAL="${VAL%"${VAL##*[![:space:]]}"}"      # rtrim val
        # 去双引号
        if [[ "$VAL" == \"*\" && "$VAL" == *\" && ${#VAL} -ge 2 ]]; then
            VAL="${VAL:1:${#VAL}-2}"
        fi
        # 已存在的环境变量优先（shell > .env > 系统）
        if [ -z "${!KEY:-}" ]; then
            export "$KEY=$VAL"
        fi
    done < "$ENV_FILE"
    info "已加载 .env: $ENV_FILE"
else
    info "未找到 .env（$ENV_FILE）；依赖系统 / 会话环境变量。"
fi

# ---- 路径 ----
LOGS_DIR="$INSTALL_DIR/logs"
PID_FILE="$LOGS_DIR/app.pid"
OUT_LOG="$LOGS_DIR/app.out.log"
ERR_LOG="$LOGS_DIR/app.err.log"
mkdir -p "$LOGS_DIR"

if [ ! -f "$JAR_PATH" ]; then
    fail 1 "未找到 JAR：$JAR_PATH。请先运行 ./install.sh。"
fi

SERVER_PORT="${SERVER_PORT:-8080}"

# ---- 重复启动检查 ----
if [ -f "$PID_FILE" ]; then
    EXISTING=$(cat "$PID_FILE" 2>/dev/null | tr -d '[:space:]' || true)
    if [[ "$EXISTING" =~ ^[0-9]+$ ]]; then
        if kill -0 "$EXISTING" 2>/dev/null; then
            # 进一步确认是 java 进程
            PROC_NAME=$(ps -p "$EXISTING" -o comm= 2>/dev/null || true)
            case "$PROC_NAME" in
                java*) fail 2 "已有实例在跑（pid=$EXISTING）。如需重启请先 ./stop.sh。" 2 ;;
                *)     info "陈旧 PID 文件（指向 $PROC_NAME，pid=$EXISTING 已不是 java），清理后继续。" ;;
            esac
        else
            info "陈旧 PID 文件（pid=$EXISTING 已不存在），清理后继续。"
        fi
        rm -f "$PID_FILE"
    fi
fi

# ---- 启动 ----
step "启动 java -jar $JAR_PATH（端口 $SERVER_PORT）"

# 决定运行用户：本进程是 visualspider（或运行身份 = APP_USER）就直接 java；root 运行时切换
RUN_AS=""
if [ "$(id -u)" -eq 0 ]; then
    RUN_AS="$APP_USER"
    info "检测到 root；将通过 runuser 切到 $APP_USER 启动"
elif [ "$(id -un)" != "$APP_USER" ]; then
    info "当前用户 $(id -un) ≠ $APP_USER；将继续以当前身份启动（systemd User= 会自动校正）"
fi

if [ -n "$RUN_AS" ]; then
    runuser -u "$RUN_AS" -- env PLAYWRIGHT_BROWSERS_PATH="${PLAYWRIGHT_BROWSERS_PATH:-0}" \
        nohup java -jar "$JAR_PATH" >"$OUT_LOG" 2>"$ERR_LOG" &
    PID=$!
else
    PLAYWRIGHT_BROWSERS_PATH="${PLAYWRIGHT_BROWSERS_PATH:-0}" \
        nohup java -jar "$JAR_PATH" >"$OUT_LOG" 2>"$ERR_LOG" &
    PID=$!
fi

echo "$PID" >"$PID_FILE"
info "已启动 pid=$PID，日志：$OUT_LOG / $ERR_LOG"

# chown 日志（root 启动时）
if [ -n "$RUN_AS" ]; then
    chown "$RUN_AS:$RUN_AS" "$OUT_LOG" "$ERR_LOG" "$PID_FILE" 2>/dev/null || true
fi

# ---- 健康检查 ----
step "等待 actuator/health UP（最长 ${HEALTH_TIMEOUT_SEC}s）"
HEALTH_URL="http://localhost:$SERVER_PORT/actuator/health"
UP=0
for _ in $(seq 1 "$HEALTH_TIMEOUT_SEC"); do
    BODY=$(curl -fsS -m 5 "$HEALTH_URL" 2>/dev/null || true)
    if [ -n "$BODY" ] && printf '%s' "$BODY" | grep -q '"status":"UP"'; then
        UP=1; break
    fi
    if ! kill -0 "$PID" 2>/dev/null; then
        TAIL=$(tail -n 50 "$ERR_LOG" 2>/dev/null || true)
        fail 3 "java 进程已退出。最近日志：\n$TAIL" 3
    fi
    sleep 1
done
if [ "$UP" -ne 1 ]; then
    TAIL=$(tail -n 50 "$ERR_LOG" 2>/dev/null || true)
    fail 3 "actuator/health 在 ${HEALTH_TIMEOUT_SEC}s 内未 UP（url=$HEALTH_URL）。最近日志：\n$TAIL" 3
fi
ok "actuator/health UP"

# ---- 最近日志 ----
info "最近日志（tail $TAIL_LINES 行，out.log）:"
tail -n "$TAIL_LINES" "$OUT_LOG" 2>/dev/null || true
exit 0