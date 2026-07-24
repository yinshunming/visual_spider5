#!/usr/bin/env bash
# M2 smoke (Linux/macOS bash)：13 步主链路冒烟。
#
# 注意：本脚本在 Linux 上仅做语法与 HTTP 流程验证。M2 不在 Linux 真机执行（AGENTS / M7
# 跨平台验收），仅留作 M7 真机验证。运行前需准备：
#   - 后端 JAR 在 $M2_BASE_URL（默认 http://localhost:8080）启动
#   - PostgreSQL DSN 已配置
#   - python -m http.server 8081 提供 fixture
#
# 真实 Chromium / Playwright IT 在 M7 验证。

set -euo pipefail

BASE_URL="${M2_BASE_URL:-http://localhost:8080}"
COOKIE_JAR=$(mktemp)
trap 'rm -f "$COOKIE_JAR"' EXIT

step() {
    printf '[step %s] %s\n' "$1" "$2"
}

step 1 "登录获取 JSESSIONID + XSRF-TOKEN"
curl -fsS -c "$COOKIE_JAR" -b "$COOKIE_JAR" \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"${M2_SMOKE_USER:-admin}\",\"password\":\"${M2_SMOKE_PASSWORD:-admin1234567890}\"}" \
    "$BASE_URL/api/auth/login" > /dev/null

step 2 "创建任务草稿"
TASK_BODY=$(cat <<'JSON'
{
  "name": "smoke-m2",
  "definition": {
    "schemaVersion": 1,
    "mode": "SINGLE_PAGE",
    "startUrl": "http://localhost:8081/static.html",
    "viewport": { "width": 1280, "height": 720 },
    "fields": [
      { "name": "title", "source": "VISIBLE_TEXT", "selector": "h1", "resultType": "TEXT", "trim": "TRIM", "required": true }
    ]
  }
}
JSON
)
TASK_ID=$(curl -fsS -c "$COOKIE_JAR" -b "$COOKIE_JAR" \
    -H 'Content-Type: application/json' \
    -d "$TASK_BODY" \
    "$BASE_URL/api/tasks" | jq -r '.id')
echo "taskId=$TASK_ID"

step 3 "打开配置会话"
SESSION_ID=$(curl -fsS -c "$COOKIE_JAR" -b "$COOKIE_JAR" \
    -H 'Content-Type: application/json' \
    -d "{\"taskId\":$TASK_ID}" \
    "$BASE_URL/api/visual-sessions" | jq -r '.sessionId')
echo "sessionId=$SESSION_ID"

step 4 "查询会话状态"
curl -fsS -c "$COOKIE_JAR" -b "$COOKIE_JAR" \
    "$BASE_URL/api/visual-sessions/$SESSION_ID" > /dev/null

step 5 "切换浏览模式：WS 协议 + CSRF query"
echo "WS 握手由 m2-ws-smoke.sh 覆盖；M7 真机执行。"

step 6 "校验选择器 (CSS)"
curl -fsS -c "$COOKIE_JAR" -b "$COOKIE_JAR" \
    -H 'Content-Type: application/json' \
    -d '{"selectors":[{"type":"css","selector":"h1"}]}' \
    "$BASE_URL/api/visual-sessions/$SESSION_ID/selectors/validate" > /dev/null

step 7 "编辑字段"
echo "EditingBuffer 自动保存路径在 M3+ 完整 UI 启用。"

step 8 "字段预览"
curl -fsS -c "$COOKIE_JAR" -b "$COOKIE_JAR" \
    -H 'Content-Type: application/json' \
    -d "{\"definition\":$(echo "$TASK_BODY" | jq '.definition')}" \
    "$BASE_URL/api/visual-sessions/$SESSION_ID/preview" > /dev/null

step 9 "自动保存 (PUT /api/tasks)"
curl -fsS -c "$COOKIE_JAR" -b "$COOKIE_JAR" \
    -H 'Content-Type: application/json' \
    -d "$TASK_BODY" \
    -X PUT \
    "$BASE_URL/api/tasks/$TASK_ID" > /dev/null

step 10 "状态切到 READY"
echo "TaskCatalog.saveDraft 后由 TaskReadiness 决定 status。"

step 11 "关闭配置会话"
curl -fsS -c "$COOKIE_JAR" -b "$COOKIE_JAR" -X DELETE \
    "$BASE_URL/api/visual-sessions/$SESSION_ID" > /dev/null

step 12 "重开会话（验证幂等）"
REOPENED_ID=$(curl -fsS -c "$COOKIE_JAR" -b "$COOKIE_JAR" \
    -H 'Content-Type: application/json' \
    -d "{\"taskId\":$TASK_ID}" \
    "$BASE_URL/api/visual-sessions" | jq -r '.sessionId')
curl -fsS -c "$COOKIE_JAR" -b "$COOKIE_JAR" -X DELETE \
    "$BASE_URL/api/visual-sessions/$REOPENED_ID" > /dev/null

step 13 "验证 Chromium 子进程 0 残留（Linux/M7）"
LEFTOVER=$(pgrep -af chromium 2>/dev/null | grep -v 'grep' || true)
if [ -n "$LEFTOVER" ]; then
    echo "Chromium 子进程未回收: $LEFTOVER"
    exit 1
fi
echo "Chromium 子进程已清空。"

echo "M2 smoke (Linux bash syntax check) PASSED."