#!/usr/bin/env bash
# Visual Spider 5 — Linux 开发启动脚本（M1-5）
# 用途：检查环境变量 + 启动 JAR 到后台，写日志到 logs/app.out.log / logs/app.err.log，PID 到 logs/app.pid

set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$PROJECT_ROOT"

LOG_DIR="$PROJECT_ROOT/logs"
mkdir -p "$LOG_DIR"

fail() { echo "[FAIL] $1" >&2; exit 1; }
ok()   { echo "[OK]   $1"; }
info() { echo "[INFO] $1"; }

# 1. java 版本
if ! command -v java >/dev/null 2>&1; then
    fail "java 未安装"
fi
JAVA_VERSION="$(java -version 2>&1 | head -1 | sed -E 's/.*"([0-9]+)\.([0-9]+).*/\1/')"
if [ "${JAVA_VERSION:-0}" -lt 21 ]; then
    fail "java 版本过低: $(java -version 2>&1 | head -1)（需要 ≥ 21）"
fi
ok "java = $(java -version 2>&1 | head -1)"

# 2. 环境变量
if [ -z "${VISUALSPIDER_ADMIN_USERNAME:-}" ]; then
    fail "VISUALSPIDER_ADMIN_USERNAME 未设置"
fi
if [ -z "${VISUALSPIDER_ADMIN_PASSWORD:-}" ] || [ "${#VISUALSPIDER_ADMIN_PASSWORD}" -lt 12 ]; then
    fail "VISUALSPIDER_ADMIN_PASSWORD 未设置或长度 < 12"
fi
ok "admin 凭据已配置"

# 3. 构建 JAR（如不存在）
JAR_PATH="$PROJECT_ROOT/target/visual-spider5-0.0.1-SNAPSHOT.jar"
if [ ! -f "$JAR_PATH" ]; then
    info "未找到 JAR，开始构建..."
    ./mvnw package -DskipTests >/dev/null
fi

# 4. 启动 JAR 到后台
info "启动应用..."
nohup java -jar "$JAR_PATH" >"$LOG_DIR/app.out.log" 2>"$LOG_DIR/app.err.log" &
PID=$!
echo "$PID" >"$LOG_DIR/app.pid"

sleep 5
info "最近日志（tail 200）："
tail -n 200 "$LOG_DIR/app.out.log" || true
echo ""
info "PID: $PID"
info "健康检查： curl http://localhost:8080/actuator/health"
