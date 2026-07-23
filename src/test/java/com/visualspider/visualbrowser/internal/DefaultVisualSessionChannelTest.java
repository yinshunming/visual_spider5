package com.visualspider.visualbrowser.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.visualspider.identity.domain.ActorId;
import com.visualspider.visualbrowser.InputCommand;
import com.visualspider.visualbrowser.spi.SessionLifecycleState;
import com.visualspider.visualbrowser.spi.VisualSession;
import com.visualspider.visualbrowser.spi.VisualSessionChannel;
import com.visualspider.visualbrowser.spi.VisualSessionManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DefaultVisualSessionChannelTest {

    private FakeManager manager;
    private RecordingExecutor executor;
    private VisualSessionChannel channel;

    @BeforeEach
    void setUp() {
        manager = new FakeManager();
        executor = new RecordingExecutor();
        channel = new DefaultVisualSessionChannel(manager, executor);
    }

    @Test
    void rejectsCommandForOtherActor() {
        VisualSession session = manager.addAndGet(actor(1), 11L);
        InputCommand cmd = new InputCommand(session.sessionId(), 1L, 1280, 720,
                InputCommand.TYPE_CLICK, 10, 10, null, null, null, null);
        assertThatThrownBy(() -> channel.handleCommand(session.sessionId(), cmd, actor(2)))
                .isInstanceOf(VisualSessionNotOwnerException.class);
        assertThat(executor.calls.get()).isZero();
    }

    @Test
    void rejectsCommandWithSessionIdMismatch() {
        VisualSession session = manager.addAndGet(actor(1), 11L);
        InputCommand cmd = new InputCommand("not-mine", 1L, 1280, 720,
                InputCommand.TYPE_CLICK, 10, 10, null, null, null, null);
        assertThatThrownBy(() -> channel.handleCommand(session.sessionId(), cmd, actor(1)))
                .isInstanceOf(VisualSessionNotOwnerException.class);
    }

    @Test
    void rejectsCommandForClosedSession() {
        VisualSession session = manager.addAndGet(actor(1), 11L);
        manager.close(session.sessionId(), actor(1), "USER_CLOSE");
        InputCommand cmd = new InputCommand(session.sessionId(), 1L, 1280, 720,
                InputCommand.TYPE_CLICK, 10, 10, null, null, null, null);
        assertThatThrownBy(() -> channel.handleCommand(session.sessionId(), cmd, actor(1)))
                .isInstanceOf(VisualSessionNotFoundException.class);
    }

    @Test
    void dispatchesAcceptedCommandAndRefreshesHeartbeat() {
        VisualSession session = manager.addAndGet(actor(1), 11L);
        executor.accepted = true;
        InputCommand cmd = new InputCommand(session.sessionId(), 1L, 1280, 720,
                InputCommand.TYPE_CLICK, 10, 10, null, null, null, null);
        channel.handleCommand(session.sessionId(), cmd, actor(1));
        assertThat(executor.calls.get()).isEqualTo(1);
        assertThat(manager.heartbeatCalls.get()).isEqualTo(1);
    }

    @Test
    void doesNotRefreshActivityWhenExecutorRejectsCommand() {
        VisualSession session = manager.addAndGet(actor(1), 11L);
        executor.accepted = false;
        InputCommand cmd = new InputCommand(session.sessionId(), 1L, 1280, 720,
                InputCommand.TYPE_CLICK, 10, 10, null, null, null, null);
        channel.handleCommand(session.sessionId(), cmd, actor(1));
        assertThat(executor.calls.get()).isEqualTo(1);
        assertThat(manager.heartbeatCalls.get()).isZero();
    }

    private static ActorId actor(long id) {
        return new ActorId(id);
    }

    private static final class FakeManager implements VisualSessionManager {
        final HashMap<String, VisualSession> sessions = new HashMap<>();
        final HashMap<String, ActorId> owners = new HashMap<>();
        final AtomicInteger heartbeatCalls = new AtomicInteger();

        VisualSession addAndGet(ActorId owner, long taskId) {
            String id = UUID.randomUUID().toString();
            Instant now = Instant.parse("2026-07-23T10:00:00Z");
            VisualSession session = new VisualSession(id, taskId, owner, now, now,
                    SessionLifecycleState.ACTIVE);
            sessions.put(id, session);
            owners.put(id, owner);
            return session;
        }

        @Override
        public VisualSession open(long taskId, ActorId actor) {
            return addAndGet(actor, taskId);
        }

        @Override
        public Optional<VisualSession> find(long taskId, ActorId actor) {
            return sessions.values().stream().filter(s -> s.taskId() == taskId).findFirst();
        }

        @Override
        public Optional<VisualSession> findBySessionId(String sessionId) {
            return Optional.ofNullable(sessions.get(sessionId));
        }

        @Override
        public VisualSession requireOwnedBy(String sessionId, ActorId actor) {
            VisualSession session = sessions.get(sessionId);
            if (session == null) {
                throw new VisualSessionNotFoundException(sessionId);
            }
            ActorId owner = owners.get(sessionId);
            if (owner == null || !owner.equals(actor)) {
                throw new VisualSessionNotOwnerException(sessionId);
            }
            return session;
        }

        @Override
        public void heartbeat(String sessionId, ActorId actor) {
            heartbeatCalls.incrementAndGet();
        }

        @Override
        public void close(String sessionId, ActorId actor, String reason) {
            VisualSession session = sessions.get(sessionId);
            if (session != null) {
                sessions.put(sessionId, session.withLifecycle(SessionLifecycleState.CLOSED));
            }
        }

        @Override
        public int activeCount() {
            return sessions.size();
        }

        @Override
        public List<SessionLifecycleTicker.ActiveSnapshot> snapshotActive() {
            List<SessionLifecycleTicker.ActiveSnapshot> list = new ArrayList<>();
            for (VisualSession s : sessions.values()) {
                list.add(new SessionLifecycleTicker.ActiveSnapshot() {
                    @Override public String sessionId() { return s.sessionId(); }
                    @Override public ActorId owner() { return s.owner(); }
                    @Override public long taskId() { return s.taskId(); }
                    @Override public Instant openedAt() { return s.openedAt(); }
                    @Override public Instant lastActivityAt() { return s.lastActivityAt(); }
                });
            }
            return list;
        }
    }

    private static final class RecordingExecutor implements DefaultVisualSessionChannel.CommandExecutor {
        final AtomicInteger calls = new AtomicInteger();
        boolean accepted = true;

        @Override
        public boolean execute(String sessionId, InputCommand command) {
            calls.incrementAndGet();
            return accepted;
        }
    }
}
