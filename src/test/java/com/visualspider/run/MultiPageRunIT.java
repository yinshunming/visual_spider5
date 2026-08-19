package com.visualspider.run;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.visualspider.run.internal.RunDispatcher;
import com.visualspider.task.domain.FieldDefinition;
import com.visualspider.task.domain.FieldSource;
import com.visualspider.task.domain.Limits;
import com.visualspider.task.domain.ListItemRule;
import com.visualspider.task.domain.NavigationMode;
import com.visualspider.task.domain.PaginationRule;
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
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

/**
 * M5-2 happy-path IT（issue #40 / spec §D4 阶段1 / §D17）。
 *
 * <p>首个真实 Chromium 多页采集 IT：dispatcher → MultiPageRunExecutor → 2 页 × 5 item
 * = 10 record，验证：
 * <ul>
 *   <li>真 PG 五计数：raw=10/dedup=0/final=10/fail=0</li>
 *   <li>终态 SUCCESS + stop_reason=COMPLETED</li>
 *   <li>事件序列含 LIST_PAGE_LOADED ×2 + PAGINATION_CLICKED ×1</li>
 *   <li>10 条 distinct title（uniqueKey=title）按 Alpha..Kappa 顺序</li>
 * </ul>
 *
 * <p>用 JDK 内建 {@link com.sun.net.httpserver.HttpServer} 把 {@code pagination/next-page.html}
 * 以 {@code http://localhost} 提供（{@link TargetUrlPolicy} 仅允许 http(s)）；
 * fixture 用 JS 读取 {@code location.search} 渲染不同页：第 2 页的 next 链接被移除，
 * 自然触发终止信号（NOT_FOUND）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"run.dispatcher.enabled=true"})
@ActiveProfiles("it")
// run.dispatcher.enabled=true 让 dispatcher 5s 兜底轮询常驻；用 AFTER_CLASS 销毁 context，
// 避免 fallback 线程在后续 IT 中继续 claim / 查询 collection_run 干扰其它测试。
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MultiPageRunIT {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RunDispatcher dispatcher;

    private static com.sun.net.httpserver.HttpServer fixtureServer;
    private static int fixturePort;

    private long userId;
    private long taskId;
    private long runId;

    @BeforeAll
    static void startFixtureServer() throws Exception {
        Path paginationDir = Paths.get(MultiPageRunIT.class
                .getResource("/pagination/next-page.html").toURI()).getParent();
        fixtureServer = com.sun.net.httpserver.HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0), 0);
        fixtureServer.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.startsWith("/")) {
                path = path.substring(1);
            }
            Path file = paginationDir.resolve(path).normalize();
            if (!file.startsWith(paginationDir) || !Files.isReadable(file)) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            byte[] body = Files.readAllBytes(file);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (var os = exchange.getResponseBody()) {
                os.write(body);
            }
            exchange.close();
        });
        fixtureServer.start();
        fixturePort = fixtureServer.getAddress().getPort();
    }

    @AfterAll
    static void stopFixtureServer() {
        if (fixtureServer != null) {
            fixtureServer.stop(0);
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("DELETE FROM run_result");
        jdbc.update("DELETE FROM run_event");
        jdbc.update("DELETE FROM collection_run");
        jdbc.update("DELETE FROM collection_task");
        jdbc.update("DELETE FROM app_user WHERE username LIKE 'it-%'");

        userId = jdbc.queryForObject(
                "INSERT INTO app_user (username, password_hash, role, status) "
                        + "VALUES (?, ?, 'COLLECTOR', 'ACTIVE') RETURNING id",
                Long.class, "it-multi", "{noop}multi-pwd-12chars");

        TaskDefinition def = listDefinition();
        String definitionJson = objectMapper.writeValueAsString(def);

        // schemaVersion=3（V3 任务含 paginationRule）；M5-1 已落地 upgrader 此处直写 V3。
        taskId = jdbc.queryForObject(
                "INSERT INTO collection_task (owner_id, name, mode, status, schema_version, definition) "
                        + "VALUES (?, 'it-multi-task', 'LIST', 'READY', 3, ?::jsonb) RETURNING id",
                Long.class, userId, definitionJson);

        TaskSnapshot snapshot = new TaskSnapshot(taskId, userId, "it-multi-task",
                new TaskMode.List(), 3, 1L, def);
        String snapshotJson = objectMapper.writeValueAsString(snapshot);

        runId = jdbc.queryForObject(
                "INSERT INTO collection_run (task_id, owner_id, snapshot, status) "
                        + "VALUES (?, ?, ?::jsonb, 'WAITING') RETURNING id",
                Long.class, taskId, userId, snapshotJson);
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
    @DisplayName("NEXT_PAGE happy-path：2 页 × 5 item = 10 record -> raw=10/dedup=0/final=10/fail=0 + SUCCESS")
    void nextPageHappyPath() throws Exception {
        dispatcher.dispatchOnceForTest();

        String status = pollTerminal(runId, Duration.ofSeconds(60));
        if (!"SUCCESS".equals(status)) {
            throw new AssertionError("run 未达 SUCCESS，终态=" + status + "，事件:\n"
                    + dumpEvents(runId));
        }
        assertStopReason(runId, "COMPLETED");

        java.util.Map<String, Object> row = jdbc.queryForMap(
                "SELECT record_count_raw, record_count_dedup, record_count_final, fail_count "
                        + "FROM collection_run WHERE id = ?",
                runId);
        assertThat(row.get("record_count_raw")).isEqualTo(10);
        assertThat(row.get("record_count_dedup")).isEqualTo(0);
        assertThat(row.get("record_count_final")).isEqualTo(10);
        assertThat(row.get("fail_count")).isEqualTo(0);

        List<String> titles = jdbc.queryForList(
                "SELECT data->>'title' FROM run_result WHERE run_id = ? ORDER BY sequence_no ASC",
                String.class, runId);
        assertThat(titles).containsExactly(
                "Alpha", "Beta", "Gamma", "Delta", "Epsilon",
                "Zeta", "Eta", "Theta", "Iota", "Kappa");

        // 关键事件序列（spec §D17）：两页各发一次 LIST_PAGE_LOADED + 中间一次 PAGINATION_CLICKED。
        // 逐 item 事件不在断言内（与 M4 ListRunExecutor 兼容）。
        assertRunEventSequence(runId,
                "INFO", "LIST_PAGE_LOADED",
                "INFO", "PAGINATION_CLICKED",
                "INFO", "LIST_PAGE_LOADED",
                "INFO", "terminal");
    }

    private void assertStopReason(long runId, String expected) {
        String stopReason = jdbc.queryForObject(
                "SELECT stop_reason FROM collection_run WHERE id = ?",
                String.class, runId);
        assertThat(stopReason).isEqualTo(expected);
    }

    private String dumpEvents(long runId) {
        List<String> events = jdbc.queryForList(
                "SELECT level || '/' || stage || ': ' || message FROM run_event "
                        + "WHERE run_id = ? ORDER BY id",
                String.class, runId);
        return String.join("\n", events);
    }

    /** 校验 run_event 序列中按 (level, stage) 顺序出现的子序列都被找到。 */
    private void assertRunEventSequence(long runId, String... levelAndStagePairs) {
        if (levelAndStagePairs.length % 2 != 0) {
            throw new IllegalArgumentException("levelAndStagePairs 必须成对 (level, stage)");
        }
        List<String> allLevels = jdbc.queryForList(
                "SELECT level FROM run_event WHERE run_id = ? ORDER BY id",
                String.class, runId);
        List<String> allStages = jdbc.queryForList(
                "SELECT stage FROM run_event WHERE run_id = ? ORDER BY id",
                String.class, runId);
        int idx = 0;
        for (int i = 0; i < allLevels.size() && idx < levelAndStagePairs.length; i++) {
            if (allLevels.get(i).equals(levelAndStagePairs[idx * 2])
                    && allStages.get(i).equals(levelAndStagePairs[idx * 2 + 1])) {
                idx++;
            }
        }
        if (idx != levelAndStagePairs.length / 2) {
            throw new AssertionError("run_event 序列不匹配：期望找到 " + (levelAndStagePairs.length / 2)
                    + " 对，实际找到 " + idx + " 对；实际事件:\n" + dumpEvents(runId));
        }
    }

    private String pollTerminal(long runId, Duration timeout) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            String s = jdbc.queryForObject("SELECT status FROM collection_run WHERE id = ?",
                    String.class, runId);
            if (s != null && !s.equals("WAITING") && !s.equals("RUNNING")) {
                return s;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("run " + runId + " 未在 " + timeout + " 内达终态");
    }

    private TaskDefinition listDefinition() {
        FieldDefinition title = new FieldDefinition("title", FieldSource.VISIBLE_TEXT,
                ".title", null, SelectorType.CSS, ResultType.TEXT, TrimPolicy.TRIM, null, true);
        FieldDefinition date = new FieldDefinition("date", FieldSource.VISIBLE_TEXT,
                ".date", null, SelectorType.CSS, ResultType.TEXT, TrimPolicy.TRIM, null, false);
        FieldDefinition count = new FieldDefinition("count", FieldSource.VISIBLE_TEXT,
                ".count", null, SelectorType.CSS, ResultType.TEXT, TrimPolicy.TRIM, null, false);
        // URL 不带 /pagination/ 前缀：fixture HttpServer 已把 paginationDir 当 root，
        // 直接以 <filename> 访问（参考 ListRunIT 模式）。
        return new TaskDefinition(
                3,
                new TaskMode.List(),
                "http://localhost:" + fixturePort + "/next-page.html",
                Viewport.DEFAULT,
                new WaitPolicy(0),
                new Limits(200, 10_000, Duration.ofMinutes(30)),
                new ListItemRule("tbody > tr", SelectorType.CSS),
                List.of(new UniqueKeyField("title")),
                new PaginationRule(NavigationMode.NEXT_PAGE, "a.next"),
                List.of(title, date, count));
    }
}