package com.visualspider.visualbrowser.internal;

import com.visualspider.identity.domain.ActorId;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/** 内部测试 fake：实现 ticker 需要的 snapshotActive，足以做生命周期边界验证。 */
final class FakeSessionManager {
    final Map<String, ActiveMeta> open = new ConcurrentHashMap<>();
    final List<String> closed = new CopyOnWriteArrayList<>();

    ActiveMeta addOpenSession(String sessionId, ActorId owner, long taskId,
                              Instant openedAt, Instant lastActivityAt) {
        ActiveMeta meta = new ActiveMeta(sessionId, owner, taskId, openedAt,
                new AtomicReference<>(lastActivityAt));
        open.put(sessionId, meta);
        return meta;
    }

    void refreshActivity(String sessionId, Instant newActivity) {
        ActiveMeta meta = open.get(sessionId);
        if (meta != null) {
            meta.lastActivityAt.set(newActivity);
        }
    }

    void close(String sessionId) {
        ActiveMeta removed = open.remove(sessionId);
        if (removed != null) {
            closed.add(sessionId);
        }
    }

    void closeFromSnapshot(SessionLifecycleTicker.ActiveSnapshot snapshot, String reason) {
        close(snapshot.sessionId());
    }

    List<SessionLifecycleTicker.ActiveSnapshot> snapshotActive() {
        return open.values().stream()
                .map(m -> new SessionLifecycleTicker.ActiveSnapshot() {
                    @Override public String sessionId() { return m.sessionId; }
                    @Override public ActorId owner() { return m.owner; }
                    @Override public long taskId() { return m.taskId; }
                    @Override public Instant openedAt() { return m.openedAt; }
                    @Override public Instant lastActivityAt() { return m.lastActivityAt.get(); }
                })
                .collect(Collectors.toList());
    }

    static final class ActiveMeta {
        final String sessionId;
        final ActorId owner;
        final long taskId;
        final Instant openedAt;
        final AtomicReference<Instant> lastActivityAt;
        ActiveMeta(String id, ActorId o, long t, Instant opened, AtomicReference<Instant> lastRef) {
            this.sessionId = id;
            this.owner = o;
            this.taskId = t;
            this.openedAt = opened;
            this.lastActivityAt = lastRef;
        }
    }
}
