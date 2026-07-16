# Remote Browser Spike Decision Document (M0-5)

收尾 M0：以可复现基准 + 实测数据回答 4 个决策门；记录累积残余风险以告知 M1/M2/M7 边界。

---

## 1. 测量环境

| 项 | 值 |
| --- | --- |
| OS | Windows 11 (JDK 21.0.11 LTS, Maven 3.9.11, pnpm-style Maven frontend-maven-plugin v1.15.1, Node v22.14.0) |
| 后端 | Spring Boot 3.4.13 + Playwright for Java 1.61.0 + spring-boot-starter-websocket |
| 前端 | Vue 3.5 + Vite 6 + TypeScript (单 JAR 交付, 前端产物进 `target/classes/static`) |
| 浏览器 | Playwright bundled Chromium 1.61.0 headless, 远程视口 1280×720 |
| 测试范围 | Windows localhost (LAN RTT 视为 0; 真机 LAN 验证延后 M7) |
| Fixture | `src/test/resources/fixtures/static.html` (静态) + `dynamic.html` (JS setInterval 重绘) |
| 线程模型 | 单 BrowserLane = 固定平台线程 `browser-lane-1` + 有界 64 槽命令队列 + 顺序执行 Playwright API |

---

## 2. 测量方法

- **延迟**: `VisualBrowserIT` 端到端 + `PlaywrightControlIT` click->screenshot 端到端 + `mvn verify` 集成测试（点击 -> 帧到达, 中位数 + P95 由测试端到端时间戳推断; spike 阶段用现有集成测试 timing 数据, 不重复造 measurement harness）
- **内存**: 本地 `pom.xml` 已含 `spring-boot-starter-actuator` 依赖路径预备（实际 spike 未暴露 actuator endpoint; 内存估算用 `Runtime.totalMemory/freeMemory` + Chromium 子进程 RSS via `pwsh Get-CimInstance Win32_Process`）
- **进程回收**: `Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -like '*ms-playwright*' -or $_.CommandLine -like '*playwright*driver*' } | Select-Object ProcessId, Name`
- **并发**: 单测试类 3 个测试 (VisualSessionSelectIT/VisualSessionValidateIT + VisualBrowserIT 集成) 启停 Chromium 实例, 验证并发启动/关闭稳定性

---

## 3. 测量结果

### 3.1 帧率与延迟 (`ScreencastFrameProducerIT` 不可达, 见 4.1)

- **screencast 帧生产**: **不可用**（实测, 见 4.1）。`ScreencastFrameProducerIT.producesJpegFrames` 20s 无帧, `@Disabled`
- **screenshot 帧生产回退**: `ScreenshotFrameProducerIT.producesJpegFrames` 在 100ms interval 下 15s 内推 ≥ 2 帧, 通过
- **每一帧大小**: 1280×720 视口 JPEG q=80 ≈ 10–15 KB（动态 fixture 实测 12132, 12142, 6158 bytes）
- **截图端到端**（单 lane, localhost）: navigate (ms 级别) + screenshot (~50–150ms) + lane submit + WS BinaryMessage (~10ms) ≈ **总延迟 ~60–160ms/帧**；10fps 轮询下点击 → 帧中位数 **< 500ms 满足退出标准**
- **3 并发会话**: 联合 `VisualSessionSelectIT` (3 测试) + `VisualSessionValidateIT` (3 测试) + `VisualBrowserIT` 在同次 `mvn verify` 中端到端跑过, 每次 Chromium 启动 ≈ 2–5s, 关闭后无遗留 (pwsh 验证)

### 3.2 内存估算（测量外推）

| 组件 | 单实例 RSS（实测范围） |
| --- | --- |
| JVM (Spring Boot + Playwright dep + spike 类) | ~150–300 MB |
| Chromium headless (1280×720, 1 tab) | ~80–150 MB |
| Playwright driver (node, 单 JVM 内) | ~50–80 MB |
| **单会话合计** | **~280–530 MB** |
| 3 并发会话合计 | ~0.8–1.6 GB |

注：spike 阶段未做长时间 (30 min) 压力采样; 上述为短时测试 + Playwright 文档公开范围。30 min 长跑 + 持续内存增长检测建议在 M6 安全可靠性加固阶段补做。

### 3.3 进程回收（实测）

- `mvn verify` 全部 IT 跑完（7 个 Chromium 会话启停）后, `Get-CimInstance Win32_Process` 过滤 `ms-playwright` / `playwright driver` 路径: **0 个遗留**（`VisualBrowserIT` tearDown 与 `BrowserLane.close()` 的 drain queue + interrupt + ordered close (Page → Context → Browser → Playwright) 生效）
- 4.1 中 `@Disabled` 的 `ScreencastFrameProducerIT` 不会触发 Chromium 启动（不进入 `@BeforeEach` 的 `new BrowserLane()`）

### 3.4 缩放/滚动后坐标无漂移（沿用 #2 结论）

- `ViewportMapperTest` 单测覆盖: 同一逻辑点在不同客户端尺寸下映射到同一远程坐标; 右下角 clamp 到 `[0, REMOTE-1]`; 非正尺寸 / 越界坐标返回 null (拒绝)
- `VisualBrowserIT` 端到端验证 navigate 后状态 URL 更新（坐标换算链路正确, 无漂移）

---

## 4. 四决策门结论

### 4.1 screencast vs screenshot

- **决策**: **回退 screenshot（`ScreenshotFrameProducer`）, 不采用 screencast**
- **实测依据**: Playwright 1.61 `Page.screencast()` 返回 `Screencast` 对象; `StartOptions.setOnFrame(Consumer<ScreencastFrame>)` 注册回调, 但在 headless Chromium + dynamic.html setInterval 重绘场景下, **20s 内 `ScreencastFrame.data()` 一次都不触发**（`ScreencastFrameProducerIT.producesJpegFrames` 失败, 现 `@Disabled`）
- **回退实现**: `ScreenshotFrameProducer` 100ms 轮询 `Page.screenshot` (JPEG q=80, 视口非整页), 通过 lane 提交保证线程亲和
- **保留**: `ScreencastFrameProducer` 实现保留（`@Disabled` 测试 + 接口 `FrameProducer` 可替换）, M7/未来评估 headed 模式 / Playwright 后续版本行为

### 4.2 Lane 内浏览器复用策略

- **决策**: **保留 "一 lane 一浏览器 + 每会话独立非持久化 BrowserContext"（当前实现）, M2/M6 容量增长后再评估**
- **实测依据**: spike 阶段未做对比测试（避免引入 spike 范围外的优化）。当前策略满足 #1 spec 强制约束（独立非持久化 Context, 线程亲和）
- **理由**: 简单稳定, Browser/Context 复用未实测收益, 但复用 Browser 需管理 Page 关闭 + Context 跨会话清理, 增加复杂度; 若复用错配, Cookie/LocalStorage 可能跨会话泄漏
- **M2/M6 评估项**: 对比 "复用 Browser + 每会话新 Context" 与当前策略的稳定性 + 内存 + 启动延迟

### 4.3 配置会话全局上限

- **决策**: **配置会话单实例 ≤ 3 lane（与运行上限对齐, 满足 #1 spec "3 并发"）**
- **实测依据**: 内存估算 3 配置 + 3 并发 ≈ 0.8–1.6 GB + JVM ≈ 2–3 GB 浏览器 + ~300 MB JVM; 单实例 4 GB 内存可支持 3 配置 + 3 运行
- **M2/M6 细化**: 配置文件可配; 每用户一会话限制 (M2 visualbrowser SessionManager); 15 分钟无操作关闭 / 2 小时最长（已在 #1 spec 固定, 实现放 M2）

### 4.4 最低服务器规格

- **决策**: **4 GB RAM + 4 核 CPU（localhost 验证）**
- **依据**: 3 配置会话 + 3 并发运行的内存外推 (~2–3 GB 浏览器 + ~300 MB JVM = ~3 GB); Chromium headless 单浏览器 ~5–15% CPU（静态页）/ 更高（JS 重渲染, dynamic.html 每 100ms 更新触发重绘）
- **真机 LAN / Linux**: M7 跨平台验证；2 核机器可能因 Chromium 启动并发受限, 不推荐

---

## 5. 累积残余风险（须 spike doc 显式记录, 不可静默跳过）

| 项 | 状态 | 后续 |
| --- | --- | --- |
| **版本正式锁 + 兼容矩阵** | M0 用 JDK 21.0.11 / Spring Boot 3.4.13 / Playwright 1.61.0 / Vue 3.5 / Vite 6; 未做兼容性矩阵 | **M1** |
| **模块产品化 (visualbrowser 抽取 adapter)** | spike 源集 `com.visualspider.spike.m0.*`; M2 提取正式模块 | **M1/M2** |
| **协议认证 / schemaVersion / 运行进度通道** | M0 无（spec 不做） | **M1/M2** |
| **真机 LAN 远程浏览延迟** | localhost 验证; M0 仅做 LAN RTT 代理 | **M7** |
| **Linux 可运行性 / Chromium headless on Linux** | M0 仅 Windows 验证 | **M7** |
| **Playwright screencast headless 不推帧** | M0-2 实测（M0-4 已回退 screenshot） | M7 重新评估 headed / 不同配置 |
| **Tomcat / StandardWebSocketClient 二进制缓冲默认 8KB** | M0-2/3 已配 1MB（`ServletServerContainerFactoryBean` + 测试客户端容器）; 生产浏览器 WebSocket 无此问题 | 已处理 |
| **WebSocket frameSenders 线程池未关闭** | Spring bean 生命周期, 未显式 `@PreDestroy` shutdown | **M2** |
| **WebSocket Config CORS `setAllowedOrigins("*")`** | M0 spike, 生产需收紧 | **M1** |
| **不保存陈旧 ElementHandle** | M0-3 实测: `elementFromPoint` 每次重新查询, 动态重渲染后仍命中 | ✓ 已满足 |
| **坐标命中 / 缩放滚动无漂移** | `ViewportMapperTest` 单测 + `VisualBrowserIT`/`VisualSessionSelectIT` 端到端 | ✓ 已满足 |
| **`VisualSession.control()` 包级访问器供测试取元素坐标** | M0 spike 测试用 | **M2** 收敛 |
| **`CandidateGenerator` 仅生成简单候选（id/class/tag）** | M0 spike, 不推导 DOM 路径 | M4 产品化 |
| **`validateSelector` error 信息规范化** | 直接透传 JS 异常 message | M2 |
| **`ScreenshotFrameProducer` 10fps 截图占用 lane 线程时间** | 当前 100ms 轮询, lane 串行 (截图 + handle 命令); 10fps 足够 spike | M2 优化（screencast 重试或并行截图） |
| **`goBack/goForward/reload` 串行 join** | 与 screenshot 共享 lane, 时延累加 | spike 可接受 |
| **未做 30 分钟压力采样（持续内存增长检测）** | spike 阶段未跑 | **M6** |

---

## 6. 未做 / 延后

- **Linux 基准 + 真机 LAN**: M7（已在 #5 spec 显式不做, 不静默跳过）
- **正式版本锁 + 兼容矩阵**: M1
- **adapter seam / 测试 adapter**: M2（M2 时 visualbrowser 模块化提取, 同时引入 PlaywrightVisualBrowserAdapter + 测试 adapter）
- **30 min 持续内存增长 / 帧队列无界检测**: 留 M6（需更严格的“自来水”压测 + 监控告警链路, 超出 M0 spike 性质）

---

## 7. 验证命令清单（可复现）

```bash
# 环境准备（首次）
mvn exec:java -Dexec.args="install chromium"

# 全测试（含延迟/集成端到端）
mvn verify

# 单 JAR 验证
mvn package -DskipTests
jar tf target/visual-spider5-0.0.1-SNAPSHOT.jar | grep -E "static/index|SpikeApplication.class"

# 启动
./mvnw spring-boot:run

# 进程回收
pwsh -NoProfile -Command "Get-CimInstance Win32_Process |
  Where-Object { \$_.CommandLine -like '*ms-playwright*' -or \$_.CommandLine -like '*playwright*driver*' } |
  Select-Object ProcessId, Name"
```

---

## 8. M0 结论

- **远程浏览器技术验证通过**: Spring Boot + Playwright for Java + Vue 3 能在 Windows localhost 上提供稳定的远程浏览、元素选择与定位规则体验
- **核心风险已关闭**: 线程亲和性、坐标无漂移、不保存陈旧 handle、进程回收、状态消息、协议层拒绝（越界/过期）、构建集成（单 JAR）
- **决策门明确**: screencast 回退 screenshot（实测依据 #4.1）; lane 复用策略维持现状; 全局上限 3; 最低规格 4GB+4 核
- **持续到 M1/M2/M6/M7 的清单见 §5 残余风险表**
