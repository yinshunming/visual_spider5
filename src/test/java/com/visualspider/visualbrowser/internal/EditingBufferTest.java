package com.visualspider.visualbrowser.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.visualspider.identity.domain.ActorId;
import com.visualspider.shared.time.MutableClock;
import com.visualspider.task.domain.TaskDefinition;
import com.visualspider.task.domain.TaskDraft;
import com.visualspider.task.domain.TaskMode.SinglePage;
import com.visualspider.task.domain.TaskStatus;
import com.visualspider.task.domain.Viewport;
import com.visualspider.task.spi.TaskCatalog;
import com.visualspider.task.domain.exceptions.StaleTaskVersionException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EditingBufferTest {

    private TaskDraft currentDraft;
    private final AtomicLong version = new AtomicLong(5L);
    private ScheduledExecutorService scheduler;
    private MutableClock clock;
    private EditingBuffer buffer;
    private FakeTaskCatalog catalog;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-07-23T10:00:00Z"));
        scheduler = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "edit-save");
            t.setDaemon(true);
            return t;
        });
        version.set(5L);
        currentDraft = new TaskDraft(11L, 1L, "demo", new SinglePage(),
                TaskStatus.DRAFT, 1, version.get(),
                new TaskDefinition(1, new SinglePage(), "http://example.com/",
                        Viewport.DEFAULT, List.of()),
                OffsetDateTime.now());
        catalog = new FakeTaskCatalog(currentDraft);
        buffer = new EditingBuffer(catalog, clock, scheduler, 60L, "build/test-logs");
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    @Test
    void getOrCreateReturnsInitialBuffer() {
        EditingBuffer.Buffer b = buffer.getOrCreate("s1", 11L, actor(1));
        assertThat(b.baseVersion).isEqualTo(5L);
        assertThat(b.current.get().startUrl()).isEqualTo("http://example.com/");
    }

    @Test
    void updatePersistsSynchronouslyOnFlush() {
        EditingBuffer.Buffer b = buffer.getOrCreate("s1", 11L, actor(1));
        buffer.update("s1", actor(1), withStartUrl(b.current.get(), "http://example.com/page2"));
        assertThat(catalog.savesAttempted.get()).isZero();
        buffer.flushSync("s1", actor(1));
        assertThat(catalog.savesAttempted.get()).isEqualTo(1);
    }

    @Test
    void optimisticLockConflictTriggersRetry() {
        // 第一次 flushSync 触发 retry：因为 onceFailAt=5L 匹配 baseVersion，second attempt 成功
        catalog.failNextOnce(5L);
        EditingBuffer.Buffer b = buffer.getOrCreate("s1", 11L, actor(1));
        buffer.update("s1", actor(1), withStartUrl(b.current.get(), "http://example.com/page2"));
        buffer.flushSync("s1", actor(1));
        assertThat(catalog.savesAttempted.get()).isEqualTo(2);
    }

    @Test
    void doubleOptimisticConflictRaisesBusinessException() {
        // 两次抛 stale → retry 仍 stale → 抛 EditBufferConflictException
        catalog.alwaysFailStale();
        EditingBuffer.Buffer b = buffer.getOrCreate("s1", 11L, actor(1));
        buffer.update("s1", actor(1), withStartUrl(b.current.get(), "http://example.com/page2"));
        assertThatThrownBy(() -> buffer.flushSync("s1", actor(1)))
                .isInstanceOf(EditingBuffer.EditBufferConflictException.class);
        assertThat(catalog.savesAttempted.get()).isEqualTo(2);
    }

    private static ActorId actor(long id) {
        return new ActorId(id);
    }

    private static TaskDefinition withStartUrl(TaskDefinition original, String url) {
        return new TaskDefinition(original.schemaVersion(), original.mode(), url,
                original.viewport(), original.fields());
    }

    /**
     * 仿造 {@link TaskCatalog}：将 saveDraft 透传到 {@link #savesAttempted} 与
     * {@link #nextSaveResult}；读取始终返回初始 draft。
     */
    private static final class FakeTaskCatalog implements TaskCatalog {
        private final TaskDraft draft;
        final AtomicLong savesAttempted = new AtomicLong();
        java.util.function.BiPredicate<Long, TaskDefinition> predicate;
        boolean alwaysStale;
        Long onceFailAt;

        FakeTaskCatalog(TaskDraft draft) {
            this.draft = draft;
        }

        void failNextOnce(long expectedVersion) {
            onceFailAt = expectedVersion;
        }

        void alwaysFailStale() {
            alwaysStale = true;
        }

        @Override
        public TaskDraft read(long taskId, ActorId actor) {
            return draft;
        }

        @Override
        public TaskDraft saveDraft(long taskId, TaskDefinition draft, long expectedVersion, ActorId actor) {
            savesAttempted.incrementAndGet();
            if (alwaysStale) {
                throw new StaleTaskVersionException(taskId, expectedVersion, expectedVersion + 1);
            }
            if (onceFailAt != null && onceFailAt == expectedVersion) {
                onceFailAt = null;
                throw new StaleTaskVersionException(taskId, expectedVersion, expectedVersion + 1);
            }
            return new TaskDraft(taskId, draft == null ? -1 : draft.schemaVersion(),
                    Long.toString(taskId), new SinglePage(), TaskStatus.DRAFT,
                    draft == null ? 1 : draft.schemaVersion(),
                    expectedVersion + 1, draft == null
                            ? new TaskDefinition(1, new SinglePage(), "http://example.com/", Viewport.DEFAULT, List.of())
                            : draft, OffsetDateTime.now());
        }

        @Override public long createDraft(TaskDefinition d, String n, ActorId a) { throw new UnsupportedOperationException(); }
        @Override public java.util.List<com.visualspider.task.domain.TaskSummary> listMine(ActorId actor) { throw new UnsupportedOperationException(); }
        @Override public void delete(long taskId, ActorId actor) { throw new UnsupportedOperationException(); }
    }
}
