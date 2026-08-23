#!/usr/bin/env bash
# M6 smoke (Linux) — 占位 + Linux 标注（spec §T3 / docs/specs/m6.md §Further Notes）
#
# 本文件不在 M6 执行；按 spec 决策门，跨平台真实验收推迟到 M7。
# 见 m6-smoke.ps1 的对应 Windows/pwsh 实现。
#
# 完整步骤占位（M6-2 ~ M6-6 全部工单落地后补齐）：
#   1) 启动 JAR（prod 配置，无 loopback 豁免）+ fixture server + PG
#   2) 再起第二个 JAR（同库）-> 断言启动失败退出且日志含指引（M6-2 已落）
#   3) admin 登录 + 建任务(startUrl=127.0.0.1 fixture)-> 断言保存/就绪被拒
#   4) collector 建 loopback fixture 任务在豁免 dev 配置下的对照（可选）
#   5) WS: 超尺寸命令、二进制帧各断连一次；正常命令链路可用
#   6) 强杀 Chromium -> 下一命令成功 + health 恢复
#   7) 3 并行小运行 + 期间 REST 探活
#   8) 改 retention.days -> 手动触发清理 -> 断言删除
#   9) 日志尾部扫描哨兵字符串
#  10) 收尾进程数核对
#  11) Linux 标注输出

echo "M6 smoke (Linux) not executed in M6; see M7" >&2
exit 1
