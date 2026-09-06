# Release Notes — v0.1.0（首版）

> 首版（`v0.1.0`）发布说明。覆盖范围、验收证据、已知限制、升级指引。
> 本文档配合 [`configuration.md`](./configuration.md)、[`windows.md`](./windows.md)、[`linux.md`](./linux.md)、[`backup-restore-upgrade.md`](./backup-restore-upgrade.md)。

## 1. 验收证据映射表（product-spec §13）

`product-spec.md` §13 共 11 条（m7.md D8 草稿数字"13 条"为口径误差；以 spec 实际为准）。下表逐项映射"证据类型 + 证据位置"。

| # | 验收项 | 证据类型 | 证据位置 |
| - | - | - | - |
| 1 | 管理员可以创建账号，采集人员之间的数据不可互相访问 | 自动化 IT | `src/test/java/com/visualspider/identity/**/*IT.java`（AdminUserControllerTest、IT 跨用户 403） + smoke.ps1 step 6 + m7-acceptance step 12 |
| 2 | 单页采集完整链路可在 Windows 和 Linux 上运行 | 自动化 IT + smoke | `src/test/java/com/visualspider/run/**/*IT.java`（SinglePageRunExecutorIT） + `scripts/e2e/m7-acceptance.ps1` step 7 + `scripts/e2e/m7-acceptance.sh` step 7（**Linux 由用户在 Ubuntu VM 跑通后贴证据**） |
| 3 | 列表采集能识别重复项、翻页/加载更多并进入一层内容页 | 自动化 IT | `src/test/java/com/visualspider/extraction/list/**/*IT.java` + `run/MultiPageRunExecutorIT.java` + m7-acceptance step 8/9 |
| 4 | 系统生成与手写 CSS/XPath 均能预览、校验和高亮 | 自动化 IT | `src/test/java/com/visualspider/extraction/selector/**/*Test.java` + selector validate/preview 端点 IT |
| 5 | 正式运行能够排队、取消、重试、部分成功和处理中断 | 自动化 IT + smoke | RunDispatcherIT（限流/排队）+ CancelRunIT + RetryExhaustionIT + InterruptOnRestartIT + m7-acceptance step 10 |
| 6 | 运行使用任务快照，编辑当前任务不改变历史结果含义 | 自动化 IT | `RunSnapshotTest.java` + `TaskSnapshotTest.java`（V3+ schemaVersion bump；编辑不影响历史 run 关联 definition） |
| 7 | 结果能够分页、运行内去重并流式导出 CSV/JSON | 自动化 IT | ResultPaginationIT + SinkDedupIT + ExportStreamingIT + m7-acceptance step 11 |
| 8 | 并发、页面数、记录数、时长、会话和保留期限限制生效 | 自动化 IT | `RunLimitsIT.java`（M6-5）+ SystemSettingRetentionIT + DispatcherConcurrencyIT + `pg-stress` profile 压测 IT |
| 9 | SSRF 防护能够拒绝直接、重定向和 DNS 变化后的内网目标 | 自动化 IT | `src/test/java/com/visualspider/visualbrowser/ssrf/**/*Test.java` + IPv4/IPv6/混合表示 fixture + m6-smoke.ps1 SSRF 负向 |
| 10 | 应用重启后遗留运行标记为已中断，已写入结果仍可访问 | 自动化 IT | `RunStartupSweepIT.java`（启动钩子扫 WAITING/RUNNING 标 INTERRUPTED；已持久化结果可读可导） |
| 11 | 部署文档明确 HTTP 使用限制、安装步骤、日志位置和升级方式 | 文档 | `docs/deploy/windows.md`（14 节）、`docs/deploy/linux.md`（15 节）、`docs/deploy/configuration.md`（9 节）、`docs/deploy/backup-restore-upgrade.md`（7 节） + README 部署入口 |

**手工演练待补**（用户在 VM 跑完贴证据到 issue 或本文档 §6）：

- M7-2 #54：Ubuntu Server 24.04 LTS VM 全新安装 → 登录 → 单页采集冒烟
- M7-3 #55：成功路径（备份 → 停 → 换 JAR → Flyway 前滚 → 冒烟）+ 失败路径（人为制造前滚失败 → 恢复备份 → 旧 JAR 启动）
- M7-4 #56：双平台 m7-acceptance 真实执行（Linux 由用户在 Ubuntu VM 跑）
- M7-5 #57（见本文档 §4）：真机 LAN 延迟采样 20+20 次

## 2. 功能摘要（按模块）

| 模块 | 主要能力 |
| - | - |
| identity | admin seed / 用户管理 / BCrypt 哈希 / HttpOnly+SameSite Cookie / CSRF |
| task | V4 schemaVersion / 单页 + 列表两种模式 / 乐观锁 / 任务快照（不可变） |
| visualbrowser | Playwright for Java 1.61.0 / 非持久化 BrowserContext / 1280×720 / SSRF 防护（DNS 解析 + 重定向 + 子资源）/ 远程帧 WebSocket 通道 |
| extraction | 列表项 DOM 层级推断 + 评分 / CSS + XPath / sink 实时去重 / SHA-256 + canonical / 字段合并（列表项 + 内容页） |
| run | RunDispatcher（M3-1 路由）/ 3 lane 有界执行器 / RunLimits SPI（200 页 / 10k 条 / 30min / 同域 1 并 1s 间隔）/ retry 2 次 / cancel 协作信号 / 启动时遗留 run 扫 INTERRUPTED |
| result | JSONB 行 / 服务端分页 / 流式 CSV/JSON 导出 / retention.days（M6-6 入 system_setting） |
| 配置会话 | 每用户 1 / 15min idle 关 / 2h 上限 / 预览 20 条 + 内容页前 3 条 / WS 鉴权握手 + 每命令校验 |

## 3. 已知限制

### 3.1 部署形态边界

| 限制 | 说明 | 解决路径 |
| - | - | - |
| **HTTP 明文** | 首版无 HTTPS / TLS。Cookie、密码、页面内容在 LAN 内明文传输 | 必须部署在可信 LAN / VPN；首版后候选（roadmap §12）反向代理终止 TLS |
| **单实例** | `SingleInstanceGuard` 通过 PostgreSQL advisory lock（M6-2 / [ADR-0007](../adr/0007-single-instance-advisory-lock.md)） | 同业务库禁止两 JAR 并存 |
| **无 Docker / Nginx / Redis / 队列 / ES / 对象存储** | 首版明确不引入（[product-spec](../product-spec.md) §12） | 后续里程碑候选 |
| **Windows 无开机自启** | `scripts/windows/` 不注册服务；脚本 + PID 文件 + `/actuator/health` | 后续可改为 NSSM / Windows Service Wrapper |
| **Linux 依赖 Ubuntu 24.04 LTS** | systemd unit、apt 源、useradd 都按 Ubuntu 24.04 验证 | Debian 12 大概率可用但不承诺、不验收 |

### 3.2 安全边界

| 限制 | 详细文档 |
| - | - |
| **SSRF 残余风险** | 公网目标经 DNS 解析 + 重定向 + 子资源三层校验；DNS 重绑定的 0 时窗仍残余理论风险。详见 [`docs/security/ssrf-residual-risk.md`](../security/ssrf-residual-risk.md)（M6-1） |
| **不读取 robots.txt** | product-spec §10 明确不读取；部署者承担目标选择与数据用途责任 |
| **不支持登录态采集** | 不支持 Cookie 复用 / 验证码 / 代理池 / 反检测；product-spec §10/§12 明确不做 |
| **遇到 429/403/captcha 退避并停止** | PacingPolicy + PageStopDetector（M5-5）；不切换 UA / 指纹 / 代理规避 |

### 3.3 验收前置依赖

- **Python 3** 是 `m7-acceptance.sh` 的前置依赖（用于 `python -m http.server` 起本地 fixture）。**非运行时依赖**——生产部署无需 Python；仅验收脚本需要。

### 3.4 性能延后项

- **真机 LAN 点击/滚动帧 RTT 中位数 ≤ 500ms**：M0 延后验收项；用户在物理 LAN 第二台 PC 浏览器 console 跑 20+20 次采样（脚本草稿见 [`lan-latency-sample.txt`](./lan-latency-sample.txt)）。未达标按性能 bug 开 issue 评估。

## 4. 演练发现的非阻断遗留

> 由 M7-2 ~ M7-4 演练暴露、已在 m7.md D11 "阻断判定标准" 之外的项。

| 项 | 详情 | 状态 |
| - | - | - |
| Linux 上 `psql` 不在 PATH 时 `install.sh` 不阻断 | 仅 WARN；`check-env.sh` 阻断 | 文档已说明 |
| systemd `Restart=on-failure` 不重启 OOM-killed | OOM 视同非失败 | linux.md §15 已声明 |
| Loopback 豁免 `--visualbrowser.target-url.allow-loopback=true` 在脚本注释中已标注"仅测试"；但缺运行时启动参数校验（如运行 prod 配置却误开） | 启动不会失败，仅行为偏离预期 | 后续可加 startup validator |
| `mvn package` 默认产物名含 SNAPSHOT；M7-6 定版前脚本/文档全部硬编码 `0.0.1-SNAPSHOT.jar` | M7-6 一并参数化清理（m7.md D9） | M7-6 关闭 |

## 5. 升级指引

见 [`backup-restore-upgrade.md`](./backup-restore-upgrade.md)。要点：

1. **升级前必备份**：`pg_dump -Fc > before-upgrade_$(date +%F_%H%M).dump`。
2. **停止应用**：`systemctl stop visual-spider`（Linux）/ `stop.ps1`（Windows）。
3. **替换 JAR**：拷贝到 `/opt/visual-spider/app.jar` 或 `C:\visual-spider\app.jar`。
4. **启动**：Flyway 自动前滚。
5. **冒烟**：`curl /actuator/health` + admin 登录 + 单页采集。
6. **失败回滚**：见 backup-restore-upgrade.md §4。

## 6. 发布包核查

> 由 M7-5 (`/code-review` + git grep) 执行。零业务代码改动，发布包只含 ops 脚本、文档、pom 版本号变更。

### 6.1 git grep 哨兵式扫描（无密钥/开发凭据/测试用户密码）

扫描命令（占位示例，需在 tag 前最终一次执行）：

```bash
# 1. 默认凭据检查（仅允许 application-dev.yml / smoke fixture 的占位）
git grep -nE 'password.*:.*(?!visualspider)' src/ 2>/dev/null | grep -v -E '^\s*\*|//|#' || true

# 2. 文档示例凭据必须是占位符（<STRONG_PASSWORD> / <...12+> 等）
git grep -nE '\bpassword\s*=\s*"[^"<]' docs/deploy/ 2>/dev/null || true

# 3. JAR / 配置文件无真实凭据
git grep -nE 'AKEA[0-9A-Z]{16}|AKIA[0-9A-Z]{16}|ghp_[A-Za-z0-9]{20,}|sk-[A-Za-z0-9]{20,}' 2>/dev/null || true
```

**预期结果**：第 3 项零命中；第 1、2 项仅命中 dev 占位（`application.yml` 默认值 `visualspider`、`application-dev.yml` 的 `change-me-please-12+`、`application-it.yml` 的 `${pg.it.password}` 占位）—— 三处均带 dev-only 标注，production 必须由环境变量覆盖（见 `configuration.md` §1）。

### 6.2 文档示例凭据约定

`docs/deploy/configuration.md` §1 与 windows.md/linux.md 的 `.env` 示例一律使用占位符：

- `<STRONG_PASSWORD>` — DB 密码
- `<STRONG_ADMIN_PASSWORD_AT_LEAST_12_CHARS>` — admin seed 密码
- `change-me-please-12+` — dev profile 默认（仅 `application-dev.yml` 内）
- `dev-admin` — dev profile 默认 admin 用户名（仅 `application-dev.yml` 内）

演练产生的真实凭据**不进入**版本控制（CLAUDE.md §2 / m7.md D11）。

### 6.3 产物清单（M7-6 标签前）

- `visual-spider5-0.1.0.jar` — 主可执行 JAR（pom M7-6 改版本后）
- `docs/deploy/` 五篇：windows.md / linux.md / configuration.md / backup-restore-upgrade.md / release-notes-v0.1.0.md
- `scripts/windows/` 6 个生产脚本
- `scripts/linux/` 6 个生产脚本 + visual-spider.service
- `scripts/e2e/m7-acceptance.ps1` / `m7-acceptance.sh`
- `docs/deploy/lan-latency-sample.txt`
- README 部署入口节指向 `docs/deploy/`

## 7. 进一步参考

- [`architecture.md`](../architecture.md) — 技术栈、模块边界
- [`roadmap.md`](../roadmap.md) §11 M7 退出标准
- [`m7.md`](../specs/m7.md) — 本里程碑 spec
- [`CONTEXT.md`](../../CONTEXT.md) — 领域词汇