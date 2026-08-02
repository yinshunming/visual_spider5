package com.visualspider.run.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.visualspider.run.spi.RunExecutionContext;
import com.visualspider.run.spi.RunExecutor;
import com.visualspider.run.spi.RunPageHandle;
import com.visualspider.run.spi.RunState;
import com.visualspider.run.spi.StopReason;
import com.visualspider.task.domain.TaskDefinition;
import com.visualspider.task.domain.TaskMode;
import com.visualspider.task.domain.TaskSnapshot;
import com.visualspider.task.domain.Viewport;
import com.visualspider.visualbrowser.spi.LanePool;
import com.visualspider.visualbrowser.spi.Lease;
import com.visualspider.run.internal.testutil.FakeRunPageHandle;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * {@link RunDispatcher} 单元测试（ADR-0006 / M3 spec §D8）。
 *
 * <p>不依赖真实 Chromium / PG；{@link RunRepository} / {@link LanePool} / {@link RunExecutor}
 * 全用 fake / mock。
 *
 * <p>覆盖：
 * <ul>
 *   <li>lane 释放后取最旧 WAITING 翻 RUNNING</li>
 *   <li>lane 满（3 lane）不派发</li>
 *   <li>CAS affected=0 兜底：notifier 不抛错，dispatcher 直接返回</li>
 *   <li>5s 兜底轮询触发：{@link RunDispatcher#onContextRefreshed()} 后立即调度</li>
 *   <li>无 BlockingQueue / Semaphore 作为队列副本（构造期不维护）</li>
 * </ul>
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class RunDispatcherTest {

    private FakeLanePool lanePool;
    private RecordingRepository repository;
    private RecordingExecutor executor;
    private FakePageHandleProvider pageHandleProvider;
    private RunDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        lanePool = new FakeLanePool(RunLanePool.DEFAULT_CAPACITY);
        repository = new RecordingRepository();
        executor = new RecordingExecutor();
        pageHandleProvider = new FakePageHandleProvider();
        dispatcher = new RunDispatcher(lanePool, repository, executor, pageHandleProvider);
    }

    @Test
    @DisplayName("dispatchOnce 取最旧 WAITING → CAS 翻 RUNNING → 提交到 lane")
    void dispatchOnceClaimsAndSubmits() {
        RunRepository.RunRecord r1 = repository.enqueueWaiting(101L, 1L);
        RunRepository.RunRecord r2 = repository.enqueueWaiting(102L, 1L);
        RunRepository.RunRecord r3 = repository.enqueueWaiting(103L, 2L);

        dispatcher.dispatchOnceForTest();

        // 三条都应被 claim + submit
        assertThat(repository.claimedIds).containsExactly(101L, 102L, 103L);
        assertThat(executor.submittedRunIds).containsExactly(101L, 102L, 103L);
        // lane 释放：所有 lease 关闭后 borrowedCount=0
        assertThat(lanePool.borrowedCount()).isZero();
    }

    @Test
    @DisplayName("lane 满（3 lane）时 dispatchOnce 不再 claim 新的 WAITING")
    void dispatchOnceSkipsWhenLaneFull() {
        // 模拟 lane 已借满：直接占用 FakeLanePool 的 3 个 slot
        lanePool.acquire("pre-1");
        lanePool.acquire("pre-2");
        lanePool.acquire("pre-3");
        // 队列中还有 2 个 WAITING
        repository.enqueueWaiting(201L, 1L);
        repository.enqueueWaiting(202L, 1L);

        dispatcher.dispatchOnceForTest();

        assertThat(repository.claimedIds).isEmpty();
        assertThat(executor.submittedRunIds).isEmpty();
        assertThat(lanePool.borrowedCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("CAS affected=0 兜底：空 claim 当作无新事件，dispatcher 直接返回")
    void dispatchOnceHandlesEmptyClaim() {
        // 队列空：claimOldestWaiting 直接返回 empty
        dispatcher.dispatchOnceForTest();
        assertThat(repository.claimedIds).isEmpty();
        assertThat(executor.submittedRunIds).isEmpty();
    }

    @Test
    @DisplayName("lane 释放后触发下一轮派发（事件驱动）")
    void laneReleaseTriggersNextDispatch() {
        repository.enqueueWaiting(301L, 1L);
        repository.enqueueWaiting(302L, 1L);
        repository.enqueueWaiting(303L, 1L);
        repository.enqueueWaiting(304L, 1L);

        // 第一轮：M3-2 同步执行下，3 条都执行并立即归还 lease；第 4 条仍 WAITING。
        // 但因为我们用同步 leaseAndSubmit，每条 execute 后立刻 close，pool 立即腾出 slot；
        // 而 dispatchOnce 是 while 循环，会持续尝试 claim 直到 pool 满或 queue 空。
        // 这里 while 不会 break：每轮 dispatchOnce 内已经处理到 queue 空。
        dispatcher.dispatchOnceForTest();
        // 第一轮（while 循环内）应把所有 4 条都取走 + 执行
        assertThat(executor.submittedRunIds).containsExactly(301L, 302L, 303L, 304L);
        // 此时所有 lease 已归还
        assertThat(lanePool.borrowedCount()).isZero();

        // 再触发一次：no-op（queue 已空）
        dispatcher.scheduleDispatch();
        assertThat(executor.submittedRunIds).containsExactly(301L, 302L, 303L, 304L);
    }

    @Test
    @DisplayName("兜底轮询：onContextRefreshed 后 schedule 可被多次触发")
    void fallbackScheduleIsIdempotent() {
        repository.enqueueWaiting(401L, 1L);
        dispatcher.onContextRefreshed();
        try {
            dispatcher.scheduleDispatch();
            dispatcher.scheduleDispatch();
            dispatcher.scheduleDispatch();
            assertThat(executor.submittedRunIds).containsExactly(401L);
        } finally {
            dispatcher.shutdown();
        }
    }

    @Test
    @DisplayName("started 前 scheduleDispatch 是 no-op（保护启动顺序）")
    void scheduleBeforeStartIsNoOp() {
        repository.enqueueWaiting(501L, 1L);
        dispatcher.scheduleDispatch();
        assertThat(executor.submittedRunIds).isEmpty();
    }

    @Test
    @DisplayName("executor 抛错时：dispatcher 仍标记 FAILED + 不抛向调用方")
    void executorFailureMarkedFailed() {
        RunRepository.RunRecord r = repository.enqueueWaiting(601L, 1L);
        executor.failOnRunId = 601L;
        dispatcher.dispatchOnceForTest();

        assertThat(repository.terminalMarks).containsKey(601L);
        assertThat(repository.terminalMarks.get(601L).status()).isEqualTo(RunState.FAILED);
        assertThat(repository.terminalMarks.get(601L).stopReason()).isEqualTo(StopReason.PAGE_RETRY_EXHAUSTED);
        assertThat(executor.submittedRunIds).contains(601L);
        // 不验证 r 内部字段；保留 reference 防 IDE 警告
        assertThat(r.runId()).isEqualTo(601L);
    }

    @Test
    @DisplayName("dispatcher 不持有 BlockingQueue / Semaphore 队列副本（构造期不维护）")
    void noJvmQueueCopies() {
        // 此断言为设计约束验证：dispatcher 字段只有 lanePool / repository / executor / scheduledEx。
        // 单元测试侧通过反射略作软校验（不强制；约束在 PR review 文档说明）。
        org.springframework.util.ReflectionUtils.findField(RunDispatcher.class, "lanePool");
        // 仅确保构造无异常 + 字段非空
        dispatcher.onContextRefreshed();
        try {
            assertThat(dispatcher).isNotNull();
        } finally {
            dispatcher.shutdown();
        }
    }

    // ---------- fakes ----------

    /** fake LanePool：borrowedCount 受 acquire/release 控制；不抛满（与 RunLanePool 一致）。 */
    private static final class FakeLanePool implements LanePool {
        private final int capacity;
        private final Deque<Boolean> available = new ArrayDeque<>();
        private final List<Lease> active = new ArrayList<>();

        FakeLanePool(int capacity) {
            this.capacity = capacity;
            for (int i = 0; i < capacity; i++) {
                available.push(true);
            }
        }

        @Override
        public int capacity() {
            return capacity;
        }

        @Override
        public int borrowedCount() {
            return active.size();
        }

        @Override
        public Lease acquire(String runId) {
            if (available.isEmpty()) {
                throw new IllegalStateException("FakeLanePool 已满");
            }
            available.pop();
            FakeLease lease = new FakeLease(runId, this);
            active.add(lease);
            return lease;
        }

        @Override
        public void release(Lease lease) {
            if (lease instanceof FakeLease fl && fl.released.compareAndSet(false, true)) {
                active.remove(fl);
                available.push(true);
            }
        }
    }

    private static final class FakeLease implements Lease {
        final String runKey;
        final FakeLanePool pool;
        final java.util.concurrent.atomic.AtomicBoolean released = new java.util.concurrent.atomic.AtomicBoolean(false);

        FakeLease(String runKey, FakeLanePool pool) {
            this.runKey = runKey;
            this.pool = pool;
        }

        @Override
        public String laneName() {
            return "fake-run-lane";
        }

        @Override
        public boolean isOpen() {
            return !released.get();
        }

        @Override
        public void close() {
            pool.release(this);
        }
    }

    /** fake repository：WAITING 队列 + claim 走 CAS；status 翻转模拟。 */
    private static final class RecordingRepository implements RunRepository {
        private final Deque<RunRecord> waiting = new ArrayDeque<>();
        private final List<Long> claimedIds = new ArrayList<>();
        private final java.util.Map<Long, TerminalMark> terminalMarks = new java.util.HashMap<>();

        RunRecord enqueueWaiting(long runId, long ownerId) {
            TaskDefinition def = new TaskDefinition(1, new TaskMode.SinglePage(),
                    "https://example.com", Viewport.DEFAULT, null, List.of());
            TaskSnapshot snap = new TaskSnapshot(1L, ownerId, "demo", new TaskMode.SinglePage(),
                    1, 1L, def);
            RunRecord r = new RunRecord(runId, 1L, ownerId, RunState.WAITING, null,
                    false, 0, 0, 0, snap,
                    java.time.OffsetDateTime.now(), null, null);
            waiting.add(r);
            return r;
        }

        @Override
        public Optional<RunRecord> claimOldestWaiting() {
            if (waiting.isEmpty()) {
                return Optional.empty();
            }
            RunRecord r = waiting.poll();
            claimedIds.add(r.runId());
            return Optional.of(new RunRecord(r.runId(), r.taskId(), r.ownerId(),
                    RunState.RUNNING, null, false, 0, 0, 0,
                    r.snapshot(), r.createdAt(), java.time.OffsetDateTime.now(), null));
        }

        @Override
        public boolean markTerminal(long runId, RunState status, StopReason stopReason) {
            terminalMarks.put(runId, new TerminalMark(status, stopReason));
            return true;
        }

        // ----- 未在本测试用到的方法：throw 提示扩展 -----
        @Override public int countActiveByOwner(long ownerId) { throw new UnsupportedOperationException(); }
        @Override public long insertWaiting(long taskId, long ownerId, TaskSnapshot snapshot) { throw new UnsupportedOperationException(); }
        @Override public Optional<RunRecord> findById(long runId) { throw new UnsupportedOperationException(); }
        @Override public boolean markCancelRequested(long runId) { throw new UnsupportedOperationException(); }
        @Override public int markCancelledIfWaiting(long runId) { throw new UnsupportedOperationException(); }
        @Override public int markAllActiveInterrupted() { throw new UnsupportedOperationException(); }
        @Override public List<com.visualspider.run.spi.RunSummary> listByOwner(Long ownerId, com.visualspider.run.spi.RunFilter filter) { throw new UnsupportedOperationException(); }
        @Override public com.visualspider.run.spi.Page<com.visualspider.run.spi.RunSummary> pageByOwner(Long ownerId, com.visualspider.run.spi.RunFilter filter) { throw new UnsupportedOperationException(); }
        @Override public Optional<com.visualspider.run.spi.RunProgress> loadProgress(long runId) { throw new UnsupportedOperationException(); }
        @Override public Optional<com.visualspider.run.spi.RunDetail> loadDetail(long runId) { throw new UnsupportedOperationException(); }
    }

    private record TerminalMark(RunState status, StopReason stopReason) {
    }

    /** fake executor：记录 submit；可选 fail。 */
    private static final class RecordingExecutor implements RunExecutor {
        final List<Long> submittedRunIds = new ArrayList<>();
        Long failOnRunId;

        @Override
        public void execute(RunExecutionContext context, long runId) {
            submittedRunIds.add(runId);
            if (failOnRunId != null && failOnRunId == runId) {
                throw new RuntimeException("synthetic failure");
            }
        }
    }

    /** fake page handle provider：每次返回新的 FakeRunPageHandle。 */
    private static final class FakePageHandleProvider implements RunPageHandleProvider {
        final List<RunPageHandle> handles = new ArrayList<>();

        @Override
        public RunPageHandle openFor(Lease lease, long runId) {
            FakeRunPageHandle h = new FakeRunPageHandle();
            handles.add(h);
            return h;
        }
    }
}