#!/usr/bin/env bash
# Visual Spider 5 — M7 acceptance（Linux / bash）
#
# 文档依据：docs/specs/m7.md D6、D7。
# 与 docs/e2e/m7-acceptance.ps1（Windows 端）步骤一一对应；脚本自管 JAR 启动与 fixture server。
#
# 前置：本地 PG（业务库 visualspider + 用户 visualspider）、JDK 21、
#       Playwright Chromium 已装、Python 3（仅 fixture HTTP server）、bash + curl + grep/sed。
#
# 环境变量：
#   BASE_URL         默认 http://localhost:8080
#   JAR_PATH         默认 target/visual-spider5-0.0.1-SNAPSHOT.jar
#   FIXTURE_PORT     默认 18080
#   FIXTURE_DIR      默认 src/test/resources
#   LOOPBACK_ALLOWED 默认 true（仅测试；文档与脚本注释均标注）
#   HEALTH_TIMEOUT_SEC 默认 120
#   RUN_WAIT_SEC     默认 180
#
# 退出码：
#   0 = 全链路通过
#   1 = 通用失败（带 STEP N 前缀）
#   2 = 前置条件缺失（PG / python / jar / chromium）

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
PROJECT_ROOT="$(pwd)"
JAR_PATH="${JAR_PATH:-$PROJECT_ROOT/target/visual-spider5-0.0.1-SNAPSHOT.jar}"
FIXTURE_PORT="${FIXTURE_PORT:-18080}"
FIXTURE_DIR="${FIXTURE_DIR:-$PROJECT_ROOT/src/test/resources}"
LOOPBACK_ALLOWED="${LOOPBACK_ALLOWED:-true}"
HEALTH_TIMEOUT_SEC="${HEALTH_TIMEOUT_SEC:-120}"
RUN_WAIT_SEC="${RUN_WAIT_SEC:-180}"

LOG_DIR="$PROJECT_ROOT/logs"
mkdir -p "$LOG_DIR"
APP_OUT_LOG="$LOG_DIR/m7-acceptance-app.out.log"
APP_ERR_LOG="$LOG_DIR/m7-acceptance-app.err.log"
APP_PID_FILE="$LOG_DIR/m7-acceptance-app.pid"
FIX_OUT_LOG="$LOG_DIR/m7-acceptance-fix.out.log"
FIX_ERR_LOG="$LOG_DIR/m7-acceptance-fix.err.log"
FIX_PID_FILE="$LOG_DIR/m7-acceptance-fix.pid"

COOKIE_ADMIN=$(mktemp)
COOKIE_COLL=$(mktemp)
COOKIE_OTHER=$(mktemp)
JSON_DIR=$(mktemp -d)
trap 'cleanup' EXIT INT TERM

step() { printf '\n\033[1;36m[STEP %s] %s\033[0m\n' "$1" "$2"; }
ok()   { printf '\033[0;32m[OK]  \033[0m %s\n' "$1"; }
info() { printf '\033[0;90m[INFO]\033[0m %s\n' "$1"; }
fail() { printf '\033[0;31m[FAIL step %s]\033[0m %s\n' "$1" "$2" >&2; cleanup; exit "${3:-1}"; }

extract_value() { grep -o "\"$2\":[^,}]*" "$1" | head -1 | sed -E "s/\"$2\"://;s/^\"//;s/\"$//"; }

get_xsrf() {
    grep -i 'XSRF-TOKEN' "$1" 2>/dev/null | tail -1 | awk '{print $NF}'
}

cleanup() {
    [ -f "$FIX_PID_FILE" ] && kill "$(cat "$FIX_PID_FILE" 2>/dev/null)" 2>/dev/null || true
    [ -f "$APP_PID_FILE" ] && kill "$(cat "$APP_PID_FILE" 2>/dev/null)" 2>/dev/null || true
    rm -f "$COOKIE_ADMIN" "$COOKIE_COLL" "$COOKIE_OTHER"
    rm -rf "$JSON_DIR"
    rm -f "$FIX_PID_FILE" "$APP_PID_FILE"
}

stop_app() {
    if [ -f "$APP_PID_FILE" ]; then
        PID=$(cat "$APP_PID_FILE" 2>/dev/null || true)
        if [ -n "$PID" ] && kill -0 "$PID" 2>/dev/null; then
            kill -TERM "$PID" 2>/dev/null || true
            for _ in $(seq 1 30); do
                if ! kill -0 "$PID" 2>/dev/null; then break; fi
                sleep 1
            done
            kill -KILL "$PID" 2>/dev/null || true
        fi
    fi
}

# 14. Linux 资源回收 smoke（M0 延后项）：运行前后对比 Chromium / Playwright Java 进程数
step 14 '运行前后 Chromium / Playwright Java 进程数核对'
COUNT_BEFORE_CHROME=$(pgrep -fc 'chromium|chrome' || true)
COUNT_BEFORE_PW=$(pgrep -fc 'playwright' || true)
info "before: chrome=$COUNT_BEFORE_CHROME playwright=$COUNT_BEFORE_PW"

# ---- 2. 前置条件 ----
step 2 '检查前置条件（jar / python / fixture 目录）'
[ -f "$JAR_PATH" ] || fail 2 "JAR 缺失：$JAR_PATH。请先 ./mvnw package -DskipTests" 2
command -v python >/dev/null 2>&1 || fail 2 "未找到 python。请安装 Python 3（仅 fixture HTTP server 用，非运行时依赖）" 2
[ -d "$FIXTURE_DIR/pagination" ]   || fail 2 "fixture 目录缺 pagination/：$FIXTURE_DIR" 2
[ -d "$FIXTURE_DIR/content-page" ] || fail 2 "fixture 目录缺 content-page/：$FIXTURE_DIR" 2

# ---- 3. 启 fixture ----
step 3 "启动 fixture HTTP server（python -m http.server，端口 $FIXTURE_PORT）"
nohup python -m http.server "$FIXTURE_PORT" --bind 127.0.0.1 \
    >"$FIX_OUT_LOG" 2>"$FIX_ERR_LOG" &
FIX_PID=$!
echo "$FIX_PID" >"$FIX_PID_FILE"
sleep 1
curl -fsS -m 5 "http://127.0.0.1:$FIXTURE_PORT/pagination/next-page.html" >/dev/null \
    || fail 3 "fixture HTTP server 未启动" 3
ok "fixture server 已起 (pid=$FIX_PID)"

# ---- 4. 启应用 ----
SERVER_PORT="${BASE_URL##*:}"
SERVER_PORT="${SERVER_PORT%/}"
SERVER_PORT="${SERVER_PORT:-8080}"
step 4 "启动应用（loopback-豁免=$LOOPBACK_ALLOWED，端口 $SERVER_PORT）"
JAVA_ARGS=("-jar" "$JAR_PATH" "--server.port=$SERVER_PORT")
if [ "$LOOPBACK_ALLOWED" = "true" ]; then
    JAVA_ARGS+=("--visualbrowser.target-url.allow-loopback=true")
fi
nohup java "${JAVA_ARGS[@]}" >"$APP_OUT_LOG" 2>"$APP_ERR_LOG" &
APP_PID=$!
echo "$APP_PID" >"$APP_PID_FILE"
info "JAR PID=$APP_PID"

UP=0
for _ in $(seq 1 "$HEALTH_TIMEOUT_SEC"); do
    sleep 1
    BODY=$(curl -fsS -m 5 "$BASE_URL/actuator/health" 2>/dev/null || true)
    if [ -n "$BODY" ] && echo "$BODY" | grep -q '"status":"UP"'; then UP=1; break; fi
    if ! kill -0 "$APP_PID" 2>/dev/null; then
        TAIL=$(tail -n 30 "$APP_ERR_LOG" 2>/dev/null || true)
        fail 4 "JVM 已退出。最近日志：$TAIL" 4
    fi
done
[ "$UP" -eq 1 ] || fail 4 "actuator/health 在 ${HEALTH_TIMEOUT_SEC}s 内未 UP" 4
ok 'actuator/health UP'

# ---- 5. admin 登录 ----
ADMIN_USER="${VISUALSPIDER_ADMIN_USERNAME:-admin}"
ADMIN_PWD="${VISUALSPIDER_ADMIN_PASSWORD:-change-me-please-12+}"
info "admin=$ADMIN_USER"
curl -s -c "$COOKIE_ADMIN" "$BASE_URL/api/auth/login-status" >/dev/null 2>&1 || true
HTTP=$(curl -s -o "$JSON_DIR/admin.json" -w "%{http_code}" \
    -X POST -H 'Content-Type: application/json' \
    -c "$COOKIE_ADMIN" -b "$COOKIE_ADMIN" \
    -d "{\"username\":\"$ADMIN_USER\",\"password\":\"$ADMIN_PWD\"}" \
    "$BASE_URL/api/auth/login")
[ "$HTTP" = "200" ] || fail 5 "admin 登录 HTTP $HTTP" 5
XSRF_ADMIN=$(get_xsrf "$COOKIE_ADMIN")
[ -n "$XSRF_ADMIN" ] || fail 5 "未拿到 admin XSRF" 5
ok "admin 已登录 (XSRF len=${#XSRF_ADMIN})"

# ---- 6. 创建 collector + 登录 ----
COLLECTOR="m7_coll_$$"
COLLECTOR_PWD="m7_coll_pwd_12+"
HTTP=$(curl -s -o "$JSON_DIR/coll_create.json" -w "%{http_code}" \
    -X POST -H 'Content-Type: application/json' \
    -H "X-XSRF-TOKEN: $XSRF_ADMIN" \
    -b "$COOKIE_ADMIN" \
    -d "{\"username\":\"$COLLECTOR\",\"password\":\"$COLLECTOR_PWD\",\"role\":\"COLLECTOR\"}" \
    "$BASE_URL/api/admin/users")
[ "$HTTP" = "201" ] || fail 6 "创建 collector HTTP $HTTP" 6
HTTP=$(curl -s -o "$JSON_DIR/coll_login.json" -w "%{http_code}" \
    -X POST -H 'Content-Type: application/json' \
    -c "$COOKIE_COLL" \
    -d "{\"username\":\"$COLLECTOR\",\"password\":\"$COLLECTOR_PWD\"}" \
    "$BASE_URL/api/auth/login")
[ "$HTTP" = "200" ] || fail 6 "collector 登录 HTTP $HTTP" 6
XSRF_COLL=$(get_xsrf "$COOKIE_COLL")
[ -n "$XSRF_COLL" ] || fail 6 "未拿到 collector XSRF" 6
ok "collector 已登录：$COLLECTOR"

# 工具：建任务 + 切 READY + 启运行 + 等终态
# 用法：start_and_wait "$name" "$definition_json"
start_and_wait() {
    local name="$1"
    local def="$2"
    local exp_status="${3:-SUCCESS}"
    local save_body out task_resp task_id task_ver run_id st final end_ts
    save_body=$(printf '{"expectedVersion":0,"definition":%s}' "$def")
    # 用 POST 创建任务
    HTTP=$(curl -s -o "$JSON_DIR/task.json" -w "%{http_code}" \
        -X POST -H 'Content-Type: application/json' \
        -H "X-XSRF-TOKEN: $XSRF_COLL" \
        -b "$COOKIE_COLL" \
        -d "{\"name\":\"$name\",\"definition\":$def}" \
        "$BASE_URL/api/tasks")
    [ "$HTTP" = "201" ] || fail 7 "建任务 $name HTTP $HTTP: $(cat "$JSON_DIR/task.json")"
    task_id=$(extract_value "$JSON_DIR/task.json" id)
    task_ver=$(extract_value "$JSON_DIR/task.json" version)
    # 切 READY
    save_body="{\"expectedVersion\":$task_ver,\"definition\":$def}"
    HTTP=$(curl -s -o "$JSON_DIR/save.json" -w "%{http_code}" \
        -X PUT -H 'Content-Type: application/json' \
        -H "X-XSRF-TOKEN: $XSRF_COLL" \
        -b "$COOKIE_COLL" \
        -d "$save_body" "$BASE_URL/api/tasks/$task_id")
    [ "$HTTP" = "200" ] || fail 7 "任务 $name 切 READY HTTP $HTTP: $(cat "$JSON_DIR/save.json")"
    # 启运行
    HTTP=$(curl -s -o "$JSON_DIR/run.json" -w "%{http_code}" \
        -X POST -H 'Content-Type: application/json' \
        -H "X-XSRF-TOKEN: $XSRF_COLL" \
        -b "$COOKIE_COLL" \
        -d "{\"taskId\":\"$task_id\"}" \
        "$BASE_URL/api/runs")
    [ "$HTTP" = "202" ] || fail 7 "启动运行 $name HTTP $HTTP: $(cat "$JSON_DIR/run.json")"
    run_id=$(extract_value "$JSON_DIR/run.json" runId)
    info "run_id=$run_id"
    # 等终态
    final=""
    end_ts=$(( $(date +%s) + RUN_WAIT_SEC ))
    while [ "$(date +%s)" -lt "$end_ts" ]; do
        sleep 2
        BODY=$(curl -s -b "$COOKIE_COLL" "$BASE_URL/api/runs/$run_id" 2>/dev/null || true)
        st=$(printf '%s' "$BODY" | grep -oE '"status":"[A-Z_]+"' | head -1 | sed -E 's/.*"([A-Z_]+)".*/\1/')
        if [ -z "$st" ]; then continue; fi
        case "$st" in
            SUCCESS|PARTIAL_SUCCESS|FAILED|CANCELLED|INTERRUPTED) final="$st"; break ;;
        esac
    done
    [ -n "$final" ] || fail 7 "运行 $name 在 ${RUN_WAIT_SEC}s 内未终态" 7
    case "$final" in
        "$exp_status"|SUCCESS|PARTIAL_SUCCESS) ok "$name 终态 $final (run=$run_id)" ;;
        *) fail 7 "$name 终态应为 $exp_status，实际 $final (run=$run_id)" 7 ;;
    esac
}

# ---- 7. 单页任务 ----
step 7 '单页任务：建任务 + 启运行 + 等待 TERMINAL'
SINGLE_DEF=$(cat <<EOF
{"schemaVersion":4,"mode":"SINGLE_PAGE","startUrl":"http://127.0.0.1:$FIXTURE_PORT/pagination/next-page.html","viewport":{"width":1280,"height":720},"fields":[{"name":"title","selectorType":"CSS","selector":"h1","resultType":"TEXT","trim":"TRIM","required":true}]}
EOF
)
start_and_wait "m7-acc-single-sh" "$SINGLE_DEF" SUCCESS

# ---- 8. 列表任务（分页翻页） ----
step 8 '列表任务：分页翻页（pagination/next-page.html fixture）'
LIST_DEF=$(cat <<EOF
{"schemaVersion":4,"mode":"LIST","startUrl":"http://127.0.0.1:$FIXTURE_PORT/pagination/next-page.html","viewport":{"width":1280,"height":720},"pagination":{"type":"NEXT_PAGE","selector":"a.next"},"fields":[{"name":"title","selectorType":"CSS","selector":"h1","resultType":"TEXT","trim":"TRIM","required":true}]}
EOF
)
start_and_wait "m7-acc-list-sh" "$LIST_DEF" SUCCESS

# ---- 9. 内容页任务 ----
step 9 '内容页任务：内容页前 3 条 + 字段合并（content-page fixture）'
CONTENT_DEF=$(cat <<EOF
{"schemaVersion":4,"mode":"LIST","startUrl":"http://127.0.0.1:$FIXTURE_PORT/pagination/next-page.html","viewport":{"width":1280,"height":720},"pagination":{"type":"NEXT_PAGE","selector":"a.next"},"content":{"selector":"a.content-link","limit":3,"fields":[{"name":"body","selectorType":"CSS","selector":"p","resultType":"TEXT","trim":"TRIM","required":false}]},"fields":[{"name":"title","selectorType":"CSS","selector":"h1","resultType":"TEXT","trim":"TRIM","required":true}]}
EOF
)
start_and_wait "m7-acc-content-sh" "$CONTENT_DEF" SUCCESS

# ---- 10. 取消运行 ----
step 10 '取消运行：建任务 → 启运行 → POST /cancel → 断言 CANCELLED'
CANCEL_DEF=$(cat <<EOF
{"schemaVersion":4,"mode":"LIST","startUrl":"http://127.0.0.1:$FIXTURE_PORT/pagination/next-page.html","viewport":{"width":1280,"height":720},"pagination":{"type":"NEXT_PAGE","selector":"a.next"},"fields":[{"name":"title","selectorType":"CSS","selector":"h1","resultType":"TEXT","trim":"TRIM","required":true}]}
EOF
)
# 建任务
HTTP=$(curl -s -o "$JSON_DIR/cancel_task.json" -w "%{http_code}" \
    -X POST -H 'Content-Type: application/json' \
    -H "X-XSRF-TOKEN: $XSRF_COLL" \
    -b "$COOKIE_COLL" \
    -d "{\"name\":\"m7-acc-cancel-sh\",\"definition\":$CANCEL_DEF}" \
    "$BASE_URL/api/tasks")
[ "$HTTP" = "201" ] || fail 10 "建 cancel 任务 HTTP $HTTP" 10
CTID=$(extract_value "$JSON_DIR/cancel_task.json" id)
CTV=$(extract_value "$JSON_DIR/cancel_task.json" version)
HTTP=$(curl -s -o /dev/null -w "%{http_code}" \
    -X PUT -H 'Content-Type: application/json' \
    -H "X-XSRF-TOKEN: $XSRF_COLL" \
    -b "$COOKIE_COLL" \
    -d "{\"expectedVersion\":$CTV,\"definition\":$CANCEL_DEF}" \
    "$BASE_URL/api/tasks/$CTID")
[ "$HTTP" = "200" ] || fail 10 "cancel 任务切 READY HTTP $HTTP" 10
HTTP=$(curl -s -o "$JSON_DIR/cancel_run.json" -w "%{http_code}" \
    -X POST -H 'Content-Type: application/json' \
    -H "X-XSRF-TOKEN: $XSRF_COLL" \
    -b "$COOKIE_COLL" \
    -d "{\"taskId\":\"$CTID\"}" \
    "$BASE_URL/api/runs")
[ "$HTTP" = "202" ] || fail 10 "cancel 启运行 HTTP $HTTP" 10
CRID=$(extract_value "$JSON_DIR/cancel_run.json" runId)
sleep 1
HTTP=$(curl -s -o /dev/null -w "%{http_code}" \
    -X POST -H "X-XSRF-TOKEN: $XSRF_COLL" \
    -b "$COOKIE_COLL" \
    "$BASE_URL/api/runs/$CRID/cancel")
[ "$HTTP" = "200" ] || [ "$HTTP" = "204" ] || fail 10 "cancel 调用 HTTP $HTTP" 10
# 等终态
CANCEL_FINAL=""
END_TS=$(( $(date +%s) + 60 ))
while [ "$(date +%s)" -lt "$END_TS" ]; do
    sleep 1
    BODY=$(curl -s -b "$COOKIE_COLL" "$BASE_URL/api/runs/$CRID" 2>/dev/null || true)
    st=$(printf '%s' "$BODY" | grep -oE '"status":"[A-Z_]+"' | head -1 | sed -E 's/.*"([A-Z_]+)".*/\1/')
    case "$st" in
        SUCCESS|PARTIAL_SUCCESS|FAILED|CANCELLED|INTERRUPTED) CANCEL_FINAL="$st"; break ;;
    esac
done
[ "$CANCEL_FINAL" = "CANCELLED" ] || fail 10 "cancel 终态应为 CANCELLED，实际 $CANCEL_FINAL" 10
ok "cancel 路径终态 CANCELLED"

# ---- 11. 导出 CSV / JSON（用第一个运行的 run_id = m7-acc-single-sh 的最后 run_id） ----
step 11 '导出 CSV / JSON 行数校验'
# 重新查第一个任务的 run（前面 start_and_wait 已跑过；用 run_id 难拿；这里取列表中第一个）
FIRST_RUN=$(curl -s -b "$COOKIE_COLL" "$BASE_URL/api/runs" 2>/dev/null | grep -oE '"runId":"[^"]+"' | head -1 | sed -E 's/.*"([^"]+)".*/\1/' || true)
[ -n "$FIRST_RUN" ] || fail 11 "无运行可导出" 11
HTTP=$(curl -s -o "$JSON_DIR/export.csv" -w "%{http_code}" -b "$COOKIE_COLL" "$BASE_URL/api/runs/$FIRST_RUN/results/export?format=csv")
[ "$HTTP" = "200" ] || fail 11 "CSV 导出 HTTP $HTTP" 11
CSV_LINES=$(grep -c . "$JSON_DIR/export.csv" 2>/dev/null || echo 0)
[ "$CSV_LINES" -ge 2 ] || fail 11 "CSV 行数 < 2（应 ≥ 1 header + ≥ 1 data）：$CSV_LINES" 11
HTTP=$(curl -s -o "$JSON_DIR/export.json" -w "%{http_code}" -b "$COOKIE_COLL" "$BASE_URL/api/runs/$FIRST_RUN/results/export?format=json")
[ "$HTTP" = "200" ] || fail 11 "JSON 导出 HTTP $HTTP" 11
JSON_COUNT=$(grep -oE '"runId"|"recordId"' "$JSON_DIR/export.json" 2>/dev/null | wc -l | tr -d ' ')
[ "$JSON_COUNT" -ge 1 ] || fail 11 "JSON 导出条数 < 1：$JSON_COUNT" 11
ok "CSV=$CSV_LINES 行; JSON=$JSON_COUNT 字段引用"

# ---- 12. 跨用户权限负向 ----
step 12 '跨用户权限负向：second collector 访问 → 403/404'
OTHER="m7_other_$$"
OTHER_PWD="m7_other_pwd_12+"
HTTP=$(curl -s -o /dev/null -w "%{http_code}" \
    -X POST -H 'Content-Type: application/json' \
    -H "X-XSRF-TOKEN: $XSRF_ADMIN" \
    -b "$COOKIE_ADMIN" \
    -d "{\"username\":\"$OTHER\",\"password\":\"$OTHER_PWD\",\"role\":\"COLLECTOR\"}" \
    "$BASE_URL/api/admin/users")
[ "$HTTP" = "201" ] || fail 12 "创建 other HTTP $HTTP" 12
HTTP=$(curl -s -o /dev/null -w "%{http_code}" \
    -X POST -H 'Content-Type: application/json' \
    -c "$COOKIE_OTHER" \
    -d "{\"username\":\"$OTHER\",\"password\":\"$OTHER_PWD\"}" \
    "$BASE_URL/api/auth/login")
[ "$HTTP" = "200" ] || fail 12 "other 登录 HTTP $HTTP" 12
# 跨用户访问第一个任务的 ID
SOME_TASK_ID=$(curl -s -b "$COOKIE_COLL" "$BASE_URL/api/tasks" | grep -oE '"id":"[^"]+"' | head -1 | sed -E 's/.*"([^"]+)".*/\1/')
[ -n "$SOME_TASK_ID" ] || fail 12 "无任务可测" 12
HTTP=$(curl -s -o /dev/null -w "%{http_code}" -b "$COOKIE_OTHER" "$BASE_URL/api/tasks/$SOME_TASK_ID")
[ "$HTTP" = "403" ] || [ "$HTTP" = "404" ] || fail 12 "跨用户访问应 403/404，实际 $HTTP" 12
ok '跨用户访问被拒（403/404）'

# ---- 13. retention ----
step 13 '触发 retention（admin REST PUT /api/admin/settings/retention.days）'
HTTP=$(curl -s -o /dev/null -w "%{http_code}" \
    -X PUT -H 'Content-Type: application/json' \
    -H "X-XSRF-TOKEN: $XSRF_ADMIN" \
    -b "$COOKIE_ADMIN" \
    -d '{"value":30}' \
    "$BASE_URL/api/admin/settings/retention.days")
[ "$HTTP" = "200" ] || fail 13 "retention PUT HTTP $HTTP（端点是否就绪）" 13
ok 'retention.days=30 PUT 成功'

# ---- 收尾：Linux 资源回收 smoke ----
step 15 '收尾：Chromium / Playwright Java 进程数核对'
info "等待 JVM 退出以核对进程回收"
stop_app
# 等几秒让进程自然结束
for _ in $(seq 1 10); do
    if ! kill -0 "$APP_PID" 2>/dev/null; then break; fi
    sleep 1
done
kill -KILL "$APP_PID" 2>/dev/null || true
[ -f "$FIX_PID_FILE" ] && kill "$(cat "$FIX_PID_FILE")" 2>/dev/null || true
sleep 2
COUNT_AFTER_CHROME=$(pgrep -fc 'chromium|chrome' || true)
COUNT_AFTER_PW=$(pgrep -fc 'playwright' || true)
info "after: chrome=$COUNT_AFTER_CHROME playwright=$COUNT_AFTER_PW"
DELTA_CHROME=$(( COUNT_AFTER_CHROME - COUNT_BEFORE_CHROME ))
DELTA_PW=$(( COUNT_AFTER_PW - COUNT_BEFORE_PW ))
info "delta: chrome=$DELTA_CHROME playwright=$DELTA_PW"
if [ "$DELTA_CHROME" -gt 0 ] || [ "$DELTA_PW" -gt 0 ]; then
    fail 15 "Chromium/Playwright 进程未完全回收（chrome delta=$DELTA_CHROME, playwright delta=$DELTA_PW）" 15
fi
ok '进程回收干净'

printf '\n\033[0;32m[OK] m7-acceptance 全部步骤通过\033[0m\n'
exit 0