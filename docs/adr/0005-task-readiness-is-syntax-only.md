# 任务 READY 状态以语法就绪为准

> 状态：Accepted（2026-07-21 M2 grilling 决策 5）
> 范围：`com.visualspider.task.spi.TaskReadiness` 与 `collection_task.status` 字段语义

## 背景

M1 已定义 `TaskReadiness.validateForRun()` 接口，但实现只覆盖最小语法集（`startUrl` 是合法
http(s)、`viewport` 是 1280×720、字段名非空唯一、`schemaVersion` 是 1）。roadmap §6 要求
M2 引入"完整校验和『可运行』状态"，但未明确可运行是否需要"当前页面能取到值"。M0 spike 已
有 `VisualSession` 与实时页面 DOM，两种语义都能实现，需要决策以确定 M2 退出标准。

## 决策

`collection_task.status = READY` 仅表达**语法就绪**：定义文件通过结构校验、可被运行引擎消
化；运行时检查（选择器在当前页面匹配 0 / 多 / N 个元素、正则捕获是否成功、类型转换是否成
功、URL 是否合法可达）一律作为 `extraction.ExtractionDiagnostic` 返回给前端展示，不影响
`status` 字段。

具体地，`TaskReadiness.validateForRun()` 在 M2 实现：

- `startUrl`：`URI.create` 后 `getScheme` ∈ `{http, https}`、主机名非空、语法合法。
- `fields`：`name` 非空、`name` 唯一（不区分大小写）、`selector` 语法合法（CSS 通过
  `org.jsoup.select.Selector` 解析；XPath 通过 JDK 内置 `XPathFactory` 编译）。
- 至少 1 个字段。
- `viewport == 1280×720`（M2 锁定）。

不通过任一项 → 返回 `ReadinessReport.draft(errors)`，`status` 维持 `DRAFT`；通过 →
`status = READY`。

## 备选

- **A. 运行时检查型（采纳的反面）**：`status = READY` 要求所有字段在当前 session 页面至少匹
  配 1 个元素。代价：用户从 A 页调试到 B 页时状态在 `READY` / `DRAFT` 之间反复跳变；session
  关闭后"READY"记录不携带运行时证据。不可持续。
- **B. 双状态 `READY` + `RUNNABLE`**：增加第三个状态字段表达"当前页面也能跑"。代价：
  `READY` 与 `RUNNABLE` 两个独立字段、UI 三态、状态机复杂度加倍；预览时计算的运行结果已
  包含同样信息，独立字段冗余。
- **C. 语法就绪型（采纳）**：见决策。

## 后果

- `collection_task.status` 在保存时计算并落库；session 内编辑不再触发 `status` 变更，避免
  频繁写。session 关闭前最后一次保存才重新计算。
- 运行时诊断（0 匹配 / 多匹配 / 正则失败 / 类型失败 / URL 不合法）通过 `extraction` 模块
  返回，UI 在字段旁展示；M3 启动运行后，运行引擎同样基于这套诊断生成 `run_event`。
- 修改 `startUrl` 或使任一字段 `selector` 语法失效 → 保存时 `validateForRun` 返回错误 →
  `status` 回退到 `DRAFT`（与 roadmap §6 "修改入口 URL 或使规则失效会把任务恢复为草稿" 一
  致）。
- `TaskSnapshotFactory`（M3 启用）从 `status = READY` 的任务固化快照；如果用户在没有
  `READY` 时强行启动运行（M3 不暴露此入口，但接口预留），返回 `TASK_NOT_READY`。
- `TaskReadinessTest` 与 `TaskReadinessIT` 必须覆盖：合法定义 → `READY`；任一规则失败
  → `DRAFT` + 明确错误码（`TASK_INVALID_URL` / `TASK_DUPLICATE_FIELD` / `TASK_INVALID_SELECTOR`）。
- ATTRIBUTE 字段缺失 attribute、正则 capture group 为空、`NUMBER` 类型转换失败等运行时检
  查**不**进入 `validateForRun`；它们通过 `ExtractionDiagnostic` 在 session 内或预览时返回。
- 与 M1 契约：`TaskReadiness.validateForRun(taskId, actor)` 接口形状不变；M1 抛
  `UnsupportedOperationException("M3 启用")` 的 `TaskSnapshotFactory` 仍然占位。

## 验证

- 单元：`TaskReadinessTest` 用本地 fixture 的 `TaskDefinition` 覆盖所有失败路径与成功路径。
- 集成：`TaskReadinessIT`（pg-it）保存草稿后 `status = DRAFT`；修正定义再保存 →
  `status = READY`；改坏 selector 再保存 → 回到 `DRAFT`。
- 端到端：M2 smoke 中验证用户在 session 内编辑 → 关闭 session → 任务 `status` 与最近一次
  保存一致；session 内未保存的编辑**不**影响 `status`。