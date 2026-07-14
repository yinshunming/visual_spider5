# 项目 Agent 规则

## 权威文档与查阅路由

`AGENTS.md` 只保存高频、稳定且会影响实现决策的约束，不能替代以下原文。开始相关工作前必须查阅对应文档：

| 文档 | 权威范围 | 必须查阅的场景 |
| --- | --- | --- |
| [`docs/product-spec.md`](docs/product-spec.md) | 首版用户、功能范围、容量、安全边界与验收标准 | 修改产品行为、权限、采集能力、状态、结果、限制或发布验收 |
| [`docs/architecture.md`](docs/architecture.md) | 技术栈、模块 seam、线程/浏览器模型、数据、安全、协议、测试与运维约束 | 设计或修改代码结构、接口、并发、数据库、Playwright、API/WebSocket、部署 |
| [`docs/roadmap.md`](docs/roadmap.md) | M0–M7 依赖顺序、每阶段范围/不做项、退出标准与 AI 工单方式 | 创建 issue、拆任务、选择当前实现范围、判断里程碑或发布是否完成 |

领域术语以根目录 [`CONTEXT.md`](CONTEXT.md) 为准；已接受的架构决策以 [`docs/adr/`](docs/adr/) 为准。三份主文档分别回答“做什么”“怎样做”“何时做”，不要用本文件的摘要替代原文细节。发现文档之间、文档与代码之间不一致时，必须指出，不能静默选择或把猜测当成现行决策。

## 首版硬约束

### 产品与交付边界

- 首版仅面向小团队私有部署，目标容量约 10 个账号；客户端零安装，只通过 Web 使用服务端远程 Chromium，不使用 `iframe`、桌面客户端或浏览器扩展。
- 采集模式只有单页采集和列表采集。列表导航只支持“下一页”“加载更多”和从明确 URL 进入一层内容页；结果记录仅为扁平单值字段。
- 不得顺手加入首版外能力，包括登录态采集、验证码/反检测、代理、纯无限滚动、任意脚本、多级内容页、数组/嵌套结果、定时调度、断点续跑、跨运行增量、公共 SaaS 或协同编辑。范围变化必须先更新产品规格；改变安全或系统边界时还需 ADR。
- M0–M7 必须按依赖顺序推进，不跨里程碑堆叠未验证功能。M0–M5 只能在开发环境和受控 fixture 上验证；完成 M6 安全可靠性加固并通过 M7 跨平台验收后，才能给真实采集人员使用。
- 每次实现前先从 issue 和 `docs/roadmap.md` 确认当前里程碑、成功标准与明确不做项；不得把整个里程碑作为一个无人监督的大任务。

### 技术栈、部署与模块边界

- 首版固定为 Java + Spring Boot、Vue 3 + TypeScript + Vite、Playwright for Java + Chromium、PostgreSQL；使用 Maven Wrapper，由根构建先构建前端，再由 Spring Boot 提供静态资源，最终交付一个可执行 JAR。
- PostgreSQL 是唯一外部基础设施；不引入 Docker、Nginx、Redis、消息队列、Elasticsearch、对象存储或独立 Worker。应用仅支持单实例运行，禁止两个 JAR 同时连接同一业务数据库参与调度。
- 代码按 `identity`、`task`、`visualbrowser`、`extraction`、`run`、`result` 等业务能力组织深模块，不创建全局 `controller/service/repository` 横向目录。Controller 只做身份/输入校验和响应转换，再调用模块 interface。
- 模块通过少量稳定 interface 隐藏实现复杂度；不要为每个类机械创建 port。Playwright 对象、线程约束、数据库细节和执行状态不得泄漏到模块 interface；`shared` 只放稳定标识符、时钟和小型错误类型。
- 其他模块接收已认证的 `ActorId`，不自行读取 `SecurityContext`。预览和正式运行必须复用同一套提取实现；任务校验不能被绕过，运行只能使用启动时固化的不可变任务快照。

### 浏览器、并发与状态

- Playwright Java 对象只能在创建它们的固定平台线程上使用。每个浏览器 lane 拥有固定线程和有界命令队列；配置会话或采集运行在生命周期内绑定 lane，Web/WebSocket 线程不得直接调用 `Page`。
- 每个配置会话和每次采集运行都使用独立、非持久化的 `BrowserContext`，二者不共享 Cookie、Local Storage、Session Storage 或缓存；远程视口固定为 `1280 × 720`。
- 帧通道只保留最新待发送帧，慢客户端时丢弃旧帧，禁止无界排队；画面帧/截图只存内存，不写数据库或磁盘。元素规则每次重新查询 DOM，不长期保存 `ElementHandle`。
- PostgreSQL 保存采集运行的权威状态，JVM 内只用有界执行器调度。应用启动时把遗留的“等待/运行中”记录标记为“已中断”；已持久化结果仍可查询和导出，不做自动续跑。
- 正式运行不传输实时浏览器画面。取消必须是可回收资源的协作式信号，每个循环都检查取消、页面数、记录数、时长和关闭信号。

### 固定容量与默认值

- 每名用户最多 1 个配置会话；无操作 15 分钟关闭，单次最长 2 小时。
- 全系统最多并行 3 个采集运行，每名用户最多并行 1 个；单次运行最多 200 个页面、10,000 条结果、30 分钟；同一目标域名并发为 1，请求间隔至少 1 秒。
- 页面先等 `DOMContentLoaded`，再等必要元素，默认超时 15 秒；额外等待只能为 0–5 秒。单页失败重试 2 次，总尝试最多 3 次。
- 列表预览最多 20 条记录，内容页预览最多访问前 3 条；运行、结果和运行日志默认保留 30 天。
- 达到限制必须停止并记录明确原因；遇到 `429`、持续 `403` 或验证码时退避并停止，不切换 UA、指纹或代理规避。首版明确不读取或执行目标站点 `robots.txt`，也不增加运行前权限确认步骤。

### 数据、权限与安全

- 所有 schema 变更由 Flyway migration 管理；任务 JSON 和 REST DTO 显式版本化，任务定义包含 `schemaVersion`；前端 TypeScript 类型由 OpenAPI 生成或在构建中校验。migration 必须说明历史数据、索引、锁影响以及回滚或前滚恢复策略。
- PostgreSQL 特性必须用真实 PostgreSQL 集成测试验证，不得用 H2 模拟 JSONB、索引、事务或锁语义。结果以按运行关联的 JSONB 行保存，只能服务器分页查询或流式导出，禁止返回无界数组、一次加载全部结果或在磁盘生成完整临时导出文件。
- 管理员可全局访问；采集人员只能访问自己的任务、运行与结果。所有查询必须携带所有者约束，不能只依赖前端隐藏。WebSocket 握手及每条命令都要验证身份和会话所有者。
- `visualbrowser` 与 `run` 必须共用同一个目标 URL 策略：仅允许公开 `http://`/`https://`，校验入口、全部 DNS 结果、重定向、最终 URL 和页面子资源，拒绝回环、私有、链路本地、保留及云元数据地址，并防护 DNS 重绑定。
- 认证使用 Spring Security 服务端 `HttpSession`、BCrypt、CSRF、`HttpOnly` 和合适的 `SameSite` Cookie；不使用 JWT 或浏览器 `localStorage`。首版不内置 HTTPS，只能部署在可信 LAN/VPN，禁止直接暴露公网。
- 技术日志不得记录密码、Cookie、完整页面内容或结果字段值；前端错误不得暴露 Java 堆栈。

### 验证与未决事项

- 每个里程碑只有在退出标准有可复现证据、适用的 Java/前端测试、lint、typecheck 和构建通过、文档同步后才算完成。真实浏览器关键差异必须通过仓库内本地 fixture 的 Playwright 集成测试覆盖，并保持每测试独立 `BrowserContext` 和线程亲和性。
- Windows 与 Linux 至少各验证安装、启动、单页采集和资源回收主链路；本地缺少 PostgreSQL/Chromium 时要明确列出未运行的验证，不能静默跳过后宣称全部通过。
- 以下是 M0/M1 的决策门，不得提前当作既定事实：`Page.screencast()` 与 `Page.screenshot()` 回退选择、lane 内浏览器复用策略、配置会话全局上限、最低服务器规格，以及具体 JDK/Spring Boot/Vue/Playwright 版本。
- Issue 默认只交付一个可观察结果、只改一个业务模块；跨模块时必须说明 seam 和集成原因。数据库 migration 与消费代码放在同一 issue，或显式建立依赖。Bug 按“复现 → 回归测试 → 最小修复 → 验证”闭环。

## Agent skills

### Issue tracker

Issues are tracked as GitHub issues (via the `gh` CLI). See `docs/agents/issue-tracker.md`.

### Triage labels

Five canonical triage roles, label string equal to role name: `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: one `CONTEXT.md` + `docs/adr/` at the repo root. See `docs/agents/domain.md`.
