# 配置会话 lane 池与运行 lane 池独立

> 状态：Accepted（2026-07-21 M2 grilling 决策 1）
> 范围：`com.visualspider.visualbrowser.lane` 与 `com.visualspider.run.lane`

## 背景

`spikes/remote-browser.md` §4.3 给出"配置会话单实例 ≤ 3 lane"的决策，但 spike 阶段没有运行模块，无
法回答"这 3 个 lane 与运行 lane 是否共享"。`architecture.md` §4.1 也仅各自声明上限，没有说
明两池关系。M2 进入产品化时必须先确定 lane 池模型，因为它影响 lane 生命周期代码、内存预算、
资源回收顺序以及 M3 调度实现。

## 决策

配置会话与正式运行各自维护独立的 lane 池：

- `ConfigLanePool`：固定 3 lane，每 lane 一固定平台线程 + 一有界命令队列（64 槽），每个 lane
  持有独立的 Playwright + Browser + 多个非持久化 BrowserContext（一会话一 context）。
- `RunLanePool`：固定 3 lane，结构同上；M3 启用。
- 配置会话只从 `ConfigLanePool` 申请 lane；运行只从 `RunLanePool` 申请。两池互不感知。
- 池容量通过常量 `LANE_POOL_CAPACITY` 写死在 `visualbrowser.config.LanePoolConfig` 中，配置
  化延后 M6（M6 才把容量写入 `system_setting`）。

## 备选

- **A. 单池共享 3 lane（config+run 共用）**：代码简单，单实例 ~1.6 GB 内存。但 3 个 run 跑起
  来后用户无法配置任何任务，违反产品"边跑边配"的隐含期望；M3 引入后 M2 用户体验会突然恶
  化。
- **B. 单池共享但 config 优先抢占**：运行被临时挂起/排队。实现复杂（抢占 / 状态机迁移 /
  用户感知），违背 spike §4.2 "lane 内浏览器复用策略" 简单稳定的原则。
- **C. 独立双池（采纳）**：见决策。

## 后果

- 单实例内存预算与 spike §4.4 一致：4 GB RAM + 4 核 CPU 满足 3+3 lane ≈ 2-3 GB 浏览器 +
  ~300 MB JVM = ~3 GB 的工作集。M7 真机 LAN 验证时复核。
- `BrowserLane`（M0 spike）保持不变，由 `ConfigLanePool` 持有 3 个实例；`RunLanePool`
  同样持有 3 个 `BrowserLane` 实例。M3 不必修改 `BrowserLane` 本身。
- lane 绑定的资源回收顺序不变：spike §4.1 `Page → BrowserContext → Browser/Playwright`，由
  lane 在同一线程上执行。
- 两池独立的额外复杂度：`VisualSessionManager` 与未来 `RunCoordinator` 分别持有一个 lane 池
  引用；测试时需要能注入不同池大小，但 lane 池本身不参与 `extraction` / `task` 的 seam。
- 配置化延后 M6 的代价：M2/M3 阶段如果需要调容量，必须改代码重新打包；这是 spike §4.3
  "配置文件可配"决策的延后实现，与 roadmap §6 不冲突。

## 验证

- 单元测试：`ConfigLanePoolTest` 验证池容量、借出/归还、lane 不可重入、超额借出拒绝。
- 集成测试：在 `mvnw -Ppg-it verify` 中跑通 3 配置会话并发（已在 spike §3.1 验证过，留
  test fixture）；M3 增加 3 配置 + 3 运行并发的回归测试。
- 退出条件：`VisualSessionManager.size()` 永远 ≤ 3；同时运行的 config session 数 ≤ 3。