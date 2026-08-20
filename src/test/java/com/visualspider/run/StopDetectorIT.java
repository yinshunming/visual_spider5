package com.visualspider.run;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
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
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.HashMap;
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
 * M5-5 限速 + 停止检测 IT（issue #43 / spec §D9 / §D10 / T3）。
 *
 * <p>覆盖：
 * <ul>
 *   <li>{@code stop-429.html} -> HTTP 429 + FAILED</li>
 *   <li>{@code stop-403.html} -> 持续 403 + FAILED</li>
 *   <li>{@code stop-captcha.html} -> reCAPTCHA iframe + CAPTCHA + FAILED</li>
 * </ul>
 *
 * <p>{@code pacing-test.html}（同域间隔 ≥ 1s）由 {@link PacingPolicyIT} 独立覆盖。
 *
 * <p>HttpServer 自定义：按路径返回指定状态码；其余路径返回 404。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"run.dispatcher.enabled=true"})
@ActiveProfiles("it")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class StopDetectorIT {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RunDispatcher dispatcher;

    private static HttpServer fixtureServer;
    private static int fixturePort;

    private long userId;
    private long taskId;
    private long runId;

    @BeforeAll
    static void startFixtureServer() throws Exception {
        Path paginationDir = Paths.get(StopDetectorIT.class
                .getResource("/pagination/load-more.html").toURI()).getParent();
        Map<String, Integer> statusByName = new HashMap<>();
        statusByName.put("stop-429.html", 429);
        statusByName.put("stop-403.html", 403);
        statusByName.put("stop-captcha.html", 200);
        fixtureServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fixtureServer.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.startsWith("/")) {
                path = path.substring(1);
            }
            String fileName = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
            int status = statusByName.getOrDefault(fileName, 404);
            Path file = paginationDir.resolve(path).normalize();
            byte[] body = (status == 200 && file.startsWith(paginationDir) && Files.isReadable(file))
                    ? Files.readAllBytes(file)
                    : new byte[0];
            if (status != 200) {
                exchange.sendResponseHeaders(status, -1);
            } else {
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                exchange.sendResponseHeaders(status, body.length);
                try (var os = exchange.getResponseBody()) {
                    os.write(body);
                }
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
    void setUp() throws IOException {
        jdbc.update("DELETE FROM run_result");
        jdbc.update("DELETE FROM run_event");
        jdbc.update("DELETE FROM collection_run");
        jdbc.update("DELETE FROM collection_task");
        jdbc.update("DELETE FROM app_user WHERE username LIKE 'it-%'");

        userId = jdbc.queryForObject(
                "INSERT INTO app_user (username, password_hash, role, status) "
                        + "VALUES (?, ?, 'COLLECTOR', 'ACTIVE') RETURNING id",
                Long.class, "it-stop", "{noop}stop-pwd-12chars");

        TaskDefinition def = baseDefinition("http://localhost:" + fixturePort + "/next-page.html");
        String json = objectMapper.writeValueAsString(def);
        taskId = jdbc.queryForObject(
                "INSERT INTO collection_task (owner_id, name, mode, status, schema_version, definition) "
                        + "VALUES (?, 'it-stop-task', 'LIST', 'READY', 3, ?::jsonb) RETURNING id",
                Long.class, userId, json);

        TaskSnapshot snap = new TaskSnapshot(taskId, userId, "it-stop-task",
                new TaskMode.List(), 3, 1L, def);
        runId = jdbc.queryForObject(
                "INSERT INTO collection_run (task_id, owner_id, snapshot, status) "
                        + "VALUES (?, ?, ?::jsonb, 'WAITING') RETURNING id",
                Long.class, taskId, userId, objectMapper.writeValueAsString(snap));
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
    @DisplayName("stop-429.html -> HTTP 429 + FAILED + STOP_429 事件")
    void stopOnHttp429() throws Exception {
        rebindTo("http://localhost:" + fixturePort + "/stop-429.html");
        dispatcher.dispatchOnceForTest();
        pollTerminal();
        assertStopReason("HTTP_429");
        assertStopEventPresent("STOP_429");
    }

    @Test
    @DisplayName("stop-403.html -> HTTP 403 + FAILED + STOP_403_PERSISTENT 事件")
    void stopOnPersistentHttp403() throws Exception {
        rebindTo("http://localhost:" + fixturePort + "/stop-403.html");
        dispatcher.dispatchOnceForTest();
        pollTerminal();
        assertStopReason("HTTP_403");
        assertStopEventPresent("STOP_403_PERSISTENT");
    }

    @Test
    @DisplayName("stop-captcha.html -> CAPTCHA + FAILED + STOP_CAPTCHA 事件")
    void stopOnCaptcha() throws Exception {
        rebindTo("http://localhost:" + fixturePort + "/stop-captcha.html");
        dispatcher.dispatchOnceForTest();
        pollTerminal();
        assertStopReason("CAPTCHA");
        assertStopEventPresent("STOP_CAPTCHA");
    }

    private void rebindTo(String startUrl) throws Exception {
        TaskDefinition def = baseDefinition(startUrl);
        String json = objectMapper.writeValueAsString(def);
        jdbc.update("UPDATE collection_task SET definition = ?::jsonb WHERE id = ?", json, taskId);
        TaskSnapshot snap = new TaskSnapshot(taskId, userId, "it-stop-task",
                new TaskMode.List(), 3, 1L, def);
        jdbc.update("UPDATE collection_run SET snapshot = ?::jsonb WHERE id = ?",
                objectMapper.writeValueAsString(snap), runId);
    }

    private void pollTerminal() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 60_000L;
        while (System.currentTimeMillis() < deadline) {
            String status = jdbc.queryForObject(
                    "SELECT status FROM collection_run WHERE id = ?", String.class, runId);
            if (status != null && !status.equals("WAITING") && !status.equals("RUNNING")) {
                return;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("run " + runId + " 未在 60s 内达终态");
    }

    private void assertStopReason(String expected) {
        String stopReason = jdbc.queryForObject(
                "SELECT stop_reason FROM collection_run WHERE id = ?", String.class, runId);
        String status = jdbc.queryForObject(
                "SELECT status FROM collection_run WHERE id = ?", String.class, runId);
        assertThat(status).isEqualTo("FAILED");
        assertThat(stopReason).isEqualTo(expected);
    }

    private void assertStopEventPresent(String stage) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM run_event WHERE run_id = ? AND stage = ?",
                Integer.class, runId, stage);
        assertThat(count).isGreaterThanOrEqualTo(1);
    }

    private static TaskDefinition baseDefinition(String startUrl) {
        FieldDefinition title = new FieldDefinition("title", FieldSource.VISIBLE_TEXT,
                ".title", null, SelectorType.CSS, ResultType.TEXT, TrimPolicy.TRIM, null, true);
        FieldDefinition date = new FieldDefinition("date", FieldSource.VISIBLE_TEXT,
                ".date", null, SelectorType.CSS, ResultType.TEXT, TrimPolicy.TRIM, null, false);
        FieldDefinition count = new FieldDefinition("count", FieldSource.VISIBLE_TEXT,
                ".count", null, SelectorType.CSS, ResultType.TEXT, TrimPolicy.TRIM, null, false);
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
                List.of(title, date, count));
    }
}