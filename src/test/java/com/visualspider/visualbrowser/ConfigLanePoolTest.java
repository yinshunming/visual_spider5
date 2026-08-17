package com.visualspider.visualbrowser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.visualspider.visualbrowser.internal.ConfigLaneFullException;
import com.visualspider.visualbrowser.internal.ConfigLanePool;
import com.visualspider.visualbrowser.spi.Lease;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.concurrent.TimeUnit;

/**
 * ConfigLanePool 单元测试（不依赖 Chromium，使用 {@link BrowserLane#forTest()}）。
 *
 * <p>覆盖：
 * <ul>
 *   <li>capacity 固定为 3；可同时借出 3 个 lease</li>
 *   <li>第 4 次 acquire 抛 {@link ConfigLaneFullException}</li>
 *   <li>release 后可再次 acquire</li>
 *   <li>同一 lease 重复 release 是 no-op（不报错）</li>
 *   <li>lease.close() 等同 release</li>
 * </ul>
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ConfigLanePoolTest {

    @Test
    @DisplayName("capacity 固定为 3；可同时借出 3 个 lease")
    void canBorrowUpToCapacity() {
        try (ConfigLanePool pool = new ConfigLanePool(i -> BrowserLane.forTest())) {
            Lease a = pool.acquire("s1");
            Lease b = pool.acquire("s2");
            Lease c = pool.acquire("s3");
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
    @DisplayName("借满 3 lane 后再次 acquire 抛 ConfigLaneFullException")
    void exceedsCapacityThrowsConfigLaneFull() {
        try (ConfigLanePool pool = new ConfigLanePool(i -> BrowserLane.forTest())) {
            Lease a = pool.acquire("s1");
            Lease b = pool.acquire("s2");
            Lease c = pool.acquire("s3");
            try {
                assertThatThrownBy(() -> pool.acquire("s4"))
                        .isInstanceOf(ConfigLaneFullException.class);
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
        try (ConfigLanePool pool = new ConfigLanePool(i -> BrowserLane.forTest())) {
            Lease a = pool.acquire("s1");
            a.close();
            Lease b = pool.acquire("s2");
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
        try (ConfigLanePool pool = new ConfigLanePool(i -> BrowserLane.forTest())) {
            Lease a = pool.acquire("s1");
            a.close();
            a.close();
            assertThat(pool.borrowedCount()).isZero();
        }
    }
}
