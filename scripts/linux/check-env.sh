#!/usr/bin/env bash
# Visual Spider 5 — Linux 生产环境预检脚本（M7-2 / docs/specs/m7.md D2）
#
# 用途：在 start 之前核对 JDK、PG、端口、磁盘、Chromium 是否就绪。
#       部署资产，不依赖仓库源码。
#
# 检查项：
#   1) java >= 21
#   2) VISUALSPIDER_DATASOURCE_URL 可解析 + psql 可连通（密码不在错误输出中出现）
#   3) server.port（默认 8080）未被占用
#   4) 安装根目录剩余磁盘空间 >= MinDiskMb（默认 1024）
#   5) Chromium 已安装（ms-playwright 目录存在）
#
# 推荐运行身份：visualspider（与 start.sh 一致）；root 运行也可，会同时检查 /opt/visual-spider/.cache。
#
# 退出码：
#   0 = 全通过
#   1 = 参数缺失或值非法
#   2 = JDK 不达标
#   3 = PostgreSQL 不达标（未配 / 不可达）
#   4 = 端口被占用
#   5 = 磁盘空间不足
#   6 = Chromium 未安装

set -euo pipefail

INSTALL_DIR="${INSTALL_DIR:-/opt/visual-spider}"
DATA_SOURCE_URL="${VISUALSPIDER_DATASOURCE_URL:-jdbc:postgresql://localhost:5432/visualspider}"
DATA_SOURCE_USERNAME="${VISUALSPIDER_DATASOURCE_USERNAME:-visualspider}"
DATA_SOURCE_PASSWORD="${VISUALSPIDER_DATASOURCE_PASSWORD:-}"
SERVER_PORT="${SERVER_PORT:-8080}"
MIN_DISK_MB="${MIN_DISK_MB:-1024}"
APP_USER="${APP_USER:-visualspider}"

# ---- 参数校验 ----
[ -n "$INSTALL_DIR" ]        || { echo "[FAIL] INSTALL_DIR 不能为空" >&2; exit 1; }
[ -n "$DATA_SOURCE_URL" ]    || { echo "[FAIL] VISUALSPIDER_DATASOURCE_URL 不能为空" >&2; exit 1; }
[[ "$SERVER_PORT" =~ ^[0-9]+$ ]] || { echo "[FAIL] SERVER_PORT 必须为正整数" >&2; exit 1; }
[ "$SERVER_PORT" -ge 1 ] && [ "$SERVER_PORT" -le 65535 ] \
    || { echo "[FAIL] SERVER_PORT 必须在 1..65535" >&2; exit 1; }
[[ "$MIN_DISK_MB" =~ ^[0-9]+$ ]] && [ "$MIN_DISK_MB" -gt 0 ] \
    || { echo "[FAIL] MIN_DISK_MB 必须 > 0" >&2; exit 1; }

step() { printf '\n\033[0;36m[STEP]\033[0m %s\n' "$*"; }
ok()   { printf '\033[0;32m[OK]  \033[0m %s\n' "$*"; }
warn() { printf '\033[0;33m[WARN]\033[0m %s\n' "$*"; }
fail() { printf '\033[0;31m[FAIL]\033[0m %s\n' "$*" >&2; exit "${2:-1}"; }

# ---- 1. JDK ----
step "java 版本"
if ! command -v java >/dev/null 2>&1; then fail 2 "未在 PATH 中找到 java" 2; fi
JAVA_OUT=$(java -version 2>&1 | head -1 || true)
[ -n "$JAVA_OUT" ] || fail 2 "java -version 执行失败" 2
JAVA_MAJOR=$(printf '%s' "$JAVA_OUT" | sed -nE 's/.*"([0-9]+)(\.[0-9]+)*".*/\1/p' | head -1)
if [ -z "$JAVA_MAJOR" ] || [ "$JAVA_MAJOR" -lt 21 ]; then
    fail 2 "java 版本过低：$JAVA_OUT（需要 ≥ 21）" 2
fi
ok "java = $JAVA_OUT"

# ---- 2. PostgreSQL ----
step "PostgreSQL 连通性"
if ! command -v psql >/dev/null 2>&1; then
    fail 3 "未在 PATH 中找到 psql。请安装 PostgreSQL 16（推荐 pgdg apt）。" 3
fi
# 解析 jdbc:postgresql://host:port/db（端口/库名可选）
if ! [[ "$DATA_SOURCE_URL" =~ ^jdbc:postgresql://([^/:]+)(:([0-9]+))?(/([^?]+))?(\?.*)?$ ]]; then
    fail 3 "VISUALSPIDER_DATASOURCE_URL 格式非法：$DATA_SOURCE_URL（期望 jdbc:postgresql://host:port/db）" 3
fi
PG_HOST="${BASH_REMATCH[1]}"
PG_PORT="${BASH_REMATCH[3]:-5432}"
PG_DB="${BASH_REMATCH[5]:-postgres}"
ok "解析连接：host=$PG_HOST port=$PG_PORT db=$PG_DB"

# TCP 可达：/dev/tcp 是 bash 内置
if ! (exec 3<>"/dev/tcp/$PG_HOST/$PG_PORT") 2>/dev/null; then
    fail 3 "$PG_HOST:$PG_PORT 不可达（TCP 连接失败）" 3
fi
exec 3<&- 3>&- || true
ok "$PG_HOST:$PG_PORT TCP 可达"

# psql 登录验证。失败时仅报告退出码 + 行数（不打印原文以免泄漏密码/堆栈）
QUERY_OUT=$(PGPASSWORD="$DATA_SOURCE_PASSWORD" psql -h "$PG_HOST" -p "$PG_PORT" \
    -U "$DATA_SOURCE_USERNAME" -d "$PG_DB" -tAc 'SELECT 1' 2>&1) || PSQL_EXIT=$?
PSQL_EXIT=${PSQL_EXIT:-0}
QUERY_TRIM=$(printf '%s' "$QUERY_OUT" | sed -E 's/\s+//g' | head -c 64 || true)
if [ "$PSQL_EXIT" -ne 0 ] || [ "$QUERY_TRIM" != "1" ]; then
    LINE_COUNT=$(printf '%s\n' "$QUERY_OUT" | wc -l | tr -d ' ')
    fail 3 "psql 验证失败（用户 $DATA_SOURCE_USERNAME → $PG_HOST:$PG_PORT/$PG_DB）：退出码=$PSQL_EXIT，输出行数=$LINE_COUNT（密码等敏感字段已脱敏，请直接 psql 复现）" 3
fi
ok "psql 登录成功（用户 $DATA_SOURCE_USERNAME → $PG_DB）"

# ---- 3. 端口 ----
step "server.port = $SERVER_PORT"
if command -v ss >/dev/null 2>&1; then
    if ss -ltn 2>/dev/null | awk '{print $4}' | grep -E "(^|:)$SERVER_PORT$" >/dev/null; then
        OCCUPANT=$(ss -ltnp 2>/dev/null | awk -v p=":$SERVER_PORT" '$4 ~ p {print}' | head -1 | sed -E 's/.*pid=([0-9]+).*/\1/')
        fail 4 "端口 $SERVER_PORT 已被占用（pid=${OCCUPANT:-?}）。请改 SERVER_PORT 或先停占用者。" 4
    fi
elif command -v lsof >/dev/null 2>&1; then
    if lsof -iTCP:"$SERVER_PORT" -sTCP:LISTEN >/dev/null 2>&1; then
        fail 4 "端口 $SERVER_PORT 已被占用。请改 SERVER_PORT 或先停占用者。" 4
    fi
else
    warn "未找到 ss / lsof，跳过端口检查。请手动确认 $SERVER_PORT 未占用。"
fi
ok "端口 $SERVER_PORT 空闲"

# ---- 4. 磁盘 ----
step "安装目录磁盘空间"
if [ ! -d "$INSTALL_DIR" ]; then
    mkdir -p "$INSTALL_DIR"
    chown -R "$APP_USER:$APP_USER" "$INSTALL_DIR" 2>/dev/null || true
fi
FREE_MB=$(df -Pm "$INSTALL_DIR" 2>/dev/null | awk 'NR==2 {print $4}')
if [ -z "$FREE_MB" ]; then fail 5 "无法读取 $INSTALL_DIR 所在磁盘剩余空间" 5; fi
if [ "$FREE_MB" -lt "$MIN_DISK_MB" ]; then
    fail 5 "磁盘空间不足：${FREE_MB}MB（需要 ≥ ${MIN_DISK_MB}MB）" 5
fi
ok "$INSTALL_DIR 所在磁盘剩余 ${FREE_MB}MB"

# ---- 5. Chromium ----
step "Chromium 安装"
# 既检查运行用户的 HOME，也检查 INSTALL_DIR/.cache（systemd 用户的 HOME 可能指向 INSTALL_DIR）
PW_CANDIDATES=(
    "${HOME:-}/.cache/ms-playwright"
    "$INSTALL_DIR/.cache/ms-playwright"
)
FOUND=""
for d in "${PW_CANDIDATES[@]}"; do
    if [ -d "$d" ] && compgen -G "$d/chromium-*" >/dev/null; then
        FOUND="$d"
        break
    fi
done
if [ -z "$FOUND" ]; then
    warn "未在默认位置找到 ms-playwright 下的 chromium-*：${PW_CANDIDATES[*]}"
    warn "  建议先运行 ./install.sh。"
    fail 6 "Chromium 未安装" 6
fi
CR_VERSIONS=$(compgen -G "$FOUND/chromium-*" | wc -l | tr -d ' ')
ok "Chromium 已安装：$FOUND（共 $CR_VERSIONS 个版本目录）"

printf '\n\033[0;32m环境预检通过。可执行 ./start.sh\033[0m\n'
exit 0