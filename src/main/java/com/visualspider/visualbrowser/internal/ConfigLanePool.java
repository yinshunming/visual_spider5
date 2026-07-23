package com.visualspider.visualbrowser.internal;

import com.visualspider.visualbrowser.BrowserLane;
import com.visualspider.visualbrowser.spi.LanePool;
import com.visualspider.visualbrowser.spi.Lease;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntFunction;

/**
 * 配置会话 lane 池（M2-1 #17 / ADR-0004）。
 *
 * <p>启动时固定创建 {@value #DEFAULT_CAPACITY} 个 {@link BrowserLane}，每个 lane
 * 持有独立 Playwright + Browser；会话借用通过 {@link Semaphore} 控制，最多
 * {@value #DEFAULT_CAPACITY} 个并发 session。
 *
 * <p>{@link LanePool#acquire(String)} 超额借出抛 {@link ConfigLaneFullException}，
 * 由 {@code GlobalExceptionHandler} 映射为 {@code 409 CONFIG_LANE_FULL}。
 *
 * <p>lane 关闭顺序由 {@link BrowserLane#close()} 保证（spec §4.1 spike）：
 * Page → BrowserContext → Browser → Playwright 在同一 lane 线程串行。
 */
public final class ConfigLanePool implements LanePool, AutoCloseable {

    /** 默认 lane 池容量（spec §D3 / ADR-0004，配置化延后 M6）。 */
    public static final int DEFAULT_CAPACITY = 3;

    private final List<BrowserLane> allLanes;
    private final List<BrowserLane> available;
    private final Lease[] activeLeases;
    private final Semaphore semaphore;
    private volatile boolean closed;

    /**
     * 默认构造：固定 {@value #DEFAULT_CAPACITY} lane，使用 {@link BrowserLane#forTest()}
     * 注入（仅便于快速构造/不使用真实 Chromium 的场景）。
     * <p>生产应由 Spring 提供 {@code BrowserLane::new}（启动 Chromium）。
     */
    public ConfigLanePool() {
        this(DEFAULT_CAPACITY, i -> BrowserLane.forTest());
    }

    /** 单 lane 工厂构造：固定 {@value #DEFAULT_CAPACITY} 个 lane，使用同一 {@code laneFactory} 创建。 */
    public ConfigLanePool(IntFunction<BrowserLane> laneFactory) {
        this(DEFAULT_CAPACITY, laneFactory);
    }

    public ConfigLanePool(int capacity, IntFunction<BrowserLane> laneFactory) {
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

    @Override
    public Lease acquire(String sessionId) {
        if (closed) {
            throw new IllegalStateException("ConfigLanePool 已关闭");
        }
        if (!semaphore.tryAcquire()) {
            throw new ConfigLaneFullException();
        }
        synchronized (available) {
            if (available.isEmpty()) {
                semaphore.release();
                throw new ConfigLaneFullException();
            }
            BrowserLane lane = available.remove(available.size() - 1);
            DefaultLease lease = new DefaultLease(lane, this, sessionId);
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
        // 默认 lease 已在 close() 内通过 AtomicBoolean 防止重复归还
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
                    // 关闭阶段 lane 关闭异常不向上抛出（资源回收语义）
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
        private final ConfigLanePool owner;
        @SuppressWarnings("unused")
        private final String sessionId;
        private final AtomicBoolean released = new AtomicBoolean(false);

        DefaultLease(BrowserLane lane, ConfigLanePool owner, String sessionId) {
            this.lane = lane;
            this.owner = owner;
            this.sessionId = sessionId;
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
