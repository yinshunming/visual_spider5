#!/usr/bin/env bash
# Visual Spider 5 — Linux smoke 占位（M1-5 spec T3）
# Linux smoke: not executed in M1; see M7
# M1-5 仅要求：脚本可被 bash 解析，依赖 curl/jq/python3 时显式回退提示

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
cd "$PROJECT_ROOT"

echo "[INFO] Linux smoke 不在 M1 真机执行；见 M7 spec。"
echo "[INFO] 本脚本只做语法检查 + 环境探针。"

# 检查必备工具
for cmd in curl jq; do
    if ! command -v "$cmd" >/dev/null 2>&1; then
        echo "[FAIL] $cmd 未安装；Linux 后续 milestone 真机跑 smoke 时需先准备" >&2
        exit 1
    fi
done

# 检查环境变量
: "${VISUALSPIDER_ADMIN_USERNAME:?VISUALSPIDER_ADMIN_USERNAME 未设置}"
: "${VISUALSPIDER_ADMIN_PASSWORD:?VISUALSPIDER_ADMIN_PASSWORD 未设置}"

echo "[OK] 环境探针通过；真机 smoke 待 M7 启用。"
