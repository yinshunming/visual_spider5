# 运行并发与调度：PG 即队列 + 单 JVM 派发器

> 状态：Accepted（2026-08-02 M3 grilling 决策）
> 范围：`com.visualspider.run`（`RunCoordinator` / `RunDispatcher` / `RunLanePool`）与 `collection_run.status` 并发语义

## 背景

`architecture.md` §5.1 规定"PostgreSQL 保存权威状态；JVM 内有界执行器负责实际调度。不引入消息队列"，并要求"检查全局并发 3、用户并发 1 的约束"。`ADR-0004` 把运行 lane 池定为固定 3 lane、与配置池独立。但 M3 进入实现时仍有三个未决问题：

1. **限额计数口径**：product-spec §9 "全系统最多 3 个 / 每用户最多 1 个"没说 `WAITING` 算不算。若不算，用户可堆无数 `WAITING` run（DoS 队列）；若全算 `(W+R)≤3`，第 4 个用户提交即被拒。
2. **WAITING 队列放哪**：PG（`status=WAITING` 记录）还是 JVM 内存队列。
3. **单实例下要不要 `FOR UPDATE SKIP LOCKED`** 这种多消费者队列手段。

这三点影响 `RunCoordinator.start` 的拒绝逻辑、`RunDispatcher` 的派发机制、启动恢复与 M6 单实例租约的叠加点，必须在写代码前定死。

## 决策

采用 **PG 即队列 + 单 JVM 事件驱动派发器 + 每用户 (WAITING+RUNNING)≤1** 模型：

- **每用户 (WAITING+RUNNING) ≤ 1**：`RunCoordinator.start` 在 in-JVM 锁内、同事务做 `SELECT count(*) FROM collection_run WHERE owner_id=? AND status IN ('WAITING','RUNNING')`，≥ 1 即拒 `USER_RUN_LIMIT`（409），通过则生成快照 + 插入 `WAITING` run 记录。队列天然有界（≤ 账号数个等待，10 账号目标下最多 10 个 `WAITING`）。
- **全局 RUNNING ≤ 3**：由 `RunLanePool(3)` 物理限制（ADR-0004），**不靠 DB 计数**。`RunDispatcher` 只在 `borrowedCount() < capacity()` 时才派发一个 `WAITING` run。超出的 `WAITING` 排队，**不拒绝**（`start` 永不因全局满而拒；全局"最多 3 个"语义 = 最多 3 个 *执行*）。
- **WAITING 队列 = PG**：`status='WAITING'` 记录按 `created_at` 升序即队列顺序，**不在 JVM 复制**。`RunDispatcher` 取最旧：`SELECT ... WHERE status='WAITING' ORDER BY created_at LIMIT 1`，`UPDATE ... SET status='RUNNING', started_at=now() WHERE id=? AND status='WAITING'`（CAS，affected=1 才提交到 lane）。
- **事件驱动 + 兜底轮询**：`RunDispatcher` 在两个事件各触发一次派发尝试--(a) `start` 创建新 `WAITING` run 后，(b) lane 释放后；另加 5s 低频兜底轮询防丢事件。单 JVM，无需跨进程信号。
- **不引入消息队列 / JVM 内存队列**：PG 既是权威状态也是队列；JVM 侧只有派发器逻辑，不维护 `BlockingQueue` 或 `Semaphore` 作为队列副本。

## 备选

- **A. JVM 内存队列 + `Semaphore(3)`**：`start` DB 计数检查同决策，但 `WAITING` run 额外入 JVM `BlockingQueue`，`Semaphore(3)` 控并发。代价：`WAITING` 列表在 PG 和 JVM 两份，需保持一致；JVM 崩溃靠启动恢复兜底。比决策多一层同步状态，且 PG 已有 `status=WAITING` 时 JVM 队列是冗余复制。
- **B. 全局 (W+R)≤3**：第 4 个用户提交即拒（即使前 3 个是别用户的 `WAITING`）。代价：新用户无法在系统繁忙时排队，体验差；与"全局 3 = 最多 3 个执行"的产品语义不符。
- **C. 每用户仅限 RUNNING≤1，允许排队多个 WAITING**：用户可连提多个排队。代价：需额外每用户队列上限参数防 DoS；10 账号小用户量下"连提多个"非真实需求，YAGNI。
- **D. `FOR UPDATE SKIP LOCKED` 多消费者队列**：为多 JVM 消费者设计。代价：M3 明确单实例（architecture §11、product-spec §9），无多消费者，`SKIP LOCKED` 是无的放矢；M6 即便加租约也是"拒绝第二实例进入调度"而非"多实例共享队列"。
- **E. 无队列，创建即占 lane**：`start` 时 `RunLanePool.tryAcquire`，拿不到即拒 `RUN_LANE_FULL`。代价：违反 product-spec §7.3 的 `等待` 状态语义（用户拿不到 lane 就被拒，无排队）。

## 后果

- **`start` 的拒绝只有两种**：`USER_RUN_LIMIT`（每用户第 2 个 W+R）与 `TASK_NOT_READY`（任务非 READY）。**没有 `RUN_CAPACITY_FULL`**--全局满只排队不拒。这与配置会话 `CONFIG_LANE_FULL`（拒绝）不同，因为配置会话交互式不排队、运行有 `WAITING` 状态。
- **in-JVM 锁串行创建**：`start` 的 count-check + insert 用 `RunCoordinatorImpl` 内 `ReentrantLock` 串行，消除两并发请求同时通过 count 检查的竞态。单实例下足够；M6 的跨实例租约是叠加在上层的外锁，不冲突。
- **CAS 兜底**：`UPDATE ... WHERE status='WAITING'` affected=0 表示该 run 已被别处取走（单 JVM 下不会发生，但防御性 break）。单实例无需 `SKIP LOCKED`，普通 `SELECT ... LIMIT 1` + `UPDATE` 即可。
- **启动恢复与派发器顺序**：`RunRecovery`（把 WAITING/RUNNING 标 INTERRUPTED）必须在 `RunLanePool` 就绪后、`RunDispatcher` 接受新 run 前执行，避免恢复扫描与派发器竞争。`ApplicationRunner` 顺序由 `@Order` 保证。
- **`RunLanePool.acquire` 永不抛满**：与 `ConfigLanePool.acquire`（池满抛 `ConfigLaneFullException`）不同，`RunDispatcher` 先守卫 `borrowedCount() < capacity()` 再 acquire，故运行路径无"池满异常"。`LanePool` 接口不变，差异在调用方约束。
- **M6 叠加点**：单实例租约（第二个实例连同一库拒绝进入调度）在 `RunDispatcher` 启动前加租约获取；`RunRecovery` 与本决策不冲突。容量/保留入 `system_setting` 时，`DEFAULT_CAPACITY`/限额常量改为读 DB，调用点不变。
- **队列有界性**：每用户 (W+R)≤1 保证 `WAITING` 总数 ≤ 活跃账号数（目标 10），无需额外队列上限参数（拒绝备选 C 的复杂度）。

## 验证

- 单元：`RunCoordinatorImplTest` 覆盖每用户第 2 个 W+R 拒 `USER_RUN_LIMIT`、非 READY 拒 `TASK_NOT_READY`、in-JVM 锁串行（两线程并发 start 同用户，恰好 1 成功 1 拒）。
- 单元：`RunDispatcherTest` 覆盖 lane 释放后取最旧 WAITING 翻 RUNNING、3 lane 满不派发、CAS affected=0 兜底、5s 兜底轮询触发。
- 集成（pg-it）：`RunConcurrencyIT`--3 用户各 start 1 -> 3 RUNNING + 0 WAITING；4 用户各 start 1 -> 3 RUNNING + 1 WAITING；lane 释放后 WAITING -> RUNNING；同用户第 2 个 -> `USER_RUN_LIMIT`。
- 集成：`RunRecoveryIT`--WAITING/RUNNING 残留 -> 启动后 INTERRUPTED；恢复扫描在派发器启动前完成（无 run 被同时派发又标 INTERRUPTED）。
- 退出条件：同时 RUNNING 数永远 ≤ 3（`RunLanePool.borrowedCount()` ≤ 3）；每用户 W+R ≤ 1（DB 查询验证）；无 JVM 内存队列副本（代码审查 `RunDispatcher` 无 `BlockingQueue`/`Semaphore` 字段）。
