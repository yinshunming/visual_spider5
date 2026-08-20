package com.visualspider.run;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.visualspider.run.internal.RunDispatcher;
import com.visualspider.task.domain.FieldDefinition;
import com.visualspider.task.domain.FieldKind;
import com.visualspider.task.domain.FieldScope;
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
 * M5-4 内容页主链路 IT（issue #42 / spec §D6 / §D7 / T2）。
 *
 * <p>覆盖：
 * <ul>
 *   <li>{@code standard-list-with-content.html}：5 list item + 5 content page；
 *       字段合并后 record 包含 list 字段 + content 字段；
 *       content_fail_count=0；SUCCESS。</li>
 *   <li>{@code content-blank.html}：5 list item，每项内容页 URL 被
 *       {@code BasicTargetUrlPolicy} 拒（{@code data:} scheme）-> URL_NOT_ALLOWED；
 *       list 字段保留，content 字段 null；content_fail_count=5；SUCCESS。</li>
 * </ul>
 *
 * <p>HttpServer 以 {@code content-page/} 目录为 root；list 字段与 content 字段共名时按 name
 * 合并到同一 record（spec §D6）；PARTIAL_SUCCESS 不受 content fail 影响（spec §D7）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"run.dispatcher.enabled=true"})
@ActiveProfiles("it")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ContentPageRunIT {

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
        Path contentPageDir = Paths.get(ContentPageRunIT.class
                .getResource("/content-page/standard-list-with-content.html").toURI()).getParent();
        fixtureServer = com.sun.net.httpserver.HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0), 0);
        fixtureServer.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.startsWith("/")) {
                path = path.substring(1);
            }
            Path file = contentPageDir.resolve(path).normalize();
            if (!file.startsWith(contentPageDir) || !Files.isReadable(file)) {
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
                Long.class, "it-content", "{noop}content-pwd-12chars");

        TaskDefinition def = listDefinition("http://localhost:" + fixturePort
                + "/standard-list-with-content.html");
        String definitionJson = objectMapper.writeValueAsString(def);

        taskId = jdbc.queryForObject(
                "INSERT INTO collection_task (owner_id, name, mode, status, schema_version, definition) "
                        + "VALUES (?, 'it-content-task', 'LIST', 'READY', 3, ?::jsonb) RETURNING id",
                Long.class, userId, definitionJson);

        TaskSnapshot snapshot = new TaskSnapshot(taskId, userId, "it-content-task",
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
    @DisplayName("standard-list-with-content.html：5 item + 5 content -> final=5 + content_fail=0 + SUCCESS")
    void contentPageHappyPath() throws Exception {
        dispatcher.dispatchOnceForTest();
        pollTerminal("COMPLETED");

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT record_count_raw, record_count_dedup, record_count_final, fail_count, "
                        + "content_fail_count FROM collection_run WHERE id = ?",
                runId);
        assertThat(row.get("record_count_final")).isEqualTo(5);
        assertThat(row.get("record_count_dedup")).isEqualTo(0);
        assertThat(row.get("record_count_raw")).isEqualTo(5);
        assertThat(row.get("fail_count")).isEqualTo(0);
        assertThat(row.get("content_fail_count")).isEqualTo(0);

        // 每条 record 应包含 list 字段 (title/date/count) + content 字段 (price/date)
        // 字段合并按 name；date 在 list 与 content 共名 -> content 值覆盖（list 仅保留 content 值）。
        Map<String, String> firstRecord = jdbc.queryForMap(
                "SELECT data FROM run_result WHERE run_id = ? ORDER BY sequence_no LIMIT 1",
                String.class, runId) instanceof Map ? null : null;
        List<Map<String, Object>> records = jdbc.queryForList(
                "SELECT data FROM run_result WHERE run_id = ? ORDER BY sequence_no ASC",
                runId);
        assertThat(records).hasSize(5);
        Map<String, Object> first = records.get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) first.get("data");
        assertThat(data).containsKeys("title", "price", "date", "count");
        assertThat(String.valueOf(data.get("title"))).isEqualTo("Item 1");
        assertThat(String.valueOf(data.get("price"))).isEqualTo("$10.50");
        assertThat(String.valueOf(data.get("date"))).isEqualTo("2024-07-01");
    }

    @Test
    @DisplayName("content-blank.html：5 item 内容页 URL_NOT_ALLOWED -> final=5 + content_fail=5 + SUCCESS")
    void contentBlankKeepsListFieldsAndCountsFailures() throws Exception {
        // 改任务 startUrl 为 content-blank.html
        TaskDefinition def = listDefinition("http://localhost:" + fixturePort
                + "/content-blank.html");
        String json = objectMapper.writeValueAsString(def);
        jdbc.update("UPDATE collection_task SET definition = ?::jsonb WHERE id = ?", json, taskId);
        TaskSnapshot snapshot = new TaskSnapshot(taskId, userId, "it-content-task",
                new TaskMode.List(), 3, 1L, def);
        jdbc.update("UPDATE collection_run SET snapshot = ?::jsonb WHERE id = ?",
                objectMapper.writeValueAsString(snapshot), runId);

        dispatcher.dispatchOnceForTest();
        pollTerminal("COMPLETED");

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT record_count_raw, record_count_final, fail_count, content_fail_count "
                        + "FROM collection_run WHERE id = ?",
                runId);
        // list 字段全部写入；content 字段缺失；content_fail_count 累加 5
        assertThat(row.get("record_count_final")).isEqualTo(5);
        assertThat(row.get("record_count_raw")).isEqualTo(5);
        assertThat(row.get("fail_count")).isEqualTo(0);
        assertThat(row.get("content_fail_count")).isEqualTo(5);

        // 每条 record 只有 list 字段（title/date/count），无 price
        List<Map<String, Object>> records = jdbc.queryForList(
                "SELECT data FROM run_result WHERE run_id = ? ORDER BY sequence_no ASC",
                runId);
        assertThat(records).hasSize(5);
        for (Map<String, Object> r : records) {
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) r.get("data");
            assertThat(data).containsKeys("title", "date", "count");
            assertThat(data).doesNotContainKey("price");
        }
    }

    private TaskDefinition listDefinition(String startUrl) {
        FieldDefinition title = new FieldDefinition("title", FieldSource.VISIBLE_TEXT,
                ".title", null, SelectorType.CSS, ResultType.TEXT, TrimPolicy.TRIM, null, true,
                FieldScope.LIST, FieldKind.LIST_VALUE);
        FieldDefinition date = new FieldDefinition("date", FieldSource.VISIBLE_TEXT,
                ".date", null, SelectorType.CSS, ResultType.TEXT, TrimPolicy.TRIM, null, false,
                FieldScope.LIST, FieldKind.LIST_VALUE);
        FieldDefinition count = new FieldDefinition("count", FieldSource.VISIBLE_TEXT,
                ".count", null, SelectorType.CSS, ResultType.TEXT, TrimPolicy.TRIM, null, false,
                FieldScope.LIST, FieldKind.LIST_VALUE);
        // 内容页入口：LIST_CONTENT_LINK（每 <tr> 内首个 <a.title> 的 href）
        FieldDefinition link = new FieldDefinition("link", FieldSource.ATTRIBUTE,
                "a.title", "href", SelectorType.CSS, ResultType.TEXT, TrimPolicy.TRIM, null,
                false, FieldScope.LIST, FieldKind.LIST_CONTENT_LINK);
        // 内容页字段：CONTENT + CONTENT_VALUE
        FieldDefinition price = new FieldDefinition("price", FieldSource.VISIBLE_TEXT,
                ".price", null, SelectorType.CSS, ResultType.TEXT, TrimPolicy.TRIM, null, false,
                FieldScope.CONTENT, FieldKind.CONTENT_VALUE);
        FieldDefinition contentDate = new FieldDefinition("date", FieldSource.VISIBLE_TEXT,
                ".date", null, SelectorType.CSS, ResultType.TEXT, TrimPolicy.TRIM, null, false,
                FieldScope.CONTENT, FieldKind.CONTENT_VALUE);
        return new TaskDefinition(
                3,
                new TaskMode.List(),
                startUrl,
                Viewport.DEFAULT,
                new WaitPolicy(0),
                new Limits(200, 10_000, Duration.ofMinutes(30)),
                new ListItemRule("tbody > tr", SelectorType.CSS),
                List.of(new UniqueKeyField("title")),
                null,
                List.of(title, date, count, link, price, contentDate));
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