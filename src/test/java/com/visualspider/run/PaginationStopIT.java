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
import java.util.Map;
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
 * 4 个 pagination 停止原因 fixture 各自的独立 Playwright IT（M5-3 / issue #41 / spec §D5 / §D17 / T2）。
 *
 * <p>每个 fixture → 各自独立 IT，断言对应 {@link com.visualspider.run.spi.StopReason}。
 * 共享 {@code pagination/} 目录作为 HttpServer root，端口 0。
 *
 * <p>用例覆盖：
 * <ul>
 *   <li>{@code load-more.html} → SUCCESS + {@code PAGINATION_NO_NEW_ITEMS}（连续 2 次无新增）</li>
 *   <li>{@code no-new-items.html} → SUCCESS + {@code PAGINATION_NO_NEW_ITEMS}（首次点击即无新增）</li>
 *   <li>{@code duplicate-page.html} → SUCCESS + {@code DUPLICATE_PAGE}（URL 变但内容相同）</li>
 *   <li>{@code pagination-disappeared.html} → SUCCESS + {@code PAGINATION_DISAPPEARED}</li>
 * </ul>
 *
 * <p>注：{@code next-page.html} happy-path 由 {@link MultiPageRunIT#nextPageHappyPath} 覆盖（保持
 * M5-2 IT 不退）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"run.dispatcher.enabled=true"})
@ActiveProfiles("it")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PaginationStopIT {

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
        Path paginationDir = Paths.get(PaginationStopIT.class
                .getResource("/pagination/load-more.html").toURI()).getParent();
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
                Long.class, "it-pagestop", "{noop}pagestop-pwd-12chars");

        TaskDefinition def = baseDefinition();
        String definitionJson = objectMapper.writeValueAsString(def);

        taskId = jdbc.queryForObject(
                "INSERT INTO collection_task (owner_id, name, mode, status, schema_version, definition) "
                        + "VALUES (?, 'it-pagestop-task', 'LIST', 'READY', 3, ?::jsonb) RETURNING id",
                Long.class, userId, definitionJson);

        TaskSnapshot snapshot = new TaskSnapshot(taskId, userId, "it-pagestop-task",
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
    @DisplayName("load-more.html：3 次 +3 后封顶 14，再 2 次点击无新增 -> SUCCESS + PAGINATION_NO_NEW_ITEMS")
    void loadMoreHappyPath() throws Exception {
        insertRunWithPagination("button.more", NavigationMode.LOAD_MORE,
                "http://localhost:" + fixturePort + "/load-more.html");

        dispatcher.dispatchOnceForTest();
        pollTerminal("PAGINATION_NO_NEW_ITEMS");

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT record_count_raw, record_count_dedup, record_count_final, fail_count "
                        + "FROM collection_run WHERE id = ?",
                runId);
        // 5 + 3 + 3 + 3 = 14（skipFirstN 让追加页只处理新增，跳过已处理前缀）
        assertThat(row.get("record_count_raw")).isEqualTo(14);
        assertThat(row.get("record_count_final")).isEqualTo(14);
        assertThat(row.get("record_count_dedup")).isEqualTo(0);
        assertThat(row.get("fail_count")).isEqualTo(0);
    }

    @Test
    @DisplayName("no-new-items.html：首次点击即无新增，连点 2 次 -> SUCCESS + PAGINATION_NO_NEW_ITEMS")
    void noNewItemsStopsAfterTwoConsecutive() throws Exception {
        insertRunWithPagination("button.more", NavigationMode.LOAD_MORE,
                "http://localhost:" + fixturePort + "/no-new-items.html");

        dispatcher.dispatchOnceForTest();
        pollTerminal("PAGINATION_NO_NEW_ITEMS");

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT record_count_final FROM collection_run WHERE id = ?",
                runId);
        // 始终只有初始 5 条；后续 2 次无新增点击不产生新 record
        assertThat(row.get("record_count_final")).isEqualTo(5);
    }

    @Test
    @DisplayName("duplicate-page.html：URL 变但内容同 -> SUCCESS + DUPLICATE_PAGE（首只 1 页写入）")
    void duplicatePageStopsOnContentHash() throws Exception {
        insertRunWithPagination("a.next", NavigationMode.NEXT_PAGE,
                "http://localhost:" + fixturePort + "/duplicate-page.html");

        dispatcher.dispatchOnceForTest();
        pollTerminal("DUPLICATE_PAGE");

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT record_count_final FROM collection_run WHERE id = ?",
                runId);
        // 重复页保护触发在第 2 页处理前：仅第 1 页 5 条记录写入
        assertThat(row.get("record_count_final")).isEqualTo(5);
    }

    @Test
    @DisplayName("pagination-disappeared.html：点击 1 次后按钮消失 -> SUCCESS + PAGINATION_DISAPPEARED")
    void paginationDisappeared() throws Exception {
        insertRunWithPagination("button.more", NavigationMode.LOAD_MORE,
                "http://localhost:" + fixturePort + "/pagination-disappeared.html");

        dispatcher.dispatchOnceForTest();
        pollTerminal("PAGINATION_DISAPPEARED");

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT record_count_final FROM collection_run WHERE id = ?",
                runId);
        // 5 初始 + 3 新增 = 8（skipFirstN=5 跳过初始前缀，只处理新增 3 条）
        assertThat(row.get("record_count_final")).isEqualTo(8);
    }

    private void insertRunWithPagination(String selector, NavigationMode mode, String startUrl)
            throws Exception {
        PaginationRule pagination = new PaginationRule(mode, selector);
        TaskDefinition def = new TaskDefinition(
                3,
                new TaskMode.List(),
                startUrl,
                Viewport.DEFAULT,
                new WaitPolicy(0),
                new Limits(200, 10_000, Duration.ofMinutes(30)),
                new ListItemRule("tbody > tr", SelectorType.CSS),
                List.of(new UniqueKeyField("title")),
                pagination,
                List.of(
                        new FieldDefinition("title", FieldSource.VISIBLE_TEXT,
                                ".title", null, SelectorType.CSS, ResultType.TEXT,
                                TrimPolicy.TRIM, null, true),
                        new FieldDefinition("date", FieldSource.VISIBLE_TEXT,
                                ".date", null, SelectorType.CSS, ResultType.TEXT,
                                TrimPolicy.TRIM, null, false),
                        new FieldDefinition("count", FieldSource.VISIBLE_TEXT,
                                ".count", null, SelectorType.CSS, ResultType.TEXT,
                                TrimPolicy.TRIM, null, false)));
        String json = objectMapper.writeValueAsString(def);
        jdbc.update("UPDATE collection_task SET definition = ?::jsonb WHERE id = ?", json, taskId);

        TaskSnapshot snapshot = new TaskSnapshot(taskId, userId, "it-pagestop-task",
                new TaskMode.List(), 3, 1L, def);
        String snapshotJson = objectMapper.writeValueAsString(snapshot);
        jdbc.update("UPDATE collection_run SET snapshot = ?::jsonb WHERE id = ?",
                snapshotJson, runId);
    }

    private TaskDefinition baseDefinition() {
        // placeholder（被 insertRunWithPagination 覆盖）；保留 SELECT/INSERT 形态对齐 MultiPageRunIT
        return new TaskDefinition(
                3,
                new TaskMode.List(),
                "http://localhost:" + fixturePort + "/next-page.html",
                Viewport.DEFAULT,
                new WaitPolicy(0),
                new Limits(200, 10_000, Duration.ofMinutes(30)),
                new ListItemRule("tbody > tr", SelectorType.CSS),
                List.of(new UniqueKeyField("title")),
                null,
                List.of(new FieldDefinition("title", FieldSource.VISIBLE_TEXT,
                        ".title", null, SelectorType.CSS, ResultType.TEXT,
                        TrimPolicy.TRIM, null, true)));
    }

    private void pollTerminal(String expectedStopReason) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 60_000L;
        while (System.currentTimeMillis() < deadline) {
            String status = jdbc.queryForObject(
                    "SELECT status FROM collection_run WHERE id = ?", String.class, runId);
            if (status != null && !status.equals("WAITING") && !status.equals("RUNNING")) {
                String stopReason = jdbc.queryForObject(
                        "SELECT stop_reason FROM collection_run WHERE id = ?",
                        String.class, runId);
                assertThat(stopReason).isEqualTo(expectedStopReason);
                return;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("run " + runId + " 未在 60s 内达终态（期望 stop_reason="
                + expectedStopReason + "）");
    }
}