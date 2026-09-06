#!/usr/bin/env bash
# Visual Spider 5 — Linux 生产环境一次性安装脚本（M7-2 / docs/specs/m7.md D1/D3）
#
# 用途：把 JAR + 运行依赖一次性装好。本脚本是部署资产，不依赖仓库源码。
#       默认安装根目录 = /opt/visual-spider（可用 --install-dir 覆盖）。
#
# 步骤：
#   1) 创建专用系统用户 visualspider（无登录 shell）
#   2) 准备 /opt/visual-spider/{app.jar, config/, logs/} + chown
#   3) 校验 JDK >= 21（PATH 里 java 可用）
#   4) 校验 PostgreSQL 16 客户端（psql 可用；连通性不在此处做，留给 check-env）
#   5) 拷贝 JAR 到 /opt/visual-spider/app.jar（若不在该位置）
#   6) Linux 专属：playwright install-deps（Chromium 系统依赖）
#   7) Playwright CLI 安装 Chromium，把实际 revision 写入 logs/install.log
#
# 注意：本脚本须以 root 运行（useradd / apt-get 需要权限）。
# 已存在 visualspider 用户 / /opt/visual-spider 时复用，不破坏现有数据。
#
# 退出码：
#   0 = 成功
#   1 = 参数缺失或值非法
#   2 = 前置条件不满足（缺 java / 缺 psql / 缺 JAR / 缺 root）
#   3 = 安装失败（playwright install-deps 或 install chromium 失败）

set -euo pipefail

INSTALL_DIR="${INSTALL_DIR:-/opt/visual-spider}"
JAR_PATH="${JAR_PATH:-}"
PLAYWRIGHT_TIMEOUT_SEC="${PLAYWRIGHT_TIMEOUT_SEC:-600}"
APP_USER="${APP_USER:-visualspider}"

# ---- 参数校验 ----
if [ -z "$INSTALL_DIR" ]; then echo "[FAIL] INSTALL_DIR 不能为空" >&2; exit 1; fi
if [ -z "$JAR_PATH" ]; then JAR_PATH="$INSTALL_DIR/app.jar"; fi
if [ -z "$APP_USER" ]; then echo "[FAIL] APP_USER 不能为空" >&2; exit 1; fi
if ! [[ "$PLAYWRIGHT_TIMEOUT_SEC" =~ ^[0-9]+$ ]] || [ "$PLAYWRIGHT_TIMEOUT_SEC" -le 0 ]; then
    echo "[FAIL] PLAYWRIGHT_TIMEOUT_SEC 必须 > 0" >&2; exit 1
fi

# ---- 颜色/函数 ----
step() { printf '\n\033[0;36m[STEP]\033[0m %s\n' "$*"; }
ok()   { printf '\033[0;32m[OK]  \033[0m %s\n' "$*"; }
info() { printf '\033[0;90m[INFO]\033[0m %s\n' "$*"; }
warn() { printf '\033[0;33m[WARN]\033[0m %s\n' "$*"; }
fail() { printf '\033[0;31m[FAIL]\033[0m %s\n' "$*" >&2; exit "${2:-1}"; }

# ---- 0. 权限 ----
step "检查 root 权限（useradd / apt-get 需要）"
if [ "$(id -u)" -ne 0 ]; then
    fail 2 "本脚本须以 root 运行（useradd / 系统包安装）。请使用 sudo $0" 2
fi
ok "root 权限就绪"

# ---- 1. 系统用户 ----
step "准备专用系统用户 $APP_USER"
if id -u "$APP_USER" >/dev/null 2>&1; then
    info "用户 $APP_USER 已存在，复用"
else
    useradd --system --shell /usr/sbin/nologin \
        --home "$INSTALL_DIR" --no-create-home \
        "$APP_USER"
    ok "已创建用户 $APP_USER（uid=$(id -u "$APP_USER"))"
fi

# ---- 2. 目录 ----
step "准备安装目录：$INSTALL_DIR"
mkdir -p "$INSTALL_DIR/config" "$INSTALL_DIR/logs"
chown -R "$APP_USER:$APP_USER" "$INSTALL_DIR"
ok "目录就绪：$INSTALL_DIR (owner=$APP_USER:$APP_USER)"

# ---- 3. JDK ----
step "检查 JDK（需要 Temurin 21 LTS 或同主版本兼容发行版）"
if ! command -v java >/dev/null 2>&1; then
    fail 2 "未在 PATH 中找到 java。请先安装 Eclipse Temurin 21 LTS（apt: temurin-21-jdk）。" 2
fi
JAVA_OUT=$(java -version 2>&1 | head -1 || true)
if [ -z "$JAVA_OUT" ]; then fail 2 "java -version 执行失败" 2; fi
JAVA_VERSION=$(printf '%s' "$JAVA_OUT" | sed -nE 's/.*"([0-9]+)(\.[0-9]+)*".*/\1/p' | head -1)
if [ -z "$JAVA_VERSION" ] || [ "$JAVA_VERSION" -lt 21 ]; then
    fail 2 "java 版本过低：$JAVA_OUT（需要 ≥ 21）" 2
fi
ok "java = $JAVA_OUT"

# ---- 4. PostgreSQL 客户端 ----
step "检查 PostgreSQL 16 客户端（psql）"
if ! command -v psql >/dev/null 2>&1; then
    warn "未在 PATH 中找到 psql。请确认 PostgreSQL 16 已安装（推荐 pgdg apt）。"
    warn "  本脚本不强制 psql，但 check-env.sh 会拒绝通过。"
else
    PSQL_OUT=$(psql --version 2>&1 | head -1 || true)
    ok "psql = $PSQL_OUT"
fi

# ---- 5. JAR ----
step "校验 JAR：$JAR_PATH"
if [ ! -f "$JAR_PATH" ]; then
    fail 2 "未找到 JAR：$JAR_PATH。请把 visual-spider5-*.jar 拷到该路径，或通过 JAR_PATH=/path/to.jar 指定。" 2
fi
EXPECTED_DEST="$INSTALL_DIR/app.jar"
if [ "$(readlink -f "$JAR_PATH")" != "$(readlink -f "$EXPECTED_DEST")" ]; then
    cp -f "$JAR_PATH" "$EXPECTED_DEST"
    ok "JAR 已拷贝到：$EXPECTED_DEST"
else
    ok "JAR 已就位：$EXPECTED_DEST"
fi
chown "$APP_USER:$APP_USER" "$EXPECTED_DEST"

# ---- 6. Playwright 系统依赖（Linux 专属） ----
step "安装 Chromium 系统依赖（playwright install-deps，需 apt）"
if command -v apt-get >/dev/null 2>&1; then
    # install-deps 需要 sudo；以 root 身份运行此脚本时已具备权限
    if timeout "$PLAYWRIGHT_TIMEOUT_SEC" java -cp "$EXPECTED_DEST" com.microsoft.playwright.CLI install-deps chromium 2>&1 \
        | tee -a "$INSTALL_DIR/logs/install.log"; then
        ok "Chromium 系统依赖安装完成（详见 logs/install.log）"
    else
        warn "playwright install-deps 失败（退出码 $?）。请手动 apt-get 装齐依赖后重跑。"
        warn "  Ubuntu 24.04 常见缺失包：libnss3 libnspr4 libdbus-1-3 libatk1.0-0 libatk-bridge2.0-0 libcups2 libxkbcommon0 libatspi2.0-0 libxcomposite1 libxdamage1 libxfixes3 libxrandr2 libgbm1 libpango-1.0-0 libcairo2 libasound2t64"
    fi
else
    warn "未检测到 apt-get，跳过 install-deps。请手动确认 Chromium 依赖已装。"
fi

# ---- 7. Chromium ----
step "安装 Chromium（Playwright CLI）并把 revision 写入 logs/install.log"
INSTALL_LOG="$INSTALL_DIR/logs/install.log"
STAMP=$(date '+%Y-%m-%d %H:%M:%S')
{
    echo "=== install.sh @ $STAMP ==="
    echo "java: $JAVA_OUT"
    echo "jar:  $EXPECTED_DEST"
} >>"$INSTALL_LOG"
chown "$APP_USER:$APP_USER" "$INSTALL_LOG"

# 以 visualspider 用户身份运行 Chromium 安装（revision 落到该用户 ~/.cache）
PW_OUT=$(runuser -u "$APP_USER" -- env PLAYWRIGHT_BROWSERS_PATH=0 \
    timeout "$PLAYWRIGHT_TIMEOUT_SEC" java -cp "$EXPECTED_DEST" com.microsoft.playwright.CLI install chromium 2>&1) || PW_EXIT=$?
PW_EXIT=${PW_EXIT:-0}
printf '%s\n' "$PW_OUT" >>"$INSTALL_LOG"
if [ "$PW_EXIT" -ne 0 ]; then
    fail 3 "Playwright CLI install chromium 失败（退出码 $PW_EXIT）。详情见 $INSTALL_LOG" 3
fi

# 解析实际 Chromium revision 并打印
REVISION=$(printf '%s\n' "$PW_OUT" | grep -oE 'chromium[- ]([0-9]+)' | head -1 | sed -E 's/.*[- ]([0-9]+)/\1/')
if [ -n "$REVISION" ]; then
    echo "chromium revision: $REVISION" >>"$INSTALL_LOG"
    ok "Chromium 安装完成（revision=$REVISION，详见 $INSTALL_LOG）"
else
    ok "Chromium 安装完成（未捕获到 revision 行，详见 $INSTALL_LOG）"
fi

# ---- 完成 ----
cat <<EOF

安装完成。下一步：
  1) 编辑 $INSTALL_DIR/config/visual-spider.env（参考 docs/deploy/configuration.md）
  2) sudo -u $APP_USER ./check-env.sh   （以 $APP_USER 身份预检；确认 PG/端口/磁盘/Chromium）
  3) ./start.sh                          （root 启动后切到 $APP_USER）
  4) 安装 systemd unit（详见 docs/deploy/linux.md §systemd）：
       cp scripts/linux/visual-spider.service /etc/systemd/system/
       systemctl daemon-reload
       systemctl enable --now visual-spider.service
  5) 浏览器访问 http://<host>:8080/  用 VISUALSPIDER_ADMIN_USERNAME / VISUALSPIDER_ADMIN_PASSWORD 登录

EOF
exit 0