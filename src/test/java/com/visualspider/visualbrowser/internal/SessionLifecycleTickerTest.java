package com.visualspider.visualbrowser.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.visualspider.identity.domain.ActorId;
import com.visualspider.shared.time.MutableClock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SessionLifecycleTickerTest {

    private MutableClock clock;
    private FakeSessionManager manager;
    private SessionLifecycleTicker ticker;
    private static final Duration IDLE = Duration.ofMinutes(15);
    private static final Duration MAX = Duration.ofHours(2);

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-07-23T10:00:00Z"));
        manager = new FakeSessionManager();
        ticker = new SessionLifecycleTicker(
                manager::snapshotActive,
                (snapshot, reason) -> manager.closeFromSnapshot(snapshot, reason),
                clock, IDLE, MAX);
    }

    @Test
    void idleBoundaryClosesAfterLimit() {
        manager.addOpenSession("s1", actor(1), 11L, clock.instant(), clock.instant());
        ticker.tick();
        assertThat(manager.closed).isEmpty();

        // 越过 idle 边界（严格 >）：epoch second 差必须 > 15*60
        clock.advance(IDLE.plus(Duration.ofSeconds(1)));
        ticker.tick();
        assertThat(manager.closed).containsExactly("s1");
    }

    @Test
    void tickClosesByMaxEvenIfHeartbeatRefreshedActivity() {
        Instant opened = clock.instant();
        manager.addOpenSession("s1", actor(1), 11L, opened, clock.instant());
        clock.advance(Duration.ofHours(1).plusMinutes(30));
        manager.refreshActivity("s1", clock.instant()); // 心跳刷新最后活动时间
        ticker.tick();
        assertThat(manager.closed).isEmpty(); // 1h30m 仍未到 2h

        clock.advance(Duration.ofHours(2));
        ticker.tick();
        assertThat(manager.closed).containsExactly("s1");
    }

    @Test
    void heartbeatPreventsIdleClose() {
        Instant opened = clock.instant();
        manager.addOpenSession("s1", actor(1), 11L, opened, clock.instant());
        clock.advance(Duration.ofMinutes(10));
        manager.refreshActivity("s1", clock.instant());
        clock.advance(Duration.ofMinutes(10));
        ticker.tick(); // 20min 但最近 10min 内有心跳，应跳过
        assertThat(manager.closed).isEmpty();
    }

    @Test
    void tickerSkipsAlreadyClosedSessions() {
        manager.addOpenSession("s1", actor(1), 11L, clock.instant(), clock.instant());
        manager.close("s1"); // 模拟用户先关闭；从 open 移除，closed 计入 1
        int initialClosed = manager.closed.size();
        clock.advance(Duration.ofHours(3));
        ticker.tick();
        // ticker 扫描时 open 中已不含该会话，不会再 close；closed 大小不变
        assertThat(manager.closed).hasSize(initialClosed);
    }

    private static ActorId actor(long id) {
        return new ActorId(id);
    }
}
