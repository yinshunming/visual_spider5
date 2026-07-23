package com.visualspider.visualbrowser.internal;

import com.visualspider.identity.domain.ActorId;
import com.visualspider.shared.time.Clock;
import com.visualspider.visualbrowser.spi.VisualSessionManager;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;
import jakarta.annotation.PreDestroy;

/**
 * 配置会话生命周期 ticker（M2-1 #17）。
 *
 * <p>每 {@code periodSeconds} 调用 {@link #tick()}：{@code now - lastActivityAt > idleLimit}
 * 触发 IDLE_TIMEOUT；{@code now - openedAt > maxDuration} 触发 MAX_DURATION；
 * 两者同时满足时优先 max。
 */
@Component
public class SessionLifecycleTicker {

    private static final Logger LOG = LoggerFactory.getLogger(SessionLifecycleTicker.class);

    /** Ticker 扫描的最小信息单元；Manager 仅暴露该快照，不暴露完整状态。 */
    public interface ActiveSnapshot {
        String sessionId();
        ActorId owner();
        long taskId();
        Instant openedAt();
        Instant lastActivityAt();
    }

    private final Clock clock;
    private final Duration idleLimit;
    private final Duration maxDuration;
    private final Supplier<List<ActiveSnapshot>> snapshots;
    private final BiConsumer<ActiveSnapshot, String> closer;
    private ScheduledExecutorService scheduler;

    /** 生产构造器：从 {@link VisualSessionManager} 拉取快照并调用其 close。 */
    public SessionLifecycleTicker(VisualSessionManager manager, Clock clock,
                                  Duration idleLimit, Duration maxDuration) {
        this(() -> manager.snapshotActive(),
                (snapshot, reason) -> manager.close(snapshot.sessionId(), snapshot.owner(), reason),
                clock, idleLimit, maxDuration);
    }

    /** 测试/扩展构造器：注入快照源与关闭回调。 */
    public SessionLifecycleTicker(Supplier<List<ActiveSnapshot>> snapshots,
                                  BiConsumer<ActiveSnapshot, String> closer,
                                  Clock clock, Duration idleLimit, Duration maxDuration) {
        if (snapshots == null || closer == null || clock == null || idleLimit == null || maxDuration == null) {
            throw new IllegalArgumentException("构造参数不能为空");
        }
        this.snapshots = snapshots;
        this.closer = closer;
        this.clock = clock;
        this.idleLimit = idleLimit;
        this.maxDuration = maxDuration;
    }

    /** Spring factory bean：默认 60s tick、15min idle、2h max。 */
    @Bean
    public static SessionLifecycleTicker sessionLifecycleTicker(
            VisualSessionManager manager,
            Clock clock,
            @Value("${visualbrowser.lane-pool.idle-seconds:900}") long idleSeconds,
            @Value("${visualbrowser.lane-pool.max-seconds:7200}") long maxSeconds,
            @Value("${visualbrowser.lane-pool.tick-seconds:60}") long periodSeconds) {
        SessionLifecycleTicker ticker = new SessionLifecycleTicker(manager, clock,
                Duration.ofSeconds(idleSeconds), Duration.ofSeconds(maxSeconds));
        ticker.startScheduler(periodSeconds);
        return ticker;
    }

    private void startScheduler(long periodSeconds) {
        ScheduledExecutorService s = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "visual-session-lifecycle");
            thread.setDaemon(true);
            return thread;
        });
        s.scheduleAtFixedRate(this::safeTick, periodSeconds, periodSeconds, TimeUnit.SECONDS);
        this.scheduler = s;
    }

    /** 同步驱动一次扫描，供测试和扩展使用。 */
    public void tick() {
        Instant now = clock.instant();
        for (ActiveSnapshot meta : snapshots.get()) {
            long idleSec = Math.max(0, now.getEpochSecond() - meta.lastActivityAt().getEpochSecond());
            long maxSec = Math.max(0, now.getEpochSecond() - meta.openedAt().getEpochSecond());
            if (maxSec > maxDuration.getSeconds()) {
                LOG.debug("session.close: sessionId={} reason=MAX_DURATION", meta.sessionId());
                closer.accept(meta, "MAX_DURATION");
            } else if (idleSec > idleLimit.getSeconds()) {
                LOG.debug("session.close: sessionId={} reason=IDLE_TIMEOUT", meta.sessionId());
                closer.accept(meta, "IDLE_TIMEOUT");
            }
        }
    }

    private void safeTick() {
        try {
            tick();
        } catch (RuntimeException ex) {
            LOG.warn("lifecycle tick failed", ex);
        }
    }

    @PreDestroy
    void shutdown() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }
}
