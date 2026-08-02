package com.visualspider.run.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.visualspider.identity.domain.ActorId;
import com.visualspider.identity.domain.ActorRole;
import com.visualspider.identity.spi.IdentityAccess;
import com.visualspider.run.spi.Page;
import com.visualspider.run.spi.RunDetail;
import com.visualspider.run.spi.RunFilter;
import com.visualspider.run.spi.RunProgress;
import com.visualspider.run.spi.RunState;
import com.visualspider.run.spi.RunSummary;
import com.visualspider.run.spi.StopReason;
import com.visualspider.task.domain.FieldDefinition;
import com.visualspider.task.domain.FieldSource;
import com.visualspider.task.domain.ReadinessReport;
import com.visualspider.task.domain.ResultType;
import com.visualspider.task.domain.SelectorType;
import com.visualspider.task.domain.TaskDefinition;
import com.visualspider.task.domain.TaskDraft;
import com.visualspider.task.domain.TaskMode;
import com.visualspider.task.domain.TaskSnapshot;
import com.visualspider.task.domain.TaskStatus;
import com.visualspider.task.domain.TrimPolicy;
import com.visualspider.task.domain.Viewport;
import com.visualspider.task.spi.TaskCatalog;
import com.visualspider.task.spi.TaskReadiness;
import com.visualspider.task.spi.TaskSnapshotFactory;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.security.access.AccessDeniedException;

/**
 * {@link RunCoordinatorImpl} 单元测试（不依赖 PG / 真实 Chromium；全 fake）。
 *
 * <p>覆盖（M3 spec §D2 / ADR-0006）：
 * <ul>
 *   <li>start：READY → WAITING；写入 snapshot</li>
 *   <li>start：非 READY 任务拒 {@code TaskNotReadyException}</li>
 *   <li>start：每用户第 2 个 W+R 拒 {@code UserRunLimitException}</li>
 *   <li>start：非 owner / 非 admin 拒 {@code AccessDeniedException}</li>
 *   <li>start：in-JVM 锁串行（两线程并发 start 同一用户，恰好 1 成功 1 拒）</li>
 *   <li>cancel：终态 run 拒 {@code RunNotCancellableException}</li>
 *   <li>cancel：非 owner 拒 {@code RunNotOwnerException}</li>
 *   <li>get / list / progress：admin 全局；其它 owner-only</li>
 * </ul>
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class RunCoordinatorImplTest {

    private InMemoryRunRepository repository;
    private FakeTaskCatalog taskCatalog;
    private FakeTaskReadiness taskReadiness;
    private FakeTaskSnapshotFactory snapshotFactory;
    private FakeIdentityAccess identityAccess;
    private RecordingDispatcher dispatcher;
    private RunCoordinatorImpl coordinator;

    @BeforeEach
    void setUp() {
        repository = new InMemoryRunRepository();
        taskCatalog = new FakeTaskCatalog();
        taskReadiness = new FakeTaskReadiness();
        snapshotFactory = new FakeTaskSnapshotFactory(taskCatalog);
        identityAccess = new FakeIdentityAccess();
        dispatcher = new RecordingDispatcher();
        coordinator = new RunCoordinatorImpl(repository, taskCatalog, taskReadiness,
                snapshotFactory, identityAccess, () -> dispatcher.scheduleDispatch());
    }

    // ---------- start ----------

    @Test
    @DisplayName("start：READY 任务 + owner → 创建 WAITING run；快照写入")
    void startCreatesWaitingRun() {
        long taskId = taskCatalog.putReady(1L, "demo");

        RunSummary summary = coordinator.start(taskId, new ActorId(1L));

        assertThat(summary.status()).isEqualTo(RunState.WAITING);
        assertThat(summary.taskId()).isEqualTo(taskId);
        assertThat(summary.ownerId()).isEqualTo(1L);
        assertThat(summary.runId()).isPositive();
        // dispatcher 应被通知一次
        assertThat(dispatcher.scheduleCount.get()).isEqualTo(1);
        // repository 写入 WAITING 记录
        RunRepository.RunRecord rec = repository.byId(summary.runId());
        assertThat(rec.status()).isEqualTo(RunState.WAITING);
        assertThat(rec.snapshot().taskId()).isEqualTo(taskId);
    }

    @Test
    @DisplayName("start：非 READY 任务（仅 DRAFT）拒 TaskNotReadyException")
    void startRejectsNonReadyTask() {
        long taskId = taskCatalog.putDraft(1L, "draft");
        // readiness 默认 success → 但 task.status != READY 仍要拒
        assertThatThrownBy(() -> coordinator.start(taskId, new ActorId(1L)))
                .isInstanceOf(TaskNotReadyException.class);
        // 没有写入 run
        assertThat(repository.size()).isZero();
    }

    @Test
    @DisplayName("start：readiness validateForRun 返回非 ready → TaskNotReadyException")
    void startRejectsWhenReadinessFails() {
        long taskId = taskCatalog.putReady(1L, "broken");
        taskReadiness.invalid = true;
        assertThatThrownBy(() -> coordinator.start(taskId, new ActorId(1L)))
                .isInstanceOf(TaskNotReadyException.class);
    }

    @Test
    @DisplayName("start：每用户已有 W+R 时拒 UserRunLimitException")
    void startRejectsUserRunLimit() {
        long taskId1 = taskCatalog.putReady(1L, "first");
        long taskId2 = taskCatalog.putReady(1L, "second");
        // 第一个 start：WAITING
        coordinator.start(taskId1, new ActorId(1L));
        // 第二个 start：同用户 → 拒
        assertThatThrownBy(() -> coordinator.start(taskId2, new ActorId(1L)))
                .isInstanceOf(UserRunLimitException.class);
    }

    @Test
    @DisplayName("start：非 owner 拒 AccessDeniedException")
    void startRejectsNonOwner() {
        long taskId = taskCatalog.putReady(99L, "other");
        assertThatThrownBy(() -> coordinator.start(taskId, new ActorId(2L)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("start：admin 可访问任意 owner 的任务")
    void startAdminCanAccessAnyTask() {
        long taskId = taskCatalog.putReady(99L, "other");
        RunSummary summary = coordinator.start(taskId, new ActorId(1L));
        assertThat(summary.ownerId()).isEqualTo(99L);
    }

    @Test
    @DisplayName("start：in-JVM 锁串行（两线程并发同用户 start，恰好 1 成功 1 拒）")
    void startSerializesOnInJvmLock() throws Exception {
        long taskId1 = taskCatalog.putReady(1L, "first");
        long taskId2 = taskCatalog.putReady(1L, "second");
        // 在 count-check 之前 latch：阻塞第一个线程的 validateForRun，使第二个线程真正并发到达 in-JVM 锁。
        CountDownLatch insideLock = new CountDownLatch(1);
        CountDownLatch arrived = new CountDownLatch(1);
        taskReadiness.beforeReturn = () -> {
            arrived.countDown();
            try {
                insideLock.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> f1 = pool.submit(() -> coordinator.start(taskId1, new ActorId(1L)));
            // 等第一个线程进入 validateForRun 才发起第二个
            assertThat(arrived.await(5, TimeUnit.SECONDS)).isTrue();
            Future<?> f2 = pool.submit(() -> coordinator.start(taskId2, new ActorId(1L)));
            // 让两个线程在锁上对齐：第一个仍在 validateForRun，第二个在 startLock 上等
            Thread.sleep(100);
            insideLock.countDown();

            Object r1 = f1.get(5, TimeUnit.SECONDS);
            Throwable err2 = null;
            try {
                f2.get(5, TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException e) {
                err2 = e.getCause();
            }
            assertThat(r1).isNotNull();
            // 第二个必须抛 UserRunLimitException
            assertThat(err2).isInstanceOf(UserRunLimitException.class);
            assertThat(repository.size()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("start：任务不存在抛 TaskNotFoundException")
    void startRejectsMissingTask() {
        assertThatThrownBy(() -> coordinator.start(9999L, new ActorId(1L)))
                .isInstanceOf(com.visualspider.task.domain.exceptions.TaskNotFoundException.class);
    }

    // ---------- cancel ----------

    @Test
    @DisplayName("cancel：WAITING run 直接翻 CANCELLED + USER_CANCEL（spec §D10 迁移图）")
    void cancelWaitingFlipsTerminal() {
        long taskId = taskCatalog.putReady(1L, "demo");
        RunSummary created = coordinator.start(taskId, new ActorId(1L));

        coordinator.cancel(created.runId(), new ActorId(1L));

        RunRepository.RunRecord rec = repository.byId(created.runId());
        assertThat(rec.status()).isEqualTo(RunState.CANCELLED);
        assertThat(rec.stopReason()).isEqualTo(StopReason.USER_CANCEL);
        assertThat(rec.finishedAt()).isNotNull();
    }

    @Test
    @DisplayName("cancel：终态 run 拒 RunNotCancellableException")
    void cancelTerminalRunRejected() {
        long taskId = taskCatalog.putReady(1L, "demo");
        RunSummary created = coordinator.start(taskId, new ActorId(1L));
        // 手动翻终态
        repository.markTerminal(created.runId(), RunState.SUCCESS, StopReason.COMPLETED);

        assertThatThrownBy(() -> coordinator.cancel(created.runId(), new ActorId(1L)))
                .isInstanceOf(RunNotCancellableException.class);
    }

    @Test
    @DisplayName("cancel：非 owner 拒 RunNotOwnerException")
    void cancelByNonOwnerRejected() {
        long taskId = taskCatalog.putReady(99L, "demo");
        RunSummary created = coordinator.start(taskId, new ActorId(1L)); // admin 启动（run owner=99）
        // 另一个 collector 试图 cancel：non-admin + non-owner → RunNotOwnerException（语义明确，
        // 不回显"不存在"，与 get 的 RunNotFoundException 不同——因为 cancel 是变更意图）
        identityAccess.setAdmin(false);
        assertThatThrownBy(() -> coordinator.cancel(created.runId(), new ActorId(2L)))
                .isInstanceOf(RunNotOwnerException.class);
    }

    @Test
    @DisplayName("cancel：不存在的 run 拒 RunNotFoundException")
    void cancelMissingRunRejected() {
        assertThatThrownBy(() -> coordinator.cancel(9999L, new ActorId(1L)))
                .isInstanceOf(RunNotFoundException.class);
    }

    // ---------- get ----------

    @Test
    @DisplayName("get：owner 可读；admin 可读任意 run；其它 collector 拒 RunNotFoundException")
    void getOwnerOnlyExceptAdmin() {
        long taskId = taskCatalog.putReady(99L, "demo");
        RunSummary created = coordinator.start(taskId, new ActorId(1L)); // admin 创建
        identityAccess.setAdmin(false);

        // owner (99) 可读
        RunDetail detail = coordinator.get(created.runId(), new ActorId(99L));
        assertThat(detail.runId()).isEqualTo(created.runId());
        assertThat(detail.status()).isEqualTo(RunState.WAITING);

        // 其它 collector (2) → RunNotFoundException（不回显存在性）
        assertThatThrownBy(() -> coordinator.get(created.runId(), new ActorId(2L)))
                .isInstanceOf(RunNotFoundException.class);
    }

    // ---------- list ----------

    @Test
    @DisplayName("list：admin 全局；collector 仅自己")
    void listAdminGlobalOrOwnerOnly() {
        // 三条 run：3 个不同 owner（避免每用户 W+R≤1 限制）
        long t1 = taskCatalog.putReady(1L, "a");
        long t2 = taskCatalog.putReady(2L, "b");
        long t3 = taskCatalog.putReady(3L, "c");
        coordinator.start(t1, new ActorId(1L));
        coordinator.start(t2, new ActorId(2L));
        coordinator.start(t3, new ActorId(3L));

        // admin
        identityAccess.setAdmin(true);
        Page<RunSummary> adminPage = coordinator.list(new ActorId(99L),
                new RunFilter(null, 0, 50));
        assertThat(adminPage.items()).hasSize(3);
        assertThat(adminPage.total()).isEqualTo(3);

        // collector 1
        identityAccess.setAdmin(false);
        Page<RunSummary> c1 = coordinator.list(new ActorId(1L),
                new RunFilter(null, 0, 50));
        assertThat(c1.items()).hasSize(1);
        assertThat(c1.total()).isEqualTo(1);

        // collector 2
        Page<RunSummary> c2 = coordinator.list(new ActorId(2L),
                new RunFilter(null, 0, 50));
        assertThat(c2.items()).hasSize(1);
        assertThat(c2.total()).isEqualTo(1);
    }

    // ---------- progress ----------

    @Test
    @DisplayName("progress：owner 可读；其它拒 RunNotFoundException")
    void progressOwnerOnly() {
        long taskId = taskCatalog.putReady(1L, "demo");
        RunSummary created = coordinator.start(taskId, new ActorId(1L));
        identityAccess.setAdmin(false);

        RunProgress p = coordinator.progress(created.runId(), new ActorId(1L));
        assertThat(p.status()).isEqualTo(RunState.WAITING);

        assertThatThrownBy(() -> coordinator.progress(created.runId(), new ActorId(2L)))
                .isInstanceOf(RunNotFoundException.class);
    }

    // ---------- fakes ----------

    /** in-memory run repository（最小实现）。 */
    private static final class InMemoryRunRepository implements RunRepository {
        private final java.util.Map<Long, RunRecord> byId = new java.util.HashMap<>();
        private long seq = 1;

        @Override
        public int countActiveByOwner(long ownerId) {
            int n = 0;
            for (RunRecord r : byId.values()) {
                if (r.ownerId() == ownerId
                        && (r.status() == RunState.WAITING || r.status() == RunState.RUNNING)) {
                    n++;
                }
            }
            return n;
        }

        @Override
        public long insertWaiting(long taskId, long ownerId, TaskSnapshot snapshot) {
            long id = seq++;
            RunRecord r = new RunRecord(id, taskId, ownerId, RunState.WAITING, null,
                    false, 0, 0, 0, snapshot,
                    OffsetDateTime.now(), null, null);
            byId.put(id, r);
            return id;
        }

        RunRecord byId(long id) {
            return byId.get(id);
        }

        int size() {
            return byId.size();
        }

        @Override
        public Optional<RunRecord> findById(long runId) {
            return Optional.ofNullable(byId.get(runId));
        }

        @Override
        public Optional<RunRecord> claimOldestWaiting() {
            RunRecord oldest = null;
            for (RunRecord r : byId.values()) {
                if (r.status() != RunState.WAITING) {
                    continue;
                }
                if (oldest == null || r.createdAt().isBefore(oldest.createdAt())) {
                    oldest = r;
                }
            }
            if (oldest == null) {
                return Optional.empty();
            }
            RunRecord flipped = new RunRecord(oldest.runId(), oldest.taskId(), oldest.ownerId(),
                    RunState.RUNNING, null, false, 0, 0, 0,
                    oldest.snapshot(), oldest.createdAt(), OffsetDateTime.now(), null);
            byId.put(oldest.runId(), flipped);
            return Optional.of(flipped);
        }

        @Override
        public boolean markCancelRequested(long runId) {
            RunRecord r = byId.get(runId);
            if (r == null) {
                return false;
            }
            byId.put(runId, new RunRecord(r.runId(), r.taskId(), r.ownerId(), r.status(),
                    r.stopReason(), true, r.pageCount(), r.recordCountFinal(), r.failCount(),
                    r.snapshot(), r.createdAt(), r.startedAt(), r.finishedAt()));
            return true;
        }

        @Override
        public int markCancelledIfWaiting(long runId) {
            RunRecord r = byId.get(runId);
            if (r == null || r.status() != RunState.WAITING) {
                return 0;
            }
            byId.put(runId, new RunRecord(r.runId(), r.taskId(), r.ownerId(),
                    RunState.CANCELLED, StopReason.USER_CANCEL, r.cancelRequested(),
                    r.pageCount(), r.recordCountFinal(), r.failCount(),
                    r.snapshot(), r.createdAt(), r.startedAt(), OffsetDateTime.now()));
            return 1;
        }

        @Override
        public boolean markTerminal(long runId, RunState status, StopReason stopReason) {
            RunRecord r = byId.get(runId);
            if (r == null) {
                return false;
            }
            byId.put(runId, new RunRecord(r.runId(), r.taskId(), r.ownerId(),
                    status, stopReason, r.cancelRequested(),
                    r.pageCount(), r.recordCountFinal(), r.failCount(),
                    r.snapshot(), r.createdAt(), r.startedAt(), OffsetDateTime.now()));
            return true;
        }

        @Override
        public int markAllActiveInterrupted() {
            int n = 0;
            List<RunRecord> snapshot = new java.util.ArrayList<>(byId.values());
            for (RunRecord r : snapshot) {
                if (r.status() == RunState.WAITING || r.status() == RunState.RUNNING) {
                    byId.put(r.runId(), new RunRecord(r.runId(), r.taskId(), r.ownerId(),
                            RunState.INTERRUPTED, StopReason.APP_INTERRUPTED, r.cancelRequested(),
                            r.pageCount(), r.recordCountFinal(), r.failCount(),
                            r.snapshot(), r.createdAt(), r.startedAt(), OffsetDateTime.now()));
                    n++;
                }
            }
            return n;
        }

        @Override
        public List<RunSummary> listByOwner(Long ownerId, RunFilter filter) {
            return byId.values().stream()
                    .filter(r -> ownerId == null || r.ownerId() == ownerId)
                    .filter(r -> filter == null || filter.status() == null || r.status() == filter.status())
                    .sorted(java.util.Comparator.comparing(RunRepository.RunRecord::createdAt))
                    .map(r -> new RunSummary(r.runId(), r.taskId(), r.ownerId(), r.status(),
                            r.stopReason(), r.cancelRequested(), r.pageCount(),
                            r.recordCountFinal(), r.failCount(),
                            r.createdAt(), r.startedAt(), r.finishedAt()))
                    .collect(java.util.stream.Collectors.toList());
        }

        @Override
        public Page<RunSummary> pageByOwner(Long ownerId, RunFilter filter) {
            List<RunSummary> all = listByOwner(ownerId, filter);
            int page = filter == null ? 0 : Math.max(0, filter.page());
            int size = filter == null ? 50 : Math.max(1, filter.size());
            int from = Math.min(page * size, all.size());
            int to = Math.min(from + size, all.size());
            return new Page<>(all.subList(from, to), all.size(), page, size);
        }

        @Override
        public Optional<RunProgress> loadProgress(long runId) {
            RunRecord r = byId.get(runId);
            if (r == null) {
                return Optional.empty();
            }
            return Optional.of(new RunProgress(r.status(), r.stopReason(),
                    null, null, r.pageCount(), r.pageCount(), r.recordCountFinal(), r.failCount(),
                    null,  // listItemMatchCount
                    r.startedAt() == null ? 0
                            : java.time.Duration.between(r.startedAt().toInstant(),
                                    OffsetDateTime.now().toInstant()).toMillis()));
        }

        @Override
        public Optional<RunDetail> loadDetail(long runId) {
            RunRecord r = byId.get(runId);
            if (r == null) {
                return Optional.empty();
            }
            TaskSnapshot s = r.snapshot();
            return Optional.of(new RunDetail(r.runId(), r.taskId(), r.ownerId(), r.status(),
                    r.stopReason(), r.cancelRequested(), r.pageCount(),
                    r.pageCount(), r.pageCount(), r.recordCountFinal(),
                    r.failCount(), null, null,
                    r.createdAt(), r.startedAt(), r.finishedAt(),
                    new RunDetail.TaskSnapshotMeta(s.name(), s.mode(), s.schemaVersion(),
                            s.version(), s.definition())));
        }
    }

    /** fake TaskCatalog：内存任务表 + owner/admin 校验（admin 模式切换由测试控制）。 */
    private static final class FakeTaskCatalog implements TaskCatalog {
        private final java.util.Map<Long, TaskDraft> tasks = new java.util.HashMap<>();
        private long seq = 1;

        long putReady(long ownerId, String name) {
            return put(ownerId, name, TaskStatus.READY);
        }

        long putDraft(long ownerId, String name) {
            return put(ownerId, name, TaskStatus.DRAFT);
        }

        private long put(long ownerId, String name, TaskStatus status) {
            long id = seq++;
            tasks.put(id, new TaskDraft(id, ownerId, name, new TaskMode.SinglePage(), status,
                    1, 1L, singlePageDef(), OffsetDateTime.now()));
            return id;
        }

        private static TaskDefinition singlePageDef() {
            return new TaskDefinition(1, new TaskMode.SinglePage(), "https://example.com",
                    Viewport.DEFAULT, null, List.of(new FieldDefinition("title",
                            FieldSource.VISIBLE_TEXT, "h1", null, SelectorType.CSS,
                            ResultType.TEXT, TrimPolicy.TRIM, null, true)));
        }

        @Override
        public long createDraft(TaskDefinition draft, String name, ActorId actor) {
            long id = seq++;
            tasks.put(id, new TaskDraft(id, actor.value(), name, draft.mode(), TaskStatus.DRAFT,
                    1, 1L, draft, OffsetDateTime.now()));
            return id;
        }

        @Override
        public List<com.visualspider.task.domain.TaskSummary> listMine(ActorId actor) {
            return tasks.values().stream()
                    .filter(t -> t.ownerId() == actor.value())
                    .map(t -> new com.visualspider.task.domain.TaskSummary(t.id(), t.name(),
                            t.mode(), t.status(), t.version(), t.updatedAt()))
                    .collect(java.util.stream.Collectors.toList());
        }

        @Override
        public TaskDraft read(long taskId, ActorId actor) {
            TaskDraft t = tasks.get(taskId);
            if (t == null) {
                throw new com.visualspider.task.domain.exceptions.TaskNotFoundException(taskId);
            }
            // fake admin 校验：actor.value() == 1 → admin；其它 collector 必须匹配 owner
            boolean isAdmin = actor.value() == 1L;
            if (!isAdmin && t.ownerId() != actor.value()) {
                throw new AccessDeniedException("无权访问该任务");
            }
            return t;
        }

        @Override
        public TaskDraft saveDraft(long taskId, TaskDefinition draft, long expectedVersion, ActorId actor) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(long taskId, ActorId actor) {
            throw new UnsupportedOperationException();
        }
    }

    /** fake TaskReadiness：默认 success；可注入 fail。 */
    private static final class FakeTaskReadiness implements TaskReadiness {
        boolean invalid;
        Runnable beforeReturn;

        @Override
        public ReadinessReport validate(TaskDefinition draft) {
            if (beforeReturn != null) {
                beforeReturn.run();
            }
            return invalid ? ReadinessReport.failure(List.of()) : ReadinessReport.success();
        }

        @Override
        public ReadinessReport validateForRun(long taskId, ActorId actor) {
            if (beforeReturn != null) {
                beforeReturn.run();
            }
            return invalid ? ReadinessReport.failure(List.of()) : ReadinessReport.success();
        }
    }

    /** fake TaskSnapshotFactory：从 TaskCatalog 读 draft，构造 TaskSnapshot。 */
    private static final class FakeTaskSnapshotFactory implements TaskSnapshotFactory {
        private final TaskCatalog catalog;

        FakeTaskSnapshotFactory() {
            this(null);
        }

        FakeTaskSnapshotFactory(TaskCatalog catalog) {
            this.catalog = catalog;
        }

        @Override
        public TaskSnapshot snapshot(long taskId, ActorId actor) {
            if (catalog == null) {
                throw new IllegalStateException("catalog 未注入");
            }
            TaskDraft d = catalog.read(taskId, actor);
            return new TaskSnapshot(d.id(), d.ownerId(), d.name(), d.mode(), d.schemaVersion(),
                    d.version(), d.definition());
        }
    }

    /** fake IdentityAccess：默认 admin=true；可切换；按 actor id 决定 owner / admin。 */
    private static final class FakeIdentityAccess implements IdentityAccess {
        private boolean admin = true;

        void setAdmin(boolean admin) {
            this.admin = admin;
        }

        @Override
        public ActorId currentActor() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ActorRole currentRole() {
            return admin ? new ActorRole.Admin() : new ActorRole.Collector();
        }

        @Override
        public boolean isAdmin() {
            return admin;
        }

        @Override
        public boolean canAccessTask(long taskOwnerId, ActorId actor) {
            // admin 全局；其它要求 owner 匹配
            return admin || taskOwnerId == actor.value();
        }

        @Override
        public boolean canAccessUser(long targetUserId, ActorId actor) {
            return admin || targetUserId == actor.value();
        }

        @Override
        public boolean canRunAnyTask() {
            return admin;
        }
    }

    /** fake dispatcher：只记录 schedule 次数，不真正执行。 */
    private static final class RecordingDispatcher {
        final AtomicInteger scheduleCount = new AtomicInteger(0);

        void scheduleDispatch() {
            scheduleCount.incrementAndGet();
        }
    }
}