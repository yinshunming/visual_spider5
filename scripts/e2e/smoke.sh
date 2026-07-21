#!/usr/bin/env bash
# Visual Spider 5 — End-to-end smoke 8 steps (M1-5 spec T3)
# 依赖：bash + curl；不依赖 python/jq。用 grep/sed 解析 JSON。
set -euo pipefail

BASE="${BASE_URL:-http://localhost:8080}"
COOKIE_A=$(mktemp); COOKIE_B=$(mktemp); COOKIE_C=$(mktemp)
JSON_DIR=$(mktemp -d)

extract_value() {
    # 用 sed 取出 JSON 字段：extract_value file key
    grep -o "\"$2\":[^,}]*" "$1" | head -1 | sed -E "s/\"$2\"://;s/^\"//;s/\"$//"
}

step()  { printf "\n\033[1;36m[STEP %s] %s\033[0m\n" "$1" "$2"; }
ok()    { printf "\033[1;32m[OK]\033[0m   %s\n" "$1"; }
fail()  { printf "\033[1;31m[FAIL]\033[0m %s\n" "$1"; exit 1; }
info()  { printf "\033[1;90m[INFO]\033[0m %s\n" "$1"; }

wait_health() {
    for i in $(seq 1 30); do
        local body
        body=$(curl -fsS "$BASE/actuator/health" 2>/dev/null || true)
        if [ -n "$body" ] && echo "$body" | grep -q '"status":"UP"'; then
            return 0
        fi
        sleep 2
    done
    return 1
}

# 1. 已经在外部启动 JAR（含 seed admin）
step 1 '检查应用已启动'
ADMIN_USER="${VISUALSPIDER_ADMIN_USERNAME:-admin}"
ADMIN_PWD="${VISUALSPIDER_ADMIN_PASSWORD:-change-me-please-12+}"
info "BASE=$BASE admin=$ADMIN_USER"

# 2. 健康检查
step 2 '等待健康 UP'
wait_health || fail "健康检查超时"
ok '/actuator/health = UP'

# 取初始 XSRF
curl -s -c "$COOKIE_A" "$BASE/api/auth/login-status" >/dev/null 2>&1 || true
XSRF_A=$(grep -i 'XSRF-TOKEN' "$COOKIE_A" 2>/dev/null | tail -1 | awk '{print $NF}')
info "pre-XSRF=${XSRF_A:-<none>}"

# 3. admin 登录（CSRF 豁免）；先尝试 GET 一次以确保服务端触发 CSRF token cookie
step 3 'admin 登录'
HTTP=$(curl -s -o "$JSON_DIR/admin_login.json" -w "%{http_code}" \
    -X POST -H 'Content-Type: application/json' \
    -c "$COOKIE_A" -b "$COOKIE_A" \
    -d "{\"username\":\"$ADMIN_USER\",\"password\":\"$ADMIN_PWD\"}" \
    "$BASE/api/auth/login")
[ "$HTTP" = "200" ] || fail "admin 登录 HTTP $HTTP: $(cat $JSON_DIR/admin_login.json)"

# 登录后服务端会写新的 XSRF token；提取之用于后续 CSRF 请求
XSRF_A=$(grep -i 'XSRF-TOKEN' "$COOKIE_A" | tail -1 | awk '{print $NF}')
info "post-login XSRF=${XSRF_A:-<none>}"
ok "admin 已登录 (HTTP 200)"

# 4. 创建 collector + collector 登录
step 4 '创建 collector + 登录'
COLLECTOR="coll_$$"
COLLECTOR_PWD="coll_pwd_12+"
HTTP=$(curl -s -o "$JSON_DIR/create.json" -w "%{http_code}" \
    -X POST -H 'Content-Type: application/json' \
    -H "X-XSRF-TOKEN: $XSRF_A" \
    -b "$COOKIE_A" \
    -d "{\"username\":\"$COLLECTOR\",\"password\":\"$COLLECTOR_PWD\",\"role\":\"COLLECTOR\"}" \
    "$BASE/api/admin/users")
[ "$HTTP" = "201" ] || fail "创建 collector 失败 HTTP $HTTP: $(cat $JSON_DIR/create.json)"
ok "collector 已创建: $COLLECTOR"

HTTP=$(curl -s -o "$JSON_DIR/collector_login.json" -w "%{http_code}" \
    -X POST -H 'Content-Type: application/json' \
    -c "$COOKIE_B" \
    -d "{\"username\":\"$COLLECTOR\",\"password\":\"$COLLECTOR_PWD\"}" \
    "$BASE/api/auth/login")
[ "$HTTP" = "200" ] || fail "collector 登录失败 HTTP $HTTP"
XSRF_B=$(grep -i 'XSRF-TOKEN' "$COOKIE_B" | tail -1 | awk '{print $NF}')
ok "collector 已登录"

# 5. 创建任务
step 5 '创建任务 + 列出 + 保存'
TASK_BODY='{"name":"smoke-task","definition":{"schemaVersion":1,"mode":"SINGLE_PAGE","startUrl":"https://example.com","viewport":{"width":1280,"height":720},"fields":[]}}'
HTTP=$(curl -s -o "$JSON_DIR/task.json" -w "%{http_code}" \
    -X POST -H 'Content-Type: application/json' \
    -H "X-XSRF-TOKEN: $XSRF_B" \
    -b "$COOKIE_B" \
    -d "$TASK_BODY" "$BASE/api/tasks")
[ "$HTTP" = "201" ] || fail "创建任务失败 HTTP $HTTP: $(cat $JSON_DIR/task.json)"

TASK_ID=$(extract_value "$JSON_DIR/task.json" id)
TASK_VER=$(extract_value "$JSON_DIR/task.json" version)
[ -n "$TASK_ID" ] && [ -n "$TASK_VER" ] || fail "无法解析 task id/version: $(cat $JSON_DIR/task.json)"
ok "创建任务 id=$TASK_ID version=$TASK_VER"

# 列出
HTTP=$(curl -s -o "$JSON_DIR/list.json" -w "%{http_code}" -b "$COOKIE_B" "$BASE/api/tasks")
[ "$HTTP" = "200" ] || fail "列出任务 HTTP $HTTP"
LIST_COUNT=$(grep -o "\"id\":" "$JSON_DIR/list.json" | wc -l)
[ "$LIST_COUNT" -ge 1 ] || fail "listMine 数量 0"
ok "listMine 返回 $LIST_COUNT 条"

# 保存
SAVE_BODY="{\"expectedVersion\":$TASK_VER,\"definition\":{\"schemaVersion\":1,\"mode\":\"SINGLE_PAGE\",\"startUrl\":\"https://example.com\",\"viewport\":{\"width\":1280,\"height\":720},\"fields\":[]}}"
HTTP=$(curl -s -o "$JSON_DIR/save.json" -w "%{http_code}" \
    -X PUT -H 'Content-Type: application/json' \
    -H "X-XSRF-TOKEN: $XSRF_B" \
    -b "$COOKIE_B" \
    -d "$SAVE_BODY" "$BASE/api/tasks/$TASK_ID")
[ "$HTTP" = "200" ] || fail "保存失败 HTTP $HTTP: $(cat $JSON_DIR/save.json)"
NEW_VER=$(extract_value "$JSON_DIR/save.json" version)
[ "$NEW_VER" -gt "$TASK_VER" ] || fail "保存后 version 未递增 ($TASK_VER → $NEW_VER)"
ok "保存成功 version: $TASK_VER → $NEW_VER"

# 6. 第二个 collector 看任务 → 403
step 6 '跨用户访问应 403'
OTHER="other_$$"
HTTP=$(curl -s -o "$JSON_DIR/other_create.json" -w "%{http_code}" \
    -X POST -H 'Content-Type: application/json' \
    -H "X-XSRF-TOKEN: $XSRF_A" \
    -b "$COOKIE_A" \
    -d "{\"username\":\"$OTHER\",\"password\":\"other_pwd_12+\",\"role\":\"COLLECTOR\"}" \
    "$BASE/api/admin/users")
[ "$HTTP" = "201" ] || fail "创建 other 失败 HTTP $HTTP"
HTTP=$(curl -s -o /dev/null -w "%{http_code}" \
    -X POST -H 'Content-Type: application/json' \
    -c "$COOKIE_C" \
    -d "{\"username\":\"$OTHER\",\"password\":\"other_pwd_12+\"}" \
    "$BASE/api/auth/login")
[ "$HTTP" = "200" ] || fail "other 登录失败 HTTP $HTTP"
HTTP=$(curl -s -o /dev/null -w "%{http_code}" -b "$COOKIE_C" "$BASE/api/tasks/$TASK_ID")
[ "$HTTP" = "403" ] || fail "跨用户访问应 403 实际 $HTTP"
ok "跨用户访问被拒（403）"

# 7. 旧版本保存 → 409
step 7 '乐观锁：旧版本保存应 409'
HTTP=$(curl -s -o "$JSON_DIR/stale.json" -w "%{http_code}" \
    -X PUT -H 'Content-Type: application/json' \
    -H "X-XSRF-TOKEN: $XSRF_B" \
    -b "$COOKIE_B" \
    -d "$SAVE_BODY" "$BASE/api/tasks/$TASK_ID")
[ "$HTTP" = "409" ] || fail "旧版本保存应 409 实际 $HTTP: $(cat $JSON_DIR/stale.json)"
ok "乐观锁冲突正确返回 409"

# 8. 删除任务
step 8 '删除任务 → listMine 不再列出'
HTTP=$(curl -s -o /dev/null -w "%{http_code}" \
    -X DELETE -H "X-XSRF-TOKEN: $XSRF_B" \
    -b "$COOKIE_B" "$BASE/api/tasks/$TASK_ID")
[ "$HTTP" = "204" ] || fail "删除 HTTP $HTTP"
HTTP=$(curl -s -o "$JSON_DIR/final.json" -w "%{http_code}" -b "$COOKIE_B" "$BASE/api/tasks")
[ "$HTTP" = "200" ] || fail "删除后列表 HTTP $HTTP"
if grep -q "\"id\":$TASK_ID" "$JSON_DIR/final.json"; then
    fail "任务仍在 listMine"
fi
ok "删除成功，listMine 不再列出"

rm -rf "$COOKIE_A" "$COOKIE_B" "$COOKIE_C" "$JSON_DIR"
printf "\n\033[1;32m[OK] smoke 8 步全部通过\033[0m\n"
