package com.visualspider.run.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.visualspider.extraction.spi.ExtractionPreview;
import com.visualspider.extraction.spi.PreviewResult;
import com.visualspider.result.spi.RunEventInput;
import com.visualspider.result.spi.RunEventLevel;
import com.visualspider.run.internal.testutil.FakeRunPageHandle;
import com.visualspider.run.internal.testutil.TestDomState;
import com.visualspider.run.internal.testutil.TestExtractionPreview;
import com.visualspider.run.internal.testutil.TestRunResultSink;
import com.visualspider.run.spi.RunExecutionContext;
import com.visualspider.run.spi.RunPageHandle;
import com.visualspider.run.spi.RunState;
import com.visualspider.run.spi.StopReason;
import com.visualspider.task.domain.FieldDefinition;
import com.visualspider.task.domain.FieldSource;
import com.visualspider.task.domain.ResultType;
import com.visualspider.task.domain.SelectorType;
import com.visualspider.task.domain.TaskDefinition;
import com.visualspider.task.domain.TaskMode;
import com.visualspider.task.domain.TaskSnapshot;
import com.visualspider.task.domain.TrimPolicy;
import com.visualspider.task.domain.Viewport;
import com.visualspider.task.domain.WaitPolicy;
import com.visualspider.visualbrowser.internal.BasicTargetUrlPolicy;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link SinglePageRunExecutor} 单元测试（issue #25 / spec §D9）。
 *
 * <p>覆盖：
 * <ul>
 *   <li>happy path：单次成功 / 第 2 次重试成功 -> SUCCESS + 1 结果</li>
 *   <li>3 次入口失败 -> FAILED + {@link StopReason#PAGE_RETRY_EXHAUSTED}</li>
 *   <li>最终 URL 不合法 -> {@link StopReason#ENTRY_FAILED}</li>
 *   <li>{@link StopReason#HTTP_429} / {@link StopReason#HTTP_403} / {@link StopReason#CAPTCHA}</li>
 *   <li>取消：起始 → CANCELLED + {@link StopReason#USER_CANCEL}</li>
 *   <li>{@code TIME_LIMIT}</li>
 *   <li>每个阶段变更 + 结果写入 + 终态均写 {@code run_event}（WS 推送原料）</li>
 *   <li>{@code extraWaitSeconds} 触发 1 次 {@code extraWaitSeconds()} 调用</li>
 *   <li>{@link SinglePageRunExecutor#execute} 在异常路径也确保 {@link RunPageHandle#close} 关闭</li>
 * </ul>
 */
class SinglePageRunExecutorTest {

    private InMemoryRepository repository;
    private TestRunResultSink sink;
    private TestExtractionPreview preview;
    private SinglePageRunExecutor executor;

    @BeforeEach
    void setUp() {
        repository = new InMemoryRepository();
        sink = new TestRunResultSink();
        preview = new TestExtractionPreview();
        executor = new SinglePageRunExecutor(repository, sink, preview,
                new BasicTargetUrlPolicy());
    }

    // ----------------- happy path -----------------

    @Nested
    @DisplayName("happy path")
    class HappyPath {

        @Test
        @DisplayName("单次成功：1 条结果 + SUCCESS + COMPLETED + extraWait 调用 1 次")
        void singleSuccess() {
            TestDomState dom = new TestDomState("https://example.com/")
                    .withNodes(".title", SelectorType.CSS, List.of(textNode("Hello")));
            FakeRunPageHandle handle = pageHandle(success(200), true, "https://example.com/", dom);
            long runId = insertRun(defWithField(".title", SelectorType.CSS));
            preview.queueResult(TestExtractionPreview.successResult(Map.of("title", "Hello")));

            executor.execute(context(handle), runId);

            TerminalMark mark = repository.terminalMarks.get(runId);
            assertThat(mark).isNotNull();
            assertThat(mark.status()).isEqualTo(RunState.SUCCESS);
            assertThat(mark.stopReason()).isEqualTo(StopReason.COMPLETED);
            assertThat(sink.allResults()).hasSize(1);
            assertThat(sink.allResults().get(0).sequenceNo()).isEqualTo(1);
            assertThat(sink.allResults().get(0).data()).containsEntry("title", "Hello");
            assertThat(sink.allEvents()).extracting(RunEventInput::stage)
                    .anyMatch(s -> "extract-success".equals(s))
                    .anyMatch(s -> "terminal".equals(s));
            assertThat(handle.extraWaitCallCount()).isEqualTo(1);
            assertThat(handle.closed()).isTrue();
        }

        @Test
        @DisplayName("首两次导航失败、第三次成功 -> SUCCESS + COMPLETED；记录 3 次导航")
        void retryThenSuccess() {
            TestDomState dom = new TestDomState("https://example.com/")
                    .withNodes(".title", SelectorType.CSS, List.of(textNode("World")));
            FakeRunPageHandle handle = pageHandleWithNavigations(
                    List.of(failure(502, "upstream busy"),
                            failure(504, "gateway timeout"),
                            success(200)),
                    true,
                    "https://example.com/",
                    dom);
            long runId = insertRun(defWithField(".title", SelectorType.CSS));
            preview.queueResult(TestExtractionPreview.successResult(Map.of("title", "World")));

            executor.execute(context(handle), runId);

            assertThat(repository.terminalMarks.get(runId).status()).isEqualTo(RunState.SUCCESS);
            assertThat(repository.terminalMarks.get(runId).stopReason()).isEqualTo(StopReason.COMPLETED);
            assertThat(handle.navigationCallCount()).isEqualTo(3);
            assertThat(sink.allResults()).hasSize(1);
            assertThat(sink.allEvents()).filteredOn(e -> e.level() == RunEventLevel.WARN
                    && "navigate".equals(e.stage())).hasSizeGreaterThanOrEqualTo(2);
        }

        @Test
        @DisplayName("extraWaitSeconds=3 触发 1 次 3 秒调用")
        void extraWaitHonored() {
            TestDomState dom = new TestDomState("https://example.com/")
                    .withNodes(".x", SelectorType.CSS, List.of(textNode("v")));
            FakeRunPageHandle handle = pageHandle(success(200), true, "https://example.com/", dom);
            long runId = insertRun(defWithFieldAndWait(".x", SelectorType.CSS, 3));
            preview.queueResult(TestExtractionPreview.successResult(Map.of("x", "v")));

            executor.execute(context(handle), runId);

            assertThat(handle.extraWaits()).containsExactly(3);
        }

        @Test
        @DisplayName("extraWaitSeconds=0 仍触发 1 次 0 秒调用（不绕过步骤）")
        void zeroExtraWait() {
            TestDomState dom = new TestDomState("https://example.com/")
                    .withNodes(".x", SelectorType.CSS, List.of(textNode("v")));
            FakeRunPageHandle handle = pageHandle(success(200), true, "https://example.com/", dom);
            long runId = insertRun(defWithFieldAndWait(".x", SelectorType.CSS, 0));
            preview.queueResult(TestExtractionPreview.successResult(Map.of("x", "v")));

            executor.execute(context(handle), runId);

            assertThat(handle.extraWaits()).containsExactly(0);
        }
    }

    // ----------------- failure paths -----------------

    @Nested
    @DisplayName("失败路径")
    class FailurePaths {

        @Test
        @DisplayName("3 次导航全败 -> FAILED + PAGE_RETRY_EXHAUSTED；不写结果")
        void allNavigationsFail() {
            TestDomState dom = new TestDomState("https://example.com/");
            FakeRunPageHandle handle = pageHandleWithNavigations(
                    List.of(failure(500, "boom"),
                            failure(500, "boom"),
                            failure(500, "boom")),
                    true,
                    "https://example.com/",
                    dom);
            long runId = insertRun(defWithField(".x", SelectorType.CSS));

            executor.execute(context(handle), runId);

            assertThat(repository.terminalMarks.get(runId).status()).isEqualTo(RunState.FAILED);
            assertThat(repository.terminalMarks.get(runId).stopReason())
                    .isEqualTo(StopReason.PAGE_RETRY_EXHAUSTED);
            assertThat(sink.allResults()).isEmpty();
            assertThat(sink.allEvents()).extracting(RunEventInput::stage)
                    .contains("terminal");
        }

        @Test
        @DisplayName("导航成功但最终 URL 不合法 -> FAILED + ENTRY_FAILED（不再重试）")
        void entryUrlRejected() {
            TestDomState dom = new TestDomState("gopher://example.com/");
            FakeRunPageHandle handle = pageHandle(success(200), true,
                    "gopher://example.com/", dom);
            long runId = insertRun(defWithField(".x", SelectorType.CSS));

            executor.execute(context(handle), runId);

            assertThat(repository.terminalMarks.get(runId).status()).isEqualTo(RunState.FAILED);
            assertThat(repository.terminalMarks.get(runId).stopReason())
                    .isEqualTo(StopReason.ENTRY_FAILED);
            assertThat(sink.allEvents()).anyMatch(e ->
                    e.level() == RunEventLevel.ERROR && "entry-failed".equals(e.stage()));
            assertThat(handle.navigationCallCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("HTTP 429 -> FAILED + HTTP_429（不重试）")
        void http429() {
            TestDomState dom = new TestDomState("https://example.com/");
            FakeRunPageHandle handle = pageHandle(
                    new RunPageHandle.NavigationResult(true, 429, false, "Too Many Requests"),
                    true, "https://example.com/", dom);
            long runId = insertRun(defWithField(".x", SelectorType.CSS));

            executor.execute(context(handle), runId);

            assertThat(repository.terminalMarks.get(runId).status()).isEqualTo(RunState.FAILED);
            assertThat(repository.terminalMarks.get(runId).stopReason())
                    .isEqualTo(StopReason.HTTP_429);
            assertThat(handle.navigationCallCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("HTTP 403 -> FAILED + HTTP_403（不重试）")
        void http403() {
            TestDomState dom = new TestDomState("https://example.com/");
            FakeRunPageHandle handle = pageHandle(
                    new RunPageHandle.NavigationResult(true, 403, false, "Forbidden"),
                    true, "https://example.com/", dom);
            long runId = insertRun(defWithField(".x", SelectorType.CSS));

            executor.execute(context(handle), runId);

            assertThat(repository.terminalMarks.get(runId).stopReason())
                    .isEqualTo(StopReason.HTTP_403);
        }

        @Test
        @DisplayName("captcha detected -> FAILED + CAPTCHA")
        void captcha() {
            TestDomState dom = new TestDomState("https://example.com/");
            FakeRunPageHandle handle = pageHandle(
                    new RunPageHandle.NavigationResult(true, 200, true, "captcha detected"),
                    true, "https://example.com/", dom);
            long runId = insertRun(defWithField(".x", SelectorType.CSS));

            executor.execute(context(handle), runId);

            assertThat(repository.terminalMarks.get(runId).stopReason())
                    .isEqualTo(StopReason.CAPTCHA);
        }

        @Test
        @DisplayName("extraction 抛异常：记 ERROR 事件 + 重试；第二次成功 -> SUCCESS")
        void extractionThenRecovery() {
            TestDomState dom = new TestDomState("https://example.com/")
                    .withNodes(".x", SelectorType.CSS, List.of(textNode("v")));
            FakeRunPageHandle handle = pageHandleWithNavigations(
                    List.of(success(200), success(200)),
                    true,
                    "https://example.com/",
                    dom);
            long runId = insertRun(defWithField(".x", SelectorType.CSS));
            preview.queueThrowable(new RuntimeException("synthetic DOM read failure"));
            preview.queueResult(TestExtractionPreview.successResult(Map.of("x", "v")));

            executor.execute(context(handle), runId);

            assertThat(repository.terminalMarks.get(runId).status()).isEqualTo(RunState.SUCCESS);
            assertThat(repository.terminalMarks.get(runId).stopReason()).isEqualTo(StopReason.COMPLETED);
            assertThat(sink.allEvents()).anyMatch(e -> "extract-error".equals(e.stage())
                    && e.level() == RunEventLevel.ERROR);
            assertThat(sink.allResults()).hasSize(1);
        }

        @Test
        @DisplayName("起始 URL 不合法（构造阶段拒绝）-> FAILED + ENTRY_FAILED")
        void invalidStartUrl() {
            TestDomState dom = new TestDomState("https://example.com/");
            FakeRunPageHandle handle = pageHandle(success(200), true, "https://example.com/", dom);
            long runId = repository.insertWithUrl("ftp://example.com",
                    field(".x", SelectorType.CSS));

            executor.execute(context(handle), runId);

            assertThat(repository.terminalMarks.get(runId).status()).isEqualTo(RunState.FAILED);
            assertThat(repository.terminalMarks.get(runId).stopReason())
                    .isEqualTo(StopReason.ENTRY_FAILED);
            // 起始 URL 不合法 → 不进入导航步骤，直接终态
            assertThat(handle.navigationCallCount()).isZero();
        }
    }

    // ----------------- cancel paths -----------------

    @Nested
    @DisplayName("取消")
    class CancelPaths {

        @Test
        @DisplayName("起始 cancel -> CANCELLED + USER_CANCEL；handle 仍被关闭")
        void cancelBeforeStart() {
            TestDomState dom = new TestDomState("https://example.com/");
            FakeRunPageHandle handle = pageHandle(success(200), true, "https://example.com/", dom);
            long runId = insertRun(defWithField(".x", SelectorType.CSS));
            RunExecutionContext ctx = context(handle);
            ctx.requestCancel();

            executor.execute(ctx, runId);

            assertThat(repository.terminalMarks.get(runId).status()).isEqualTo(RunState.CANCELLED);
            assertThat(repository.terminalMarks.get(runId).stopReason())
                    .isEqualTo(StopReason.USER_CANCEL);
            assertThat(handle.navigationCallCount()).isZero();
            assertThat(handle.closed()).isTrue();
        }
    }

    // ----------------- limit / time paths -----------------

    @Nested
    @DisplayName("限制 / 时间")
    class LimitPaths {

        @Test
        @DisplayName("elapsed >= 30min -> FAILED + TIME_LIMIT（在 attempt 入口检查）")
        void timeLimitBreaksBeforeNavigate() {
            TestDomState dom = new TestDomState("https://example.com/");
            FakeRunPageHandle handle = pageHandle(success(200), true, "https://example.com/", dom);
            long runId = insertRun(defWithField(".x", SelectorType.CSS));

            executor.execute(contextWithLimit(handle, /* startedAtMs= */ 1L,
                    /* maxDurationMs= */ 1L), runId);

            assertThat(repository.terminalMarks.get(runId).status()).isEqualTo(RunState.FAILED);
            assertThat(repository.terminalMarks.get(runId).stopReason())
                    .isEqualTo(StopReason.TIME_LIMIT);
            assertThat(handle.navigationCallCount()).isZero();
            assertThat(handle.closed()).isTrue();
        }

        @Test
        @DisplayName("3 次提取异常后应 FAILED + handle 仍关闭（finally 兜底）")
        void closeEvenWhenExtractionAlwaysFails() {
            TestDomState dom = new TestDomState("https://example.com/")
                    .withNodes(".x", SelectorType.CSS, List.of(textNode("v")));
            FakeRunPageHandle handle = pageHandleWithNavigations(
                    List.of(success(200), success(200), success(200)),
                    true, "https://example.com/", dom);
            long runId = insertRun(defWithField(".x", SelectorType.CSS));
            preview.queueThrowable(new RuntimeException("first boom"));
            preview.queueThrowable(new RuntimeException("second boom"));
            preview.queueThrowable(new RuntimeException("third boom"));

            executor.execute(context(handle), runId);

            assertThat(handle.closed()).isTrue();
            assertThat(repository.terminalMarks.get(runId).status()).isEqualTo(RunState.FAILED);
            assertThat(repository.terminalMarks.get(runId).stopReason())
                    .isEqualTo(StopReason.PAGE_RETRY_EXHAUSTED);
        }

        @Test
        @DisplayName("run_event 终态被写入（含 run_event 触发 WS 推送原料）")
        void terminalEventEmitted() {
            TestDomState dom = new TestDomState("https://example.com/")
                    .withNodes(".x", SelectorType.CSS, List.of(textNode("v")));
            FakeRunPageHandle handle = pageHandle(success(200), true, "https://example.com/", dom);
            long runId = insertRun(defWithField(".x", SelectorType.CSS));
            preview.queueResult(TestExtractionPreview.successResult(Map.of("x", "v")));

            executor.execute(context(handle), runId);

            // 终态事件：INFO 级别 + stage=terminal + message 含 "SUCCESS/COMPLETED"
            assertThat(sink.allEvents()).anyMatch(e ->
                    "terminal".equals(e.stage())
                            && e.level() == RunEventLevel.INFO
                            && e.message().contains("SUCCESS"));
        }
    }

    // ----------------- helpers -----------------

    private RunExecutionContext context(RunPageHandle handle) {
        return new RunExecutionContext(
                System.currentTimeMillis(),
                30L * 60L * 1000L, // 30 min
                200,
                10_000,
                handle);
    }

    private RunExecutionContext contextWithLimit(RunPageHandle handle, long startedAtMs, long maxDurationMs) {
        return new RunExecutionContext(startedAtMs, maxDurationMs, 200, 10_000, handle);
    }

    private FakeRunPageHandle pageHandle(RunPageHandle.NavigationResult first, boolean waitFound,
                                          String url, TestDomState dom) {
        FakeRunPageHandle h = new FakeRunPageHandle();
        h.queueNavigation(first);
        h.queueWaitForSelector(waitFound);
        h.setCurrentUrl(url);
        h.setDomState(dom);
        return h;
    }

    private FakeRunPageHandle pageHandleWithNavigations(List<RunPageHandle.NavigationResult> results,
                                                         boolean waitFound,
                                                         String url,
                                                         TestDomState dom) {
        FakeRunPageHandle h = new FakeRunPageHandle();
        for (RunPageHandle.NavigationResult r : results) {
            h.queueNavigation(r);
        }
        for (int i = 0; i < results.size(); i++) {
            h.queueWaitForSelector(waitFound);
        }
        h.setCurrentUrl(url);
        h.setDomState(dom);
        return h;
    }

    private RunPageHandle.NavigationResult success(int status) {
        return new RunPageHandle.NavigationResult(true, status, false, null);
    }

    private RunPageHandle.NavigationResult failure(int status, String msg) {
        return new RunPageHandle.NavigationResult(false, status, false, msg);
    }

    private static ExtractionPreview.Node textNode(String text) {
        return new ExtractionPreview.Node("div", "", "", text, Map.of());
    }

    private static FieldDefinition field(String selector, SelectorType type) {
        return new FieldDefinition("x", FieldSource.VISIBLE_TEXT, selector, null,
                type, ResultType.TEXT, TrimPolicy.TRIM, null, false);
    }

    private static FieldDefinition fieldTitled(String name, String selector, SelectorType type) {
        return new FieldDefinition(name, FieldSource.VISIBLE_TEXT, selector, null,
                type, ResultType.TEXT, TrimPolicy.TRIM, null, false);
    }

    private static TaskDefinition defWithField(String selector, SelectorType type) {
        return new TaskDefinition(1, new TaskMode.SinglePage(),
                "https://example.com", Viewport.DEFAULT, new WaitPolicy(0),
                List.of(field(selector, type)));
    }

    private static TaskDefinition defWithFieldAndWait(String selector, SelectorType type, int extraWait) {
        return new TaskDefinition(1, new TaskMode.SinglePage(),
                "https://example.com", Viewport.DEFAULT, new WaitPolicy(extraWait),
                List.of(fieldTitled("title", selector, type)));
    }

    private long insertRun(TaskDefinition def) {
        return repository.insert(def);
    }

    /** 最小可观察 repository：findById / markTerminal / insert。其它方法显式不支持。 */
    private static final class InMemoryRepository implements RunRepository {

        final java.util.Map<Long, TerminalMark> terminalMarks = new java.util.LinkedHashMap<>();
        private final java.util.Map<Long, RunRecord> byId = new java.util.LinkedHashMap<>();
        private long seq = 9001L;

        long insert(TaskDefinition def) {
            long id = seq++;
            TaskSnapshot snap = new TaskSnapshot(id, 1L, "demo", new TaskMode.SinglePage(),
                    1, 1L, def);
            byId.put(id, new RunRecord(id, id, 1L, RunState.RUNNING, null,
                    false, 0, 0, 0, snap,
                    OffsetDateTime.now(), OffsetDateTime.now(), null));
            return id;
        }

        long insertWithUrl(String startUrl, FieldDefinition fld) {
            long id = seq++;
            TaskDefinition def = new TaskDefinition(1, new TaskMode.SinglePage(),
                    startUrl, Viewport.DEFAULT, new WaitPolicy(0), List.of(fld));
            TaskSnapshot snap = new TaskSnapshot(id, 1L, "demo", new TaskMode.SinglePage(),
                    1, 1L, def);
            byId.put(id, new RunRecord(id, id, 1L, RunState.RUNNING, null,
                    false, 0, 0, 0, snap,
                    OffsetDateTime.now(), OffsetDateTime.now(), null));
            return id;
        }

        @Override
        public boolean markTerminal(long runId, RunState status, StopReason stopReason) {
            terminalMarks.put(runId, new TerminalMark(status, stopReason));
            return true;
        }

        @Override
        public Optional<RunRecord> findById(long runId) {
            return Optional.ofNullable(byId.get(runId));
        }

        @Override public int countActiveByOwner(long ownerId) { throw new UnsupportedOperationException(); }
        @Override public long insertWaiting(long taskId, long ownerId, TaskSnapshot snapshot) { throw new UnsupportedOperationException(); }
        @Override public Optional<RunRecord> claimOldestWaiting() { throw new UnsupportedOperationException(); }
        @Override public boolean markCancelRequested(long runId) { throw new UnsupportedOperationException(); }
        @Override public int markAllActiveInterrupted() { throw new UnsupportedOperationException(); }
        @Override public List<com.visualspider.run.spi.RunSummary> listByOwner(Long ownerId, com.visualspider.run.spi.RunFilter filter) { throw new UnsupportedOperationException(); }
        @Override public com.visualspider.run.spi.Page<com.visualspider.run.spi.RunSummary> pageByOwner(Long ownerId, com.visualspider.run.spi.RunFilter filter) { throw new UnsupportedOperationException(); }
        @Override public Optional<com.visualspider.run.spi.RunProgress> loadProgress(long runId) { throw new UnsupportedOperationException(); }
        @Override public Optional<com.visualspider.run.spi.RunDetail> loadDetail(long runId) { throw new UnsupportedOperationException(); }
    }

    private record TerminalMark(RunState status, StopReason stopReason) {
    }
}
