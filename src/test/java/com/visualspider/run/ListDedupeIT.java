package com.visualspider.run;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.visualspider.run.internal.RunDispatcher;
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
 * List 模式运行内去重 IT（issue #37 / spec §T5 with-duplicates.html，M4-5 #35 首写于
 * {@code ListRunIT}，M4-7 按issue「5 fixture 一对一」拆出独立 IT）。
 *
 * <p>真 Playwright + 真 PG，经 dispatcher 路由：with-duplicates fixture 5 行中 Alpha/Beta
 * 各重复一次（uniqueKey=title）-> raw=5 / dedup=2 / final=3 + LIST_ITEM_DEDUPED 事件 × 2。
 *
 * <p>依赖 {@code -Ppg-it -Dpg.it.url=jdbc:postgresql://localhost:5432/visualspider_it
 * -Dpg.it.username=visualspider -Dpg.it.password=visualspider}。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"run.dispatcher.enabled=true"})
@ActiveProfiles("it")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ListDedupeIT {

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
        Path listDir = Paths.get(ListDedupeIT.class.getResource("/list/with-duplicates.html").toURI()).getParent();
        fixtureServer = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fixtureServer.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.startsWith("/")) {
                path = path.substring(1);
            }
            Path file = listDir.resolve(path).normalize();
            if (!file.startsWith(listDir) || !Files.isReadable(file)) {
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
                Long.class, "it-dedup", "{noop}dedup-pwd-12chars");

        TaskDefinition def = listDefinition();
        taskId = jdbc.queryForObject(
                "INSERT INTO collection_task (owner_id, name, mode, status, schema_version, definition) "
                        + "VALUES (?, 'it-dedup-task', 'LIST', 'READY', 2, ?::jsonb) RETURNING id",
                Long.class, userId, objectMapper.writeValueAsString(def));

        TaskSnapshot snapshot = new TaskSnapshot(taskId, userId, "it-dedup-task",
                new TaskMode.List(), 2, 1L, def);
        runId = jdbc.queryForObject(
                "INSERT INTO collection_run (task_id, owner_id, snapshot, status) "
                        + "VALUES (?, ?, ?::jsonb, 'WAITING') RETURNING id",
                Long.class, taskId, userId, objectMapper.writeValueAsString(snapshot));
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
    @DisplayName("with-duplicates：5 行 2 重复 -> raw=5/dedup=2/final=3 + LIST_ITEM_DEDUPED × 2")
    void listRunWithDuplicates() throws Exception {
        dispatcher.dispatchOnceForTest();
        String status = pollTerminal(runId, Duration.ofSeconds(40));
        if (!"SUCCESS".equals(status)) {
            throw new AssertionError("run 未达 SUCCESS，终态=" + status + "，事件:\n"
                    + dumpEvents(runId));
        }
        assertThat(jdbc.queryForObject(
                "SELECT stop_reason FROM collection_run WHERE id = ?", String.class, runId))
                .isEqualTo("COMPLETED");

        var counts = jdbc.queryForMap(
                "SELECT record_count_raw, record_count_dedup, record_count_final, fail_count "
                        + "FROM collection_run WHERE id = ?",
                runId);
        assertThat(counts.get("record_count_raw")).isEqualTo(5);
        assertThat(counts.get("record_count_dedup")).isEqualTo(2);
        assertThat(counts.get("record_count_final")).isEqualTo(3);
        assertThat(counts.get("fail_count")).isEqualTo(0);

        // 5 行里 2 重复（Alpha + Beta 各重复一次），去重保留第一条 -> 只剩 Alpha/Beta/Gamma
        List<String> titles = jdbc.queryForList(
                "SELECT data->>'title' FROM run_result WHERE run_id = ? ORDER BY sequence_no ASC",
                String.class, runId);
        assertThat(titles).containsExactlyInAnyOrder("Alpha", "Beta", "Gamma");

        Long dedupedCount = jdbc.queryForObject(
                "SELECT count(*) FROM run_event WHERE run_id = ? AND stage = 'LIST_ITEM_DEDUPED'",
                Long.class, runId);
        assertThat(dedupedCount).isEqualTo(2L);
    }

    private String dumpEvents(long runId) {
        List<String> events = jdbc.queryForList(
                "SELECT level || '/' || stage || ': ' || message FROM run_event "
                        + "WHERE run_id = ? ORDER BY id",
                String.class, runId);
        return String.join("\n", events);
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
        return new TaskDefinition(
                2,
                new TaskMode.List(),
                "http://localhost:" + fixturePort + "/with-duplicates.html",
                Viewport.DEFAULT,
                new WaitPolicy(0),
                new Limits(200, 10_000, Duration.ofMinutes(30)),
                new ListItemRule("tbody > tr", SelectorType.CSS),
                List.of(new UniqueKeyField("title")),
                List.of(title, date));
    }
}
