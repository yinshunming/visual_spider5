package com.visualspider.run.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.visualspider.visualbrowser.BrowserLane;
import com.visualspider.visualbrowser.spi.Lease;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * RunLanePool 单元测试（不依赖 Chromium，使用 {@link BrowserLane#forTest()}）。
 *
 * <p>镜像 {@code ConfigLanePoolTest}：3 lane 借出 / 归还 / 不可重入；
 * {@link RunLanePool#acquire} 在守卫生调用下永不抛满（ADR-0006：排队发生在 PG，
 * 不在 lane 池；运行时 {@code RunDispatcher} 持锁后调用 acquire）。
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class RunLanePoolTest {

    @Test
    @DisplayName("capacity 固定为 3；可同时借出 3 个 lease")
    void canBorrowUpToCapacity() {
        try (RunLanePool pool = new RunLanePool(i -> BrowserLane.forTest())) {
            Lease a = pool.acquire("run-1");
            Lease b = pool.acquire("run-2");
            Lease c = pool.acquire("run-3");
            try {
                assertThat(pool.borrowedCount()).isEqualTo(3);
                assertThat(a.isOpen()).isTrue();
                assertThat(b.isOpen()).isTrue();
                assertThat(c.isOpen()).isTrue();
                assertThat(a.laneName()).isNotBlank();
            } finally {
                a.close();
                b.close();
                c.close();
            }
        }
    }

    @Test
    @DisplayName("第 4 次 acquire 抛 RuntimeException（无 ConfigLaneFullException 语义）")
    void exceedsCapacityThrowsRuntimeException() {
        try (RunLanePool pool = new RunLanePool(i -> BrowserLane.forTest())) {
            Lease a = pool.acquire("run-1");
            Lease b = pool.acquire("run-2");
            Lease c = pool.acquire("run-3");
            try {
                // RunLanePool.acquire 没有特殊满载异常；契约：抛 RuntimeException 即可
                // （调用方 RunDispatcher 应在 acquire 前用 borrowedCount < capacity 守卫，永不触达）
                assertThatThrownBy(() -> pool.acquire("run-4"))
                        .isInstanceOf(RuntimeException.class);
            } finally {
                a.close();
                b.close();
                c.close();
            }
        }
    }

    @Test
    @DisplayName("release 后可再次 acquire")
    void releaseAllowsAnotherAcquire() {
        try (RunLanePool pool = new RunLanePool(i -> BrowserLane.forTest())) {
            Lease a = pool.acquire("run-1");
            a.close();
            Lease b = pool.acquire("run-2");
            try {
                assertThat(pool.borrowedCount()).isEqualTo(1);
                assertThat(b.isOpen()).isTrue();
                assertThat(a.isOpen()).isFalse();
            } finally {
                b.close();
            }
        }
    }

    @Test
    @DisplayName("重复 release 是 no-op")
    void doubleReleaseIsIdempotent() {
        try (RunLanePool pool = new RunLanePool(i -> BrowserLane.forTest())) {
            Lease a = pool.acquire("run-1");
            a.close();
            a.close();
            assertThat(pool.borrowedCount()).isZero();
        }
    }

    @Test
    @DisplayName("DEFAULT_CAPACITY = 3（ADR-0004）")
    void defaultCapacityIsThree() {
        assertThat(RunLanePool.DEFAULT_CAPACITY).isEqualTo(3);
    }

    @Test
    @DisplayName("无 laneFactory 抛 IllegalArgumentException")
    void nullLaneFactoryRejected() {
        assertThatThrownBy(() -> new RunLanePool(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("非正 capacity 抛 IllegalArgumentException")
    void nonPositiveCapacityRejected() {
        assertThatThrownBy(() -> new RunLanePool(0, i -> BrowserLane.forTest()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("capacity 报告正确")
    void capacityReportsCorrectly() {
        try (RunLanePool pool = new RunLanePool(5, i -> BrowserLane.forTest())) {
            assertThat(pool.capacity()).isEqualTo(5);
        }
    }
}