package com.visualspider.run.internal;

import com.visualspider.visualbrowser.BrowserLane;
import com.visualspider.visualbrowser.spi.LanePool;
import com.visualspider.visualbrowser.spi.Lease;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntFunction;

/**
 * 运行 lane 池（M3-2 #24 / ADR-0004 / ADR-0006）。
 *
 * <p>结构与 {@code ConfigLanePool} 同：固定 {@value #DEFAULT_CAPACITY} 个 {@link BrowserLane}，
 * 各自独立 Playwright + Browser；通过 {@link Semaphore} 控制并发。
 * 与配置池完全独立（ADR-0004），互不感知。
 *
 * <p>关键差异（ADR-0006）：{@link #acquire(String)} 没有专属"池满异常"。
 * 调用方（{@code RunDispatcher}）必须在 acquire 前用 {@link #borrowedCount()} &lt;
 * {@link #capacity()} 守卫；运行路径上永不触达"满"分支。排队发生在 PG
 * {@code status='WAITING'} 记录，不在 lane 池。
 *
 * <p>lane 关闭顺序由 {@link BrowserLane#close()} 保证：Page → BrowserContext →
 * Browser → Playwright 在同一 lane 线程串行。
 */
public final class RunLanePool implements LanePool, AutoCloseable {

    /** 默认 lane 池容量（ADR-0004 / ADR-0006；配置化延后 M6）。 */
    public static final int DEFAULT_CAPACITY = 3;

    private final List<BrowserLane> allLanes;
    private final List<BrowserLane> available;
    private final Lease[] activeLeases;
    private final Semaphore semaphore;
    private volatile boolean closed;

    /** 默认构造：固定 {@value #DEFAULT_CAPACITY} lane，使用 {@link BrowserLane#forTest()}。 */
    public RunLanePool() {
        this(DEFAULT_CAPACITY, i -> BrowserLane.forTest());
    }

    /** 单 lane 工厂构造。 */
    public RunLanePool(IntFunction<BrowserLane> laneFactory) {
        this(DEFAULT_CAPACITY, laneFactory);
    }

    public RunLanePool(int capacity, IntFunction<BrowserLane> laneFactory) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity 必须 > 0");
        }
        if (laneFactory == null) {
            throw new IllegalArgumentException("laneFactory 不能为空");
        }
        this.allLanes = new ArrayList<>(capacity);
        for (int i = 0; i < capacity; i++) {
            allLanes.add(laneFactory.apply(i));
        }
        this.available = new ArrayList<>(allLanes);
        this.activeLeases = new Lease[capacity];
        this.semaphore = new Semaphore(capacity);
    }

    @Override
    public int capacity() {
        return allLanes.size();
    }

    @Override
    public int borrowedCount() {
        return allLanes.size() - available.size();
    }

    /**
     * 借出一个 lease。运行时调用方应已在 {@link #borrowedCount()} &lt; {@link #capacity()}
     * 守卫下调用；超额时抛 {@link IllegalStateException}（不暴露特定满载异常，便于上层
     * 排查"未守卫生调用"的 bug）。
     */
    @Override
    public Lease acquire(String runId) {
        if (closed) {
            throw new IllegalStateException("RunLanePool 已关闭");
        }
        if (!semaphore.tryAcquire()) {
            throw new IllegalStateException("RunLanePool 已满（调用方应先用 borrowedCount < capacity 守卫）");
        }
        synchronized (available) {
            if (available.isEmpty()) {
                semaphore.release();
                throw new IllegalStateException("RunLanePool 已满（available 为空）");
            }
            BrowserLane lane = available.remove(available.size() - 1);
            DefaultLease lease = new DefaultLease(lane, this, runId);
            int slot = indexOfActive(lane);
            if (slot >= 0) {
                activeLeases[slot] = lease;
            }
            return lease;
        }
    }

    @Override
    public void release(Lease lease) {
        if (!(lease instanceof DefaultLease dl)) {
            return;
        }
        if (!dl.released.compareAndSet(false, true)) {
            return;
        }
        synchronized (available) {
            int slot = indexOfActive(dl.lane);
            if (slot >= 0 && activeLeases[slot] == lease && !closed) {
                activeLeases[slot] = null;
                available.add(dl.lane);
                semaphore.release();
            }
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        synchronized (available) {
            for (BrowserLane lane : allLanes) {
                try {
                    lane.close();
                } catch (RuntimeException ignored) {
                    // 关闭阶段忽略单 lane 关闭异常（资源回收语义）
                }
            }
            available.clear();
        }
    }

    private int indexOfActive(BrowserLane lane) {
        for (int i = 0; i < allLanes.size(); i++) {
            if (allLanes.get(i) == lane) {
                return i;
            }
        }
        return -1;
    }

    /** lease 内部类：持有 lane 引用；close 触发归还；isOpen 反映状态。 */
    private static final class DefaultLease implements Lease {
        private final BrowserLane lane;
        private final RunLanePool owner;
        @SuppressWarnings("unused")
        private final String runId;
        private final AtomicBoolean released = new AtomicBoolean(false);

        DefaultLease(BrowserLane lane, RunLanePool owner, String runId) {
            this.lane = lane;
            this.owner = owner;
            this.runId = runId;
        }

        @Override
        public String laneName() {
            return lane == null ? "released" : lane.laneThreadName();
        }

        @Override
        public boolean isOpen() {
            return !released.get();
        }

        @Override
        public void close() {
            owner.release(this);
        }
    }
}