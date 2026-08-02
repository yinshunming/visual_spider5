#!/usr/bin/env bash
# m4-smoke.sh — M4 列表识别端到端 smoke shell 版。
# 与 m4-smoke.ps1 步骤一一对应；占位，具体命令延后到 M4-7 acceptance。

set -eu

FIXTURE_DIR="$(cd "$(dirname "$0")/../.." && pwd)/src/test/resources/list"

echo "[M4-smoke step 1] fixture HTTP server expected at \$FIXTURE_DIR ($FIXTURE_DIR)"
echo "[M4-smoke step 2] Start JAR (依赖 M3 smoke)"
echo "[M4-smoke step 3] admin login + create collector"
echo "[M4-smoke step 4] collector build list task -> READY"
echo "[M4-smoke step 5] POST /api/runs -> 202 WAITING"
echo "[M4-smoke step 6] WebSocket progress -> SUCCESS"
echo "[M4-smoke step 7] GET /api/runs/{id} -> raw/dedup/final/fail"
echo "[M4-smoke step 8] with-duplicates fixture -> dedup > 0"
echo "[M4-smoke step 9] partial-fail fixture -> PARTIAL_SUCCESS"
echo "[M4-smoke step 10] cancel path -> CANCELLED"

echo "Linux smoke 标注: not executed in M4; see M7."
echo "M4 smoke 占位完成。"
