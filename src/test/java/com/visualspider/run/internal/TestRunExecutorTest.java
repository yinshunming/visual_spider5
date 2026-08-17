package com.visualspider.run.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.visualspider.run.spi.RunExecutionContext;
import com.visualspider.run.spi.RunState;
import com.visualspider.run.spi.StopReason;
import com.visualspider.task.domain.TaskDefinition;
import com.visualspider.task.domain.TaskMode;
import com.visualspider.task.domain.TaskSnapshot;
import com.visualspider.task.domain.Viewport;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link TestRunExecutor}（M3-2 stub）单元测试：覆盖 cancel-before-start 与正常成功路径。
 */
class TestRunExecutorTest {

    private InMemoryRepo repo;

    @BeforeEach
    void setUp() {
        repo = new InMemoryRepo();
    }

    @Test
    @DisplayName("未取消：写 SUCCESS + COMPLETED + recordCount=1")
    void successPath() {
        TestRunExecutor exec = new TestRunExecutor(repo);
        RunExecutionContext ctx = newContext();
        long runId = repo.insert(100L, 1L);

        exec.execute(ctx, runId);

        assertThat(repo.terminalMarks).containsKey(runId);
        assertThat(repo.terminalMarks.get(runId).status()).isEqualTo(RunState.SUCCESS);
        assertThat(repo.terminalMarks.get(runId).stopReason()).isEqualTo(StopReason.COMPLETED);
        assertThat(ctx.pageCount()).isEqualTo(1);
        assertThat(ctx.recordCountFinal()).isEqualTo(1);
    }

    @Test
    @DisplayName("已请求取消：写 CANCELLED + USER_CANCEL；不增加计数")
    void cancelBeforeStart() {
        TestRunExecutor exec = new TestRunExecutor(repo);
        RunExecutionContext ctx = newContext();
        ctx.requestCancel();
        long runId = repo.insert(101L, 1L);

        exec.execute(ctx, runId);

        assertThat(repo.terminalMarks).containsKey(runId);
        assertThat(repo.terminalMarks.get(runId).status()).isEqualTo(RunState.CANCELLED);
        assertThat(repo.terminalMarks.get(runId).stopReason()).isEqualTo(StopReason.USER_CANCEL);
        assertThat(ctx.pageCount()).isZero();
        assertThat(ctx.recordCountFinal()).isZero();
    }

    private static RunExecutionContext newContext() {
        return new RunExecutionContext(Instant.now().toEpochMilli(),
                java.time.Duration.ofMinutes(30).toMillis(),
                200, 10_000);
    }

    /** 最小 in-memory repo，仅满足 TestRunExecutor 写入 terminal 的需要。 */
    private static final class InMemoryRepo extends InMemoryRunRepositorySupport {
        long insert(long taskId, long ownerId) {
            return super.doInsert(taskId, ownerId);
        }
    }

    /** 提供 doInsert：避免 InMemoryRunRepositorySupport 与 RunCoordinator 测试互相污染。 */
    static class InMemoryRunRepositorySupport extends InMemoryRunRepositoryShim {
        final java.util.Map<Long, TerminalMark> terminalMarks = new java.util.HashMap<>();

        long doInsert(long taskId, long ownerId) {
            return super.insertInternal(taskId, ownerId);
        }

        @Override
        public boolean markTerminal(long runId, RunState status, StopReason stopReason) {
            terminalMarks.put(runId, new TerminalMark(status, stopReason));
            return true;
        }
    }

    record TerminalMark(RunState status, StopReason stopReason) {
    }

    /**
     * 提供 insertInternal 与其它基本方法（仅测试 stub 路径用到 insert + markTerminal）。
     * 其余方法抛 UnsupportedOperationException 以免被误用。
     */
    static class InMemoryRunRepositoryShim implements RunRepository {
        private final java.util.Map<Long, RunRecord> byId = new java.util.HashMap<>();
        private long seq = 1;

        long insertInternal(long taskId, long ownerId) {
            long id = seq++;
            TaskDefinition def = new TaskDefinition(1, new TaskMode.SinglePage(),
                    "https://example.com", Viewport.DEFAULT, null, List.of());
            TaskSnapshot snap = new TaskSnapshot(1L, ownerId, "demo", new TaskMode.SinglePage(),
                    1, 1L, def);
            RunRecord r = new RunRecord(id, taskId, ownerId, RunState.WAITING, null,
                    false, 0, 0, 0, snap,
                    java.time.OffsetDateTime.now(), null, null);
            byId.put(id, r);
            return id;
        }

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
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<RunRecord> findById(long runId) {
            return Optional.ofNullable(byId.get(runId));
        }

        @Override
        public Optional<RunRecord> claimOldestWaiting() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean markCancelRequested(long runId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int markCancelledIfWaiting(long runId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean markTerminal(long runId, RunState status, StopReason stopReason) {
            // 测试 stub：在子类覆盖
            throw new UnsupportedOperationException();
        }

        @Override
        public int markAllActiveInterrupted() {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<com.visualspider.run.spi.RunSummary> listByOwner(Long ownerId, com.visualspider.run.spi.RunFilter filter) {
            throw new UnsupportedOperationException();
        }

        @Override
        public com.visualspider.run.spi.Page<com.visualspider.run.spi.RunSummary> pageByOwner(Long ownerId, com.visualspider.run.spi.RunFilter filter) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<com.visualspider.run.spi.RunProgress> loadProgress(long runId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<com.visualspider.run.spi.RunDetail> loadDetail(long runId) {
            throw new UnsupportedOperationException();
        }
    }
}