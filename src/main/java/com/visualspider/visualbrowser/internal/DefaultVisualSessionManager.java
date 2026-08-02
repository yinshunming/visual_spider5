package com.visualspider.visualbrowser.internal;

import com.visualspider.identity.domain.ActorId;
import com.visualspider.visualbrowser.spi.LanePool;
import com.visualspider.visualbrowser.spi.Lease;
import com.visualspider.visualbrowser.spi.SessionLifecycleState;
import com.visualspider.visualbrowser.spi.VisualSession;
import com.visualspider.visualbrowser.spi.VisualSessionManager;
import com.visualspider.shared.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@link VisualSessionManager} 默认实现（M2-1 #17）。
 *
 * <p>以 (actorId, taskId) 为键保证每用户每任务 1 会话；按 sessionId 维度的额外索引
 * 供 WS 路径按 sessionId 反查；每条命令路径都重新读 manager 状态校验所有权与
 * 任务匹配，不信任前端隐藏。
 */
@Component
public final class DefaultVisualSessionManager implements VisualSessionManager {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultVisualSessionManager.class);

    private final LanePool lanePool;
    private final Clock clock;
    private final LegacySessionFactory legacyFactory;

    private final Map<String, Entry> bySessionId = new ConcurrentHashMap<>();
    /** 关闭后保留最后一次 CLOSED 状态，供 lifecycle 查询；不参与所有权与命令。 */
    private final Map<String, Entry> closedIndex = new ConcurrentHashMap<>();
    /** key = {@code actor + ":" + taskId}; value = sessionId. */
    private final Map<String, String> byActorTask = new ConcurrentHashMap<>();
    /** key = actor value; value = active sessionId（保证每用户 1 会话）。 */
    private final Map<Long, String> byActor = new ConcurrentHashMap<>();

    /** 测试构造：不接入 legacy factory，{@code legacySession} 永远返回 empty。 */
    public DefaultVisualSessionManager(LanePool lanePool, Clock clock) {
        this(lanePool, clock, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public DefaultVisualSessionManager(
            @org.springframework.beans.factory.annotation.Qualifier("configLanePool") LanePool lanePool,
            Clock clock,
            LegacySessionFactory legacyFactory) {
        if (lanePool == null) {
            throw new IllegalArgumentException("lanePool 不能为空");
        }
        if (clock == null) {
            throw new IllegalArgumentException("clock 不能为空");
        }
        this.lanePool = lanePool;
        this.clock = clock;
        this.legacyFactory = legacyFactory;
    }

    @Override
    public VisualSession open(long taskId, ActorId actor) {
        if (actor == null) {
            throw new IllegalArgumentException("actor 不能为空");
        }
        // 计算 key（同一 actor 同一 task 不重复打开）
        String key = actor.value() + ":" + taskId;
        String existing = byActorTask.get(key);
        if (existing != null) {
            Entry entry = bySessionId.get(existing);
            if (entry != null) {
                return entry.snapshot();
            }
        }
        // 每用户只允许 1 会话；先抢占 actor 槽位。
        String newSessionId = UUID.randomUUID().toString();
        String previous = byActor.putIfAbsent(actor.value(), newSessionId);
        if (previous != null) {
            Entry existingEntry = bySessionId.get(previous);
            if (existingEntry != null) {
                return existingEntry.snapshot();
            }
            // 既有槽位但会话已不存在，清除并重试一次
            byActor.remove(actor.value(), previous);
            previous = byActor.putIfAbsent(actor.value(), newSessionId);
            if (previous != null && bySessionId.get(previous) != null) {
                return bySessionId.get(previous).snapshot();
            }
        }
        Lease lease;
        try {
            lease = lanePool.acquire(newSessionId);
        } catch (RuntimeException ex) {
            byActor.remove(actor.value(), newSessionId);
            throw ex;
        }
        VisualSession initial = new VisualSession(
                newSessionId, taskId, actor,
                clock.instant(), clock.instant(),
                SessionLifecycleState.ACTIVE);
        Entry entry = new Entry(initial, lease, new AtomicReference<>(initial));
        if (legacyFactory != null) {
            try {
                entry.legacy = legacyFactory.create(actor, taskId, newSessionId);
            } catch (RuntimeException ex) {
                // legacy session 创建失败（URL 非法 / Chromium 不可用）时释放 lease 并向上抛
                lanePool.release(lease);
                byActor.remove(actor.value(), newSessionId);
                throw ex;
            }
        }
        bySessionId.put(newSessionId, entry);
        byActorTask.putIfAbsent(key, newSessionId);
        // spec §D3：日志不含 owner 用户名以减少敏感信息；此处仅记稳定 sessionId / taskId / lane。
        LOG.info("open: sessionId={} taskId={} lane={}",
                newSessionId, taskId, lease.laneName());
        return initial;
    }

    @Override
    public Optional<VisualSession> find(long taskId, ActorId actor) {
        String key = actor.value() + ":" + taskId;
        String sessionId = byActorTask.get(key);
        if (sessionId == null) {
            return Optional.empty();
        }
        Entry entry = bySessionId.get(sessionId);
        if (entry == null) {
            return Optional.empty();
        }
        return Optional.of(entry.snapshot());
    }

    @Override
    public Optional<VisualSession> findBySessionId(String sessionId) {
        if (sessionId == null) {
            return Optional.empty();
        }
        Entry entry = bySessionId.get(sessionId);
        if (entry == null) {
            entry = closedIndex.get(sessionId);
            if (entry == null) {
                return Optional.empty();
            }
            // 关闭的会话只允许 owner 查询详情；不暴露给非 owner。
            // 这里返回快照，由调用方决定 actor 校验（requireOwnedBy 不会走这里）。
            return Optional.of(entry.snapshot());
        }
        return Optional.of(entry.snapshot());
    }

    @Override
    public VisualSession requireOwnedBy(String sessionId, ActorId actor) {
        Entry entry = bySessionId.get(sessionId);
        if (entry == null) {
            throw new VisualSessionNotFoundException(sessionId);
        }
        if (!entry.session.owner().equals(actor)) {
            throw new VisualSessionNotOwnerException(sessionId);
        }
        return entry.snapshot();
    }

    @Override
    public void heartbeat(String sessionId, ActorId actor) {
        Entry entry = bySessionId.get(sessionId);
        if (entry == null || !entry.session.owner().equals(actor)) {
            return;
        }
        entry.update(entry.session.withActivity(clock.instant()));
    }

    @Override
    public void close(String sessionId, ActorId actor, String reason) {
        Entry entry = bySessionId.get(sessionId);
        if (entry == null) {
            return;
        }
        if (!entry.session.owner().equals(actor)) {
            return;
        }
        if (!entry.closedMarker.compareAndSet(false, true)) {
            return;
        }
        entry.update(entry.session.withLifecycle(SessionLifecycleState.CLOSED));
        lanePool.release(entry.lease);
        if (entry.legacy != null) {
            try {
                entry.legacy.close();
            } catch (RuntimeException ignored) {
                // legacy session 关闭异常不阻断 manager 关闭流程
            }
        }
        // 关闭后将 session 从主索引移除；保留最近一次 CLOSED 状态供 lifecycle 查询。
        // 实现策略：在 bySessionId 主索引移除前，先存入 closedIndex。
        byActor.remove(entry.session.owner().value(), sessionId);
        String key = entry.session.owner().value() + ":" + entry.session.taskId();
        byActorTask.remove(key, sessionId);
        bySessionId.remove(sessionId, entry);
        closedIndex.put(sessionId, entry);
        LOG.info("close: sessionId={} reason={}", sessionId, reason == null ? "USER_CLOSE" : reason);
    }

    @Override
    public int activeCount() {
        return bySessionId.size();
    }

    /** 取 session 绑定的旧 spike {@link com.visualspider.visualbrowser.VisualSession}（含 Playwright/Page），供 WS 帧通道与 preview 复用。 */
    public java.util.Optional<com.visualspider.visualbrowser.VisualSession> legacySession(String sessionId) {
        Entry entry = bySessionId.get(sessionId);
        if (entry == null) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.ofNullable(entry.legacy);
    }

    @Override
    public java.util.List<SessionLifecycleTicker.ActiveSnapshot> snapshotActive() {
        return bySessionId.values().stream()
                .map(entry -> new SessionLifecycleTicker.ActiveSnapshot() {
                    @Override public String sessionId() { return entry.session.sessionId(); }
                    @Override public com.visualspider.identity.domain.ActorId owner() { return entry.session.owner(); }
                    @Override public long taskId() { return entry.session.taskId(); }
                    @Override public java.time.Instant openedAt() { return entry.session.openedAt(); }
                    @Override public java.time.Instant lastActivityAt() { return entry.session.lastActivityAt(); }
                })
                .collect(java.util.stream.Collectors.toList());
    }

    /** 内部快照版本快照；用于每次 deliver 时拿到不可变记录。 */
    private static final class Entry {
        volatile VisualSession session;
        final Lease lease;
        final AtomicReference<VisualSession> latest;
        final java.util.concurrent.atomic.AtomicBoolean closedMarker = new java.util.concurrent.atomic.AtomicBoolean();
        /** 旧 spike VisualSession（含 Playwright/Page）；测试或未接入 factory 时为 null。 */
        com.visualspider.visualbrowser.VisualSession legacy;

        Entry(VisualSession session, Lease lease, AtomicReference<VisualSession> latest) {
            this.session = session;
            this.lease = lease;
            this.latest = latest;
        }

        VisualSession snapshot() {
            VisualSession s = latest.get();
            return s == null ? session : s;
        }

        void update(VisualSession next) {
            session = next;
            latest.set(next);
        }
    }
}
