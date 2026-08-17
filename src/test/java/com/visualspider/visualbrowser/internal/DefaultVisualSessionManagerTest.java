package com.visualspider.visualbrowser.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.visualspider.identity.domain.ActorId;
import com.visualspider.visualbrowser.spi.LanePool;
import com.visualspider.visualbrowser.spi.Lease;
import com.visualspider.visualbrowser.spi.SessionLifecycleState;
import com.visualspider.visualbrowser.spi.VisualSession;
import com.visualspider.visualbrowser.spi.VisualSessionManager;
import com.visualspider.shared.time.MutableClock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DefaultVisualSessionManagerTest {

    private MutableClock clock;
    private FakeLanePool pool;
    private VisualSessionManager manager;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-07-23T10:00:00Z"));
        pool = new FakeLanePool(3);
        manager = new DefaultVisualSessionManager(pool, clock);
    }

    @Test
    void openNewSessionAcquiresLeaseAndStoresMetadata() {
        VisualSession session = manager.open(11L, actor(1));

        assertThat(session.taskId()).isEqualTo(11L);
        assertThat(session.owner().value()).isEqualTo(1L);
        assertThat(session.openedAt()).isEqualTo(clock.instant());
        assertThat(session.lastActivityAt()).isEqualTo(clock.instant());
        assertThat(session.lifecycle()).isEqualTo(SessionLifecycleState.ACTIVE);
        assertThat(pool.borrowedCount()).isEqualTo(1);
    }

    @Test
    void openingSameTaskForSameActorIsIdempotent() {
        VisualSession first = manager.open(11L, actor(1));

        clock.advanceSeconds(5);
        VisualSession second = manager.open(11L, actor(1));

        assertThat(second.sessionId()).isEqualTo(first.sessionId());
        assertThat(pool.borrowedCount()).isEqualTo(1);
    }

    @Test
    void heartbeatRefreshesLastActivity() {
        VisualSession session = manager.open(11L, actor(1));
        clock.advanceSeconds(30);
        manager.heartbeat(session.sessionId(), actor(1));

        VisualSession latest = manager.requireOwnedBy(session.sessionId(), actor(1));
        assertThat(latest.lastActivityAt()).isEqualTo(clock.instant());
    }

    @Test
    void requireOwnedByRejectsOtherCollector() {
        VisualSession session = manager.open(11L, actor(1));

        assertThatThrownBy(() -> manager.requireOwnedBy(session.sessionId(), actor(2)))
                .isInstanceOf(VisualSessionNotOwnerException.class);
    }

    @Test
    void requireOwnedByMissingThrowsNotFound() {
        assertThatThrownBy(() -> manager.requireOwnedBy("ghost", actor(1)))
                .isInstanceOf(VisualSessionNotFoundException.class);
    }

    @Test
    void closeReleasesLeaseAndRemovesSession() {
        VisualSession session = manager.open(11L, actor(1));
        manager.close(session.sessionId(), actor(1), "USER_CLOSE");

        assertThat(pool.borrowedCount()).isZero();
        assertThat(manager.find(11L, actor(1))).isEmpty();
        // 关闭后 sessionId 仍可查询到最近一次 CLOSED 副本
        assertThat(manager.findBySessionId(session.sessionId())).isPresent();
        assertThat(manager.activeCount()).isZero();
    }

    @Test
    void closeOnDifferentActorIsNoOp() {
        VisualSession session = manager.open(11L, actor(1));
        manager.close(session.sessionId(), actor(2), "USER_CLOSE");

        assertThat(pool.borrowedCount()).isEqualTo(1);
        assertThat(manager.activeCount()).isEqualTo(1);
    }

    @Test
    void poolFullSurfacesAsConfigLaneFull() {
        pool.capacity = 1;
        manager = new DefaultVisualSessionManager(pool, clock);
        manager.open(11L, actor(1));

        assertThatThrownBy(() -> manager.open(22L, actor(2)))
                .isInstanceOf(ConfigLaneFullException.class);
    }

    @Test
    void sessionLifecycleTransitionsToClosedOnClose() {
        VisualSession session = manager.open(11L, actor(1));
        manager.close(session.sessionId(), actor(1), "USER_CLOSE");
        // 关闭后查询应返回 CLOSED
        VisualSession latest = manager.findBySessionId(session.sessionId()).orElseThrow();
        assertThat(latest.lifecycle()).isEqualTo(SessionLifecycleState.CLOSED);
    }

    private static ActorId actor(long id) {
        return new ActorId(id);
    }

    /** 仅供该测试使用，单线程；不需要线程安全。 */
    private static final class FakeLanePool implements LanePool {
        private final int maxCapacity;
        private int capacity;
        private int borrowed = 0;

        FakeLanePool(int capacity) {
            this.maxCapacity = capacity;
            this.capacity = capacity;
        }

        @Override
        public int capacity() {
            return maxCapacity;
        }

        @Override
        public int borrowedCount() {
            return borrowed;
        }

        @Override
        public Lease acquire(String sessionId) {
            if (borrowed >= capacity) {
                throw new ConfigLaneFullException();
            }
            borrowed++;
            return new FakeLease();
        }

        @Override
        public void release(Lease lease) {
            if (lease instanceof FakeLease && ((FakeLease) lease).released.incrementAndGet() == 1) {
                borrowed--;
            }
        }

        private final class FakeLease implements Lease {
            final AtomicInteger released = new AtomicInteger(0);

            @Override
            public String laneName() {
                return "fake-lane";
            }

            @Override
            public boolean isOpen() {
                return released.get() == 0;
            }

            @Override
            public void close() {
                release(this);
            }
        }
    }
}
