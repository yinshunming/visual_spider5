package com.visualspider.run;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.visualspider.extraction.spi.ExtractionPreview;
import com.visualspider.extraction.spi.ExtractionPreview.Node;
import com.visualspider.extraction.spi.PreviewResult;
import com.visualspider.extraction.spi.PreviewResult.FieldOutcome;
import com.visualspider.result.internal.UniqueKeyHasher;
import com.visualspider.result.spi.BatchOutcome;
import com.visualspider.result.spi.ResultRecord;
import com.visualspider.result.spi.RunEventInput;
import com.visualspider.result.spi.RunResultSink;
import com.visualspider.run.internal.JdbcRunRepository;
import com.visualspider.run.internal.ListRunExecutor;
import com.visualspider.run.spi.RunExecutionContext;
import com.visualspider.run.spi.RunPageHandle;
import com.visualspider.run.spi.RunState;
import com.visualspider.run.spi.StopReason;
import com.visualspider.task.domain.FieldDefinition;
import com.visualspider.task.domain.FieldSource;
import com.visualspider.task.domain.Limits;
import com.visualspider.task.domain.ListItemRule;
import com.visualspider.task.domain.ResultType;
import com.visualspider.task.domain.SelectorType;
import com.visualspider.task.domain.TaskDefinition;
import com.visualspider.task.domain.TaskMode;
import com.visualspider.task.domain.TaskSnapshot;
import com.visualspider.task.domain.TrimPolicy;
import com.visualspider.task.domain.UniqueKeyField;
import com.visualspider.task.domain.Viewport;
import com.visualspider.task.domain.WaitPolicy;
import com.visualspider.visualbrowser.spi.TargetUrlPolicy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

/**
 * List 模式 partial-fail IT（issue #35 / spec §D7 / §D8 / §T2 pg-it + 假 lane）。
 *
 * <p>真实 PG + 假 lane（自构造 {@link RunPageHandle}），覆盖 PARTIAL_SUCCESS 终态触发：
 * 1 item 写入失败（mocked sink 抛错）→ fail=1 / final=2 / PARTIAL_SUCCESS。
 *
 * <p>与 {@link ListRunIT}（真 Playwright lane）的 happy-path / with-duplicates 形成互补——
 * 后者无法在真 lane 触发 fail > 0（spec §D7 fail 计数只来自 sink 行级异常，真 lane 跑
 * 顺利时 0；D7 PARTIAL_SUCCESS 需要 sink 抛非 dedup 异常，本 IT 通过 delegating sink 模拟）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"run.dispatcher.enabled=false"})
@ActiveProfiles("it")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ListRunPartialFailIT {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private com.visualspider.run.internal.RunRepository repository;

    @Autowired
    private RunResultSink realSink;

    @Autowired
    private ExtractionPreview preview;

    @Autowired
    private TargetUrlPolicy urlPolicy;

    @Autowired
    private UniqueKeyHasher hasher;

    private long userId;
    private long taskId;
    private long runId;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM run_result");
        jdbc.update("DELETE FROM run_event");
        jdbc.update("DELETE FROM collection_run");
        jdbc.update("DELETE FROM collection_task");
        jdbc.update("DELETE FROM app_user WHERE username LIKE 'it-%'");
        userId = jdbc.queryForObject(
                "INSERT INTO app_user (username, password_hash, role, status) "
                        + "VALUES (?, ?, 'COLLECTOR', 'ACTIVE') RETURNING id",
                Long.class, "it-pfail", "{noop}pfail-pwd-12chars");
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM run_result");
        jdbc.update("DELETE FROM run_event");
        jdbc.update("DELETE FROM collection_run");
        jdbc.update("DELETE FROM collection_task");
        jdbc.update("DELETE FROM app_user WHERE username LIKE 'it-%'");
    }

    @Test
    @DisplayName("partial-fail：3 item 假 lane + delegating sink 在第 2 条抛错 → final=2/fail=1 + PARTIAL_SUCCESS")
    void partialFail() throws Exception {
        // 1) 假 lane：3 个 item node
        FakePageHandle page = new FakePageHandle(List.of(
                new Node("tr", "", "v-0", "", java.util.Map.of()),
                new Node("tr", "", "v-1", "", java.util.Map.of()),
                new Node("tr", "", "v-2", "", java.util.Map.of())));
        // 2) delegating sink：第 2 条记录（sequence_no=2）模拟 DB 行级失败
        //     模仿真实 JdbcRunResultRepository 的 per-record 失败语义：
        //     - events 照常插入（不影响 record count）
        //     - 第 2 条 record 不写 run_result，但 collection_run.fail_count++/final_count 不变
        //     - raw_count 包含所有尝试（与真实 JdbcRunResultRepository.appendBatch 行为一致）
        AtomicInteger recordCallCount = new AtomicInteger(0);
        RunResultSink failingSink = new PartialFailSink(realSink, jdbc, recordCallCount);
        // 3) 插入 LIST 任务 + WAITING run
        TaskDefinition def = listDefinition();
        String definitionJson = objectMapper.writeValueAsString(def);
        taskId = jdbc.queryForObject(
                "INSERT INTO collection_task (owner_id, name, mode, status, schema_version, definition) "
                        + "VALUES (?, 'it-pfail-task', 'LIST', 'READY', 2, ?::jsonb) RETURNING id",
                Long.class, userId, definitionJson);
        TaskSnapshot snapshot = new TaskSnapshot(taskId, userId, "it-pfail-task",
                new TaskMode.List(), 2, 1L, def);
        String snapshotJson = objectMapper.writeValueAsString(snapshot);
        runId = jdbc.queryForObject(
                "INSERT INTO collection_run (task_id, owner_id, snapshot, status) "
                        + "VALUES (?, ?, ?::jsonb, 'WAITING') RETURNING id",
                Long.class, taskId, userId, snapshotJson);

        // 4) 直接调 ListRunExecutor，绕开 dispatcher
        ListRunExecutor executor = new ListRunExecutor(repository, failingSink, preview,
                urlPolicy, hasher);
        RunExecutionContext ctx = new RunExecutionContext(
                System.currentTimeMillis(), 30L * 60 * 1000L, 200, 10_000, page);
        executor.execute(ctx, runId);

        // 5) 校验终态 + 计数
        String status = jdbc.queryForObject(
                "SELECT status FROM collection_run WHERE id = ?", String.class, runId);
        String stopReason = jdbc.queryForObject(
                "SELECT stop_reason FROM collection_run WHERE id = ?", String.class, runId);
        assertThat(status).isEqualTo(RunState.PARTIAL_SUCCESS.name());
        assertThat(stopReason).isEqualTo(StopReason.COMPLETED.name());

        var counts = jdbc.queryForMap(
                "SELECT record_count_raw, record_count_dedup, record_count_final, fail_count "
                        + "FROM collection_run WHERE id = ?",
                runId);
        assertThat(counts.get("record_count_raw")).isEqualTo(3);
        assertThat(counts.get("record_count_dedup")).isEqualTo(0);
        assertThat(counts.get("record_count_final")).isEqualTo(2);
        assertThat(counts.get("fail_count")).isEqualTo(1);

        // 6) 校验 run_event 序列：list-iter-start + LIST_ITEM_EXTRACTED + LIST_ITEM_FAILED + terminal
        List<String> stages = jdbc.queryForList(
                "SELECT level || '/' || stage FROM run_event WHERE run_id = ? ORDER BY id",
                String.class, runId);
        assertThat(stages).contains(
                "INFO/list-iter-start",
                "INFO/LIST_ITEM_EXTRACTED",
                "WARN/LIST_ITEM_FAILED",
                "INFO/terminal");
    }

    private TaskDefinition listDefinition() {
        FieldDefinition title = new FieldDefinition("title", FieldSource.VISIBLE_TEXT,
                "h1", null, SelectorType.CSS, ResultType.TEXT, TrimPolicy.TRIM, null, true);
        return new TaskDefinition(
                2,
                new TaskMode.List(),
                "https://example.com/list",
                Viewport.DEFAULT,
                new WaitPolicy(0),
                new Limits(200, 10_000, Duration.ofMinutes(30)),
                new ListItemRule("ul > li", SelectorType.CSS),
                List.of(new UniqueKeyField("title")),
                List.of(title));
    }

    /**
     * Delegating RunResultSink：默认转发到下游；子类可覆盖单条行为模拟行级失败。
     */
    static class DelegatingSink implements RunResultSink {
        protected final RunResultSink delegate;

        DelegatingSink(RunResultSink delegate) {
            this.delegate = delegate;
        }

        @Override
        public BatchOutcome appendBatch(long runId, List<ResultRecord> results,
                                        List<RunEventInput> events) {
            return delegate.appendBatch(runId, results, events);
        }
    }

    /**
     * 模拟 JdbcRunResultRepository per-record 失败的 sink：第 2 条 record 写入失败，
     * 直接更新 collection_run 计数（raw++ / final 不变 / fail++），不写 run_result 行。
     * 其他 record 和所有 events 透传到 realSink。
     */
    static class PartialFailSink implements RunResultSink {
        private final RunResultSink delegate;
        private final JdbcTemplate jdbc;
        private final AtomicInteger recordCallCount;
        private static final int FAIL_AT_SEQUENCE = 2;

        PartialFailSink(RunResultSink delegate, JdbcTemplate jdbc,
                        AtomicInteger recordCallCount) {
            this.delegate = delegate;
            this.jdbc = jdbc;
            this.recordCallCount = recordCallCount;
        }

        @Override
        public BatchOutcome appendBatch(long runId, List<ResultRecord> results,
                                        List<RunEventInput> events) {
            int total = results == null ? 0 : results.size();
            int inserted = 0;
            int failed = 0;
            // 1) 按 record 顺序模拟：到 FAIL_AT_SEQUENCE 触发失败
            for (int i = 0; i < total; i++) {
                int callNo = recordCallCount.incrementAndGet();
                ResultRecord r = results.get(i);
                if (callNo == FAIL_AT_SEQUENCE) {
                    // 行级失败：raw++、fail++、final 不变
                    jdbc.update(
                            "UPDATE collection_run "
                                    + "SET record_count_raw = record_count_raw + 1, "
                                    + "    fail_count = fail_count + 1 "
                                    + "WHERE id = ?",
                            runId);
                    failed++;
                } else {
                    // 成功：把单条 record 转发到真实 sink
                    BatchOutcome out = delegate.appendBatch(runId, List.of(r), List.of());
                    inserted += out.insertedCount();
                    failed += out.failedCount();
                }
            }
            // 2) 事件透传（不影响 record counts）
            if (events != null && !events.isEmpty()) {
                delegate.appendBatch(runId, List.of(), events);
            }
            return new BatchOutcome(total, 0, inserted, failed);
        }
    }

    /**
     * 假 lane：返回固定的 item 列表给 listItemRule.selector 查询；其他 navigate/wait 调用
     * 默认 ok。每个 item 的 preview 走真实 ExtractionPreview（注入的 Spring bean），返回
     * 单字段 title=v-N。
     */
    private static class FakePageHandle implements RunPageHandle {
        private final List<Node> items;

        FakePageHandle(List<Node> items) {
            this.items = items;
        }

        @Override
        public NavigationResult navigateAndAwaitDomContentLoaded(String startUrl) {
            return new NavigationResult(true, 200, false, null);
        }

        @Override
        public boolean waitForSelector(String selector, long timeoutMs) {
            return true;
        }

        @Override
        public void extraWaitSeconds(int seconds) {
        }

        @Override
        public String currentUrl() {
            return "https://example.com/list";
        }

        @Override
        public ExtractionPreview.DomState acquireDomState() {
            return new ExtractionPreview.DomState() {
                @Override
                public String url() {
                    return "https://example.com/list";
                }

                @Override
                public List<Node> query(String selector, SelectorType type) {
                    return items;
                }

                @Override
                public ExtractionPreview.DomState scopeToNode(Node item) {
                    // 假 lane：scopeToNode 返同 dom；preview 走真实 preview bean（注入测试用），
                    // 测试仅校验 partial-fail 终态，不校验 field 内容。
                    return this;
                }
            };
        }

        @Override
        public void close() {
        }
    }
}
