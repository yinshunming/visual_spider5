package com.visualspider.run.internal;

import com.visualspider.run.spi.RunExecutor;
import com.visualspider.run.spi.RunExecutionContext;
import com.visualspider.run.spi.RunPageHandle;
import com.visualspider.task.domain.TaskMode;
import com.visualspider.visualbrowser.spi.LanePool;
import com.visualspider.visualbrowser.spi.Lease;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import jakarta.annotation.PreDestroy;

/**
 * 单 JVM 派发器（ADR-0006）。
 *
 * <p>事件驱动：{@code RunCoordinator.start} 创建 WAITING run 后 + lane 释放后各触发一次派发；
 * 5s 兜底轮询防丢事件。PG 即队列：{@code SELECT ... WHERE status='WAITING' ORDER BY created_at LIMIT 1}
 * + {@code UPDATE ... SET status='RUNNING', started_at=now() WHERE id=? AND status='WAITING'}
 * （CAS affected=1 才提交到 lane）。
 *
 * <p><b>关键约束（ADR-0006）</b>：禁止使用 {@link java.util.concurrent.BlockingQueue} 或
 * {@link java.util.concurrent.Semaphore} 作为队列副本（WAITING 队列 = PG）；本类仅在
 * JVM 侧维护派发信号（{@link #scheduleDispatch()} + 兜底轮询）+ 执行器 + lane 池。
 *
 * <p>由 {@code LanePoolConfig} 装配 {@code RunLanePool}（3 lane），
 * {@code RunCoordinator.start} 注入本派发器并调用 {@link #scheduleDispatch()} 触发派发。
 *
 * <p>执行器（{@link RunExecutor}）在 lane 线程运行：执行完归还 lease，lease 关闭时再触发一次
 * 派发（{@link Lease#close()} → {@link #onLaneReleased(String)}）。
 */
@Component
public class RunDispatcher {

    private static final Logger LOG = LoggerFactory.getLogger(RunDispatcher.class);

    /** 兜底轮询间隔（spec §D2 / ADR-0006：5s 兜底轮询）。M3 写死常量；M6 入 system_setting。 */
    private static final long FALLBACK_INTERVAL_SECONDS = 5L;
    /** 单次运行最大时长（spec §容量：30 分钟）。 */
    private static final long MAX_DURATION_MS = TimeUnit.MINUTES.toMillis(30);
    /** 单次运行最大页数（spec §容量：200）。 */
    private static final int MAX_PAGES = 200;
    /** 单次运行最大结果数（spec §容量：10,000）。 */
    private static final int MAX_RECORDS = 10_000;

    private final LanePool lanePool;
    private final RunRepository repository;
    private final RunExecutor singlePageExecutor;
    private final RunExecutor multiPageExecutor;
    private final RunPageHandleProvider pageHandleProvider;

    private volatile boolean started;
    private ScheduledExecutorService scheduler;

    @org.springframework.beans.factory.annotation.Autowired
    public RunDispatcher(
            @org.springframework.beans.factory.annotation.Qualifier("runLanePool") LanePool runLanePool,
            RunRepository repository,
            @org.springframework.beans.factory.annotation.Qualifier("singlePageRunExecutor")
                    RunExecutor singlePageExecutor,
            @org.springframework.beans.factory.annotation.Qualifier("multiPageRunExecutor")
                    RunExecutor multiPageExecutor,
            RunPageHandleProvider pageHandleProvider) {
        this.lanePool = runLanePool;
        this.repository = repository;
        this.singlePageExecutor = singlePageExecutor;
        this.multiPageExecutor = multiPageExecutor;
        this.pageHandleProvider = pageHandleProvider == null
                ? new RunPageHandleProvider() {
                    @Override
                    public RunPageHandle openFor(Lease lease, long runId) {
                        throw new IllegalStateException(
                                "RunPageHandleProvider 未注入（M3-3 路径默认 no-op；测试可显式注入 fake）");
                    }
                }
                : pageHandleProvider;
    }

    /**
     * 兼容 M3-2 单元测试 / 旧调用点：单 executor 路由所有 mode（M3 行为；list / multi 复用同对象）。
     * 生产装配走 5 参构造按 {@code task.mode} 分发（spec §D15）。
     */
    public RunDispatcher(
            @org.springframework.beans.factory.annotation.Qualifier("runLanePool") LanePool runLanePool,
            RunRepository repository,
            RunExecutor executor,
            RunPageHandleProvider pageHandleProvider) {
        this(runLanePool, repository, executor, executor, pageHandleProvider);
    }

    /** 兼容 M3-2 单元测试：pageHandleProvider 走 no-op。 */
    public RunDispatcher(
            @org.springframework.beans.factory.annotation.Qualifier("runLanePool") LanePool runLanePool,
            RunRepository repository,
            RunExecutor executor) {
        this(runLanePool, repository, executor, executor, null);
    }

    /** Spring 上下文就绪后启动兜底轮询；run 接收新 run 前必须就绪（{@code @Order} 控制）。 */
    @EventListener(ContextRefreshedEvent.class)
    public void onContextRefreshed() {
        if (started) {
            return;
        }
        started = true;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "run-dispatcher-fallback");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::safeDispatch,
                FALLBACK_INTERVAL_SECONDS, FALLBACK_INTERVAL_SECONDS, TimeUnit.SECONDS);
        LOG.info("run dispatcher started: fallback={}s", FALLBACK_INTERVAL_SECONDS);
    }

    @PreDestroy
    public void shutdown() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    /**
     * 触发一次派发尝试。
     *
     * <p>事件源：{@code RunCoordinator.start}（WAITING 创建后）+ lane 释放后
     * （{@link LeaseNotifier} 触发）。
     *
     * <p>非阻塞：直接尝试一次 CAS + 提交；如果 lane 池满则 silent break，下次兜底轮询再来。
     */
    public void scheduleDispatch() {
        if (!started) {
            return;
        }
        // 同线程 inline 调用；不会与兜底轮询并发（最多重复 claim 一次，CAS affected=0 兜底）。
        safeDispatch();
    }

    private void safeDispatch() {
        try {
            dispatchOnce();
        } catch (RuntimeException ex) {
            LOG.warn("dispatch failed", ex);
        }
    }

    /** 一次派发循环：取 WAITING → CAS → 提交到 lane。 */
    void dispatchOnce() {
        while (lanePool.borrowedCount() < lanePool.capacity()) {
            var claimed = repository.claimOldestWaiting();
            if (claimed.isEmpty()) {
                return;
            }
            RunRepository.RunRecord rec = claimed.get();
            // claimed 一定是 RUNNING + started_at 已设（CAS 成功）；单 JVM 下不会发生 claimed=空但 affected=0
            try {
                leaseAndSubmit(rec);
            } catch (RuntimeException ex) {
                LOG.warn("dispatch submit failed runId={}; will retry via fallback", rec.runId(), ex);
                return;
            }
        }
    }

    private void leaseAndSubmit(RunRepository.RunRecord rec) {
        Lease lease;
        try {
            lease = lanePool.acquire("run-" + rec.runId());
        } catch (RuntimeException ex) {
            // pool full → 中断；兜底轮询下次再来
            throw ex;
        }
        RunPageHandle pageHandle = null;
        try {
            pageHandle = pageHandleProvider.openFor(lease, rec.runId());
        } catch (RuntimeException ex) {
            // Browser 启动失败（spec §D9 BROWSER_START_FAILED）；lease 仍需归还
            LOG.error("page handle open failed runId={}", rec.runId(), ex);
            try {
                repository.markTerminal(rec.runId(),
                        com.visualspider.run.spi.RunState.FAILED,
                        com.visualspider.run.spi.StopReason.BROWSER_START_FAILED);
            } catch (RuntimeException ignored) {
                // 写回失败 → 下次启动恢复扫描兜底
            }
            releaseAndDispatch(lease, "run-" + rec.runId());
            return;
        }
        RunExecutionContext context = new RunExecutionContext(
                System.currentTimeMillis(),
                MAX_DURATION_MS,
                MAX_PAGES,
                MAX_RECORDS,
                pageHandle);
        // 模式路由（spec §D15）：claim 后读 snapshot.mode 选 executor，不引入 Router。
        // LIST 一律走 multiPageExecutor（issue #40 / spec §D4 阶段1：隐式升级；
        // paginationRule=null 时退化为"只跑当前页"，等价 M4 ListRunExecutor 行为）。
        RunExecutor exec = (rec.snapshot().definition().mode() instanceof TaskMode.List)
                ? multiPageExecutor : singlePageExecutor;
        LeaseNotifier notifier = new LeaseNotifier(this);
        NotifyingLease notifying = new NotifyingLease(lease, notifier, "run-" + rec.runId());
        try {
            exec.execute(context, rec.runId());
        } catch (RuntimeException ex) {
            LOG.error("run execution failed runId={}", rec.runId(), ex);
            try {
                repository.markTerminal(rec.runId(),
                        com.visualspider.run.spi.RunState.FAILED,
                        com.visualspider.run.spi.StopReason.PAGE_RETRY_EXHAUSTED);
            } catch (RuntimeException ignored) {
                // 状态写回失败 → 下次启动恢复扫描兜底
            }
        } finally {
            notifying.close();
        }
    }

    /** lane 归还并触发下一轮派发。包级私有：兜底 BROWSER_START_FAILED 用。 */
    private void releaseAndDispatch(Lease lease, String runKey) {
        try {
            lease.close();
        } catch (RuntimeException ignored) {
        }
        onLaneReleased(runKey);
    }

    /** Lease 释放回调（事件驱动派发的第二个事件源）。 */
    void onLaneReleased(String runKey) {
        scheduleDispatch();
    }

    /**
     * 暴露给测试：手动触发一次派发循环（同步等待）。
     */
    public void dispatchOnceForTest() {
        dispatchOnce();
    }

    /** 包装 {@link Lease}：close 时通知派发器触发新一轮派发。 */
    private static final class NotifyingLease implements Lease {
        private final Lease delegate;
        private final LeaseNotifier notifier;
        private final String runKey;
        private volatile boolean closed;

        NotifyingLease(Lease delegate, LeaseNotifier notifier, String runKey) {
            this.delegate = delegate;
            this.notifier = notifier;
            this.runKey = runKey;
        }

        @Override
        public String laneName() {
            return delegate.laneName();
        }

        @Override
        public boolean isOpen() {
            return !closed && delegate.isOpen();
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            try {
                delegate.close();
            } finally {
                notifier.onReleased(runKey);
            }
        }
    }

    /** 回调持有派发器；用于 {@link NotifyingLease} 关闭时通知。 */
    private static final class LeaseNotifier {
        private final RunDispatcher dispatcher;

        LeaseNotifier(RunDispatcher dispatcher) {
            this.dispatcher = dispatcher;
        }

        void onReleased(String runKey) {
            try {
                dispatcher.onLaneReleased(runKey);
            } catch (RuntimeException ex) {
                LOG.warn("lane-released notify failed runKey={}", runKey, ex);
            }
        }
    }
}