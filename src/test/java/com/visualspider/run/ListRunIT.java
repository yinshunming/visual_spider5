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
 * M4 re-baseline 阶段1 happy-path IT（issue #38 / spec §D8 / §D15）。
 *
 * <p>史上首个真实运行 IT（M3 单页只有 fake 单测）：真实 Playwright + 真实 PG，
 * 证明 list 运行主干经 dispatcher 路由到 {@code ListRunExecutor} 并产出多 item 结果，
 * 暴露 fake-backed 单元测试藏住的真实 bug（如 {@code DomState.scopeToNode} 未实现）。
 *
 * <p>用 JDK 内建 {@link com.sun.net.httpserver.HttpServer} 把 standard-list.html 以
 * {@code http://localhost} 提供（{@link TargetUrlPolicy} 仅允许 http(s)）；
 * JDBC 直插 LIST task + WAITING run，触发 {@code dispatchOnceForTest}，绕过 REST/Readiness/前端。
 *
 * <p>依赖 {@code -Ppg-it -Dpg.it.url=jdbc:postgresql://localhost:5432/visualspider_it
 * -Dpg.it.username=visualspider -Dpg.it.password=visualspider}。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"run.dispatcher.enabled=true"})
@ActiveProfiles("it")
// 本类启用 run.dispatcher.enabled=true，dispatcher 5s 兜底轮询会常驻缓存 context；
// 用 AFTER_CLASS 销毁 context，避免 fallback 线程在后续 IT 中继续 claim/查询 collection_run 干扰其它测试。
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ListRunIT {

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
        Path listDir = Paths.get(ListRunIT.class.getResource("/list/standard-list.html").toURI()).getParent();
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
                Long.class, "it-list", "{noop}list-pwd-12chars");

        TaskDefinition def = listDefinition();
        String definitionJson = objectMapper.writeValueAsString(def);

        taskId = jdbc.queryForObject(
                "INSERT INTO collection_task (owner_id, name, mode, status, schema_version, definition) "
                        + "VALUES (?, 'it-list-task', 'LIST', 'READY', 2, ?::jsonb) RETURNING id",
                Long.class, userId, definitionJson);

        TaskSnapshot snapshot = new TaskSnapshot(taskId, userId, "it-list-task",
                new TaskMode.List(), 2, 1L, def);
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
    @DisplayName("list run happy-path：5 item 全成功 -> SUCCESS + 5 行不同值 + raw=5/dedup=0/final=5/fail=0")
    void listRunHappyPath() throws Exception {
        dispatcher.dispatchOnceForTest();

        String status = pollTerminal(runId, Duration.ofSeconds(40));
        if (!"SUCCESS".equals(status)) {
            throw new AssertionError("run 未达 SUCCESS，终态=" + status + "，事件:\n"
                    + dumpEvents(runId));
        }
        assertStopReason(runId, "COMPLETED");

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT record_count_raw, record_count_dedup, record_count_final, fail_count "
                        + "FROM collection_run WHERE id = ?",
                runId);
        assertThat(row.get("record_count_raw")).isEqualTo(5);
        assertThat(row.get("record_count_dedup")).isEqualTo(0);
        assertThat(row.get("record_count_final")).isEqualTo(5);
        assertThat(row.get("fail_count")).isEqualTo(0);

        List<String> titles = jdbc.queryForList(
                "SELECT data->>'title' FROM run_result WHERE run_id = ? ORDER BY sequence_no ASC",
                String.class, runId);
        assertThat(titles).containsExactly("Alpha", "Beta", "Gamma", "Delta", "Epsilon");

        List<String> counts = jdbc.queryForList(
                "SELECT data->>'count' FROM run_result WHERE run_id = ? ORDER BY sequence_no ASC",
                String.class, runId);
        assertThat(counts).containsExactly("10", "20", "30", "40", "50");

        assertRunEventSequence(runId, "INFO", "LIST_ITER_START",
                "INFO", "LIST_ITEM_EXTRACTED",
                "INFO", "LIST_ITEM_EXTRACTED",
                "INFO", "LIST_ITEM_EXTRACTED",
                "INFO", "LIST_ITEM_EXTRACTED",
                "INFO", "LIST_ITEM_EXTRACTED",
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

    /**
     * 校验 run_event 序列中按 (level, stage) 顺序出现的子序列都被找到；
     * 不要求中间无其他事件，但 level 数组 / stage 数组必须按出现顺序完全匹配。
     */
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
        return listDefinitionFor("standard-list.html");
    }
    private TaskDefinition listDefinitionFor(String fixtureFile) {
        FieldDefinition title = new FieldDefinition("title", FieldSource.VISIBLE_TEXT,
                ".title", null, SelectorType.CSS, ResultType.TEXT, TrimPolicy.TRIM, null, true);
        FieldDefinition date = new FieldDefinition("date", FieldSource.VISIBLE_TEXT,
                ".date", null, SelectorType.CSS, ResultType.TEXT, TrimPolicy.TRIM, null, false);
        FieldDefinition count = new FieldDefinition("count", FieldSource.VISIBLE_TEXT,
                ".count", null, SelectorType.CSS, ResultType.TEXT, TrimPolicy.TRIM, null, false);
        return new TaskDefinition(
                2,
                new TaskMode.List(),
                "http://localhost:" + fixturePort + "/" + fixtureFile,
                Viewport.DEFAULT,
                new WaitPolicy(0),
                new Limits(200, 10_000, Duration.ofMinutes(30)),
                new ListItemRule("tbody > tr", SelectorType.CSS),
                List.of(new UniqueKeyField("title")),
                List.of(title, date, count));
    }
}
