# 单实例保护：PostgreSQL advisory lock vs 租约行 vs 阻塞等待

> 状态：Accepted（2026-08-23 M6-2 决策 / docs/specs/m6.md §D6）
> 范围：`com.visualspider.run`（`SingleInstanceGuard` / `SingleInstanceGuardConfig`）与 Spring 启动序

## 背景

`architecture.md` §11 与 `product-spec.md` §9 明确"应用仅支持单实例运行；禁止两个 JAR 同时连接同一业务数据库参与调度"。但 M0-M5 阶段该约束仅是文档约束，无任何运行期机制。现状调查（2026-08-23）确认的隐患：

- 第二个 JAR 启动后无任何拒绝，会各自派发、各自占 lane，产生双倍目标站点压力与状态覆盖写。
- `system_setting` 表已建（V1）但零读写，无租约行可写。
- 启动期 Flyway / `RunRecovery` / `RunDispatcher` 顺序已有，但任何"租约行 + 心跳"机制需要 TTL 与心跳清理任务，运维面更复杂。

M6 决策要求给文档约束加机制：第二个实例启动时立即失败退出。

## 决策

采用 **PostgreSQL advisory lock + `pg_try_advisory_lock` 非阻塞 + 专用 JDBC 直连（非池）+ SmartLifecycle 最早 phase** 模型：

- **advisory lock 跨进程可见**：PG 内置能力，零 migration，连接断开自动释放，崩溃残留自愈。
- **`pg_try_advisory_lock` 非阻塞**：fail-fast 语义；阻塞等待会让"等待前例退出"造成静默挂起，违背决策。
- **专用 `DriverManager.getConnection` 直连（非 HikariCP 池）**：advisory lock 绑定单一长连接，池化连接的回收 / 校验会意外释放锁；专用直连保证锁与连接生命周期一致。
- **不写 `system_setting` 租约行、不心跳、不 TTL**：崩溃后连接断开 PG 自动回收锁，无需清理任务；这是选 advisory lock 相对"租约行 + 心跳"的核心理由。
- **实现为 `SmartLifecycle`，phase 取 `Integer.MIN_VALUE + 100`**：早于 `RunDispatcher` / `RetentionCleanup` / `SeedAdminInitializer` 启动；guard 失败时上述组件根本不启动。
- **启动失败语义**：guard.start() 抛 `IllegalStateException`，Spring `DefaultLifecycleProcessor` 包装为 `ApplicationContextException`，Spring Boot `run()` 捕获后退出非零状态。
- **关闭路径**：guard.stop() 执行 `pg_advisory_unlock` 后 `close()`；连接断开时 PG 也会自动释放锁，记 WARN 不阻断关闭。

## 备选

- **A. `system_setting` 租约行 + 心跳 TTL**：`INSERT INTO system_setting(key, value, expires_at)` 续约；TTL 过期视为前例崩溃。代价：需额外清理任务、定期心跳、TTL 调参；崩溃后到 TTL 过期之间的窗口会出现"两个实例并行调度"；不选。
- **B. `pg_advisory_lock`（阻塞）**：前例退出后第二实例能立即接管，对长停机维护场景友好。代价：阻塞等待让启动静默挂起，运维难以诊断；fail-fast 是更可取的契约。
- **C. PG `LISTEN/NOTIFY` + JVM 内存选举**：分布式协调手段，超出单实例约束范围（`architecture.md` §11 明确"不引入消息队列、不支持多实例高可用"）；不选。
- **D. JVM 启动参数文件锁（`FileChannel.tryLock`）**：仅限单机；多机部署无效；不选。
- **E. JDBC Pool 启动校验**：让 HikariCP 在初始化时 `SELECT pg_try_advisory_lock`，简单一行。代价：guard 与池耦合，关闭路径复杂（池关闭会归还连接，锁被释放时机不可控）；不选。

## 后果

- **`SingleInstanceGuard` 是 `SmartLifecycle` 唯一使用者**：模块内 internal，与既有 `RunRecoveryImpl`（`ApplicationRunner`）无 seam 冲突（`RunRecovery` 在 `finishRefresh()` 之后的 `ApplicationRunner` 阶段执行，guard 早已持锁或失败退出）。
- **锁 key = 类 FQN 哈希**：跨 JVM / 跨 PG 实例同 key；类名变更 = 部署变更 = 接受行为。
- **测试友好**：`run.single-instance.enabled=false` 可关闭 guard（典型场景：单测不连 PG 的 IT）；默认开启。
- **生产 profile 行为**：prod profile 默认开 guard；如运维需临时绕过（例如 PG 维护窗口），需通过 `run.single-instance.enabled=false` + 短重启；不提供"永久关闭"的开关。
- **M5 -> M6 衔接**：M5-1（M5 spec）预留的"M6 入 system_setting"清单中，"调度准入"一项原计划走租约行；本 ADR 关闭该路径，advisory lock 是更轻量方案；ADR-0006（单 JVM 派发）的语义不变，guard 仅是文档约束的机制化。
- **M6-5 压测期间的 `pg-stress` profile**：guard 默认开，三并行运行共用同一 guard 锁（同一 JVM 内同一 key 仍合法，因为是同一连接持锁），验证压测形态下不影响调度。

## Lock Key

`SingleInstanceGuard.LOCK_KEY` 取 `com.visualspider.run.internal.SingleInstanceGuard` 字符串的 `hashCode()` 转为 long。该 key 全 JVM 唯一；与 PG 内部保留 key 段无冲突（PG advisory lock key 是任意 bigint）。

## 部署注意

- **生产 profile**：guard 默认开；若 PG 维护时临时关闭 guard，需设置 `run.single-instance.enabled=false`。
- **多实例场景**：当前架构明确不支持；如未来扩展为多实例（不在 M7 范围），本 ADR 需重新评估，可能演进为协调服务（etcd / ZK）。
- **PG 重启**：advisory lock 是会话级，PG 重启后所有锁自动释放；guard 在 Spring 重启后会重新尝试获取，无需人工干预。
