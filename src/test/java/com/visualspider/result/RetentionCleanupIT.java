package com.visualspider.result;

import static org.assertj.core.api.Assertions.assertThat;

import com.visualspider.result.spi.RetentionCleanup;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PGobject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * RetentionCleanup PostgreSQL 集成测试（spec §D14 / T2）。
 *
 * <p>覆盖：
 * <ul>
 *   <li>31 天前已完成的 run 被删除</li>
 *   <li>FK 级联删 run_result / run_event</li>
 *   <li>未完成 run（{@code finished_at IS NULL}）不删</li>
 *   <li>30 天内不删</li>
 *   <li>{@code collection_task} 保留</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("it")
class RetentionCleanupIT {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RetentionCleanup retentionCleanup;

    private long aliceUserId;
    private long aliceTaskId;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM run_result");
        jdbc.update("DELETE FROM run_event");
        jdbc.update("DELETE FROM collection_run");
        jdbc.update("DELETE FROM collection_task");
        jdbc.update("DELETE FROM app_user WHERE username LIKE 'it-%'");

        aliceUserId = insertUser("it-alice", "alice-pwd-12+chars", "COLLECTOR");
        aliceTaskId = jdbc.queryForObject(
                "INSERT INTO collection_task (owner_id, name, mode, status, schema_version, definition) "
                        + "VALUES (?, 'alice-task', 'SINGLE_PAGE', 'READY', 1, ?::jsonb) RETURNING id",
                Long.class,
                aliceUserId,
                taskJson());
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
    @DisplayName("31 天前已完成 run 被删除 + 级联 result/event")
    void deleteExpiredRuns() {
        Instant now = Instant.parse("2026-06-01T00:00:00Z");
        Instant oldFinish = now.minus(31, ChronoUnit.DAYS);
        Instant recentFinish = now.minus(10, ChronoUnit.DAYS);

        long oldRun = insertRun(aliceUserId, aliceTaskId, oldFinish, "SUCCESS");
        long recentRun = insertRun(aliceUserId, aliceTaskId, recentFinish, "SUCCESS");

        // 给两个 run 各插 3 条 result + 2 条 event
        insertResult(oldRun, 0, Map.of("title", "old-0"));
        insertResult(oldRun, 1, Map.of("title", "old-1"));
        insertResult(oldRun, 2, Map.of("title", "old-2"));
        insertResult(recentRun, 0, Map.of("title", "recent-0"));
        insertResult(recentRun, 1, Map.of("title", "recent-1"));
        insertResult(recentRun, 2, Map.of("title", "recent-2"));
        insertEvent(oldRun, "INFO", "old event 1");
        insertEvent(oldRun, "INFO", "old event 2");
        insertEvent(recentRun, "INFO", "recent event 1");
        insertEvent(recentRun, "INFO", "recent event 2");

        int deleted = retentionCleanup.deleteExpired(now);
        assertThat(deleted).isEqualTo(1);

        // oldRun 没了
        Integer oldCount = jdbc.queryForObject(
                "SELECT count(*) FROM collection_run WHERE id = ?", Integer.class, oldRun);
        assertThat(oldCount).isZero();
        // 级联删 result/event
        Integer oldResultCount = jdbc.queryForObject(
                "SELECT count(*) FROM run_result WHERE run_id = ?", Integer.class, oldRun);
        assertThat(oldResultCount).isZero();
        Integer oldEventCount = jdbc.queryForObject(
                "SELECT count(*) FROM run_event WHERE run_id = ?", Integer.class, oldRun);
        assertThat(oldEventCount).isZero();

        // recentRun 还在
        Integer recentCount = jdbc.queryForObject(
                "SELECT count(*) FROM collection_run WHERE id = ?", Integer.class, recentRun);
        assertThat(recentCount).isEqualTo(1);
        // recentRun 的 result/event 还在
        Integer recentResultCount = jdbc.queryForObject(
                "SELECT count(*) FROM run_result WHERE run_id = ?", Integer.class, recentRun);
        assertThat(recentResultCount).isEqualTo(3);

        // task 保留
        Integer taskCount = jdbc.queryForObject(
                "SELECT count(*) FROM collection_task WHERE id = ?", Integer.class, aliceTaskId);
        assertThat(taskCount).isEqualTo(1);
    }

    @Test
    @DisplayName("未完成 run（finished_at IS NULL）即使很久前也不删")
    void unfinishedRunsNotDeleted() {
        Instant now = Instant.parse("2026-06-01T00:00:00Z");
        long unfinishedRun = insertRunUnfinished(aliceUserId, aliceTaskId, "WAITING");
        insertResult(unfinishedRun, 0, Map.of("title", "x"));

        int deleted = retentionCleanup.deleteExpired(now);
        assertThat(deleted).isZero();

        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM collection_run WHERE id = ?", Integer.class, unfinishedRun);
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("边界：30 天零 1 秒前的已完成 run 被删；正好 30 天前的不删")
    void boundaryJustOver30Days() {
        Instant now = Instant.parse("2026-06-01T00:00:00Z");
        // 30 天零 1 秒前 -> 应删
        Instant justOver = now.minus(30, ChronoUnit.DAYS).minusSeconds(1);
        // 30 天前 - 1 秒（即 now - 30d + 1s）-> 不删
        Instant justUnder = now.minus(30, ChronoUnit.DAYS).plusSeconds(1);

        long oldRun = insertRun(aliceUserId, aliceTaskId, justOver, "SUCCESS");
        long borderlineRun = insertRun(aliceUserId, aliceTaskId, justUnder, "SUCCESS");

        int deleted = retentionCleanup.deleteExpired(now);
        assertThat(deleted).isEqualTo(1);

        Integer oldCount = jdbc.queryForObject(
                "SELECT count(*) FROM collection_run WHERE id = ?", Integer.class, oldRun);
        assertThat(oldCount).isZero();
        Integer borderlineCount = jdbc.queryForObject(
                "SELECT count(*) FROM collection_run WHERE id = ?", Integer.class, borderlineRun);
        assertThat(borderlineCount).isEqualTo(1);
    }

    @Test
    @DisplayName("0 行过期返回 0")
    void noExpiredRunsReturnsZero() {
        Instant now = Instant.parse("2026-06-01T00:00:00Z");
        int deleted = retentionCleanup.deleteExpired(now);
        assertThat(deleted).isZero();
    }

    // ============================ helpers ============================

    private long insertUser(String username, String password, String role) {
        return jdbc.queryForObject(
                "INSERT INTO app_user (username, password_hash, role, status) "
                        + "VALUES (?, ?, ?, 'ACTIVE') RETURNING id",
                Long.class, username, "{noop}" + password, role);
    }

    private long insertRun(long ownerId, long taskId, Instant finishedAt, String status) {
        return jdbc.queryForObject(
                "INSERT INTO collection_run (task_id, owner_id, snapshot, status, started_at, finished_at) "
                        + "VALUES (?, ?, ?::jsonb, ?, ?, ?) RETURNING id",
                Long.class, taskId, ownerId, snapshotJson(), status,
                Timestamp.from(finishedAt.minusSeconds(60)),
                Timestamp.from(finishedAt));
    }

    private long insertRunUnfinished(long ownerId, long taskId, String status) {
        return jdbc.queryForObject(
                "INSERT INTO collection_run (task_id, owner_id, snapshot, status) "
                        + "VALUES (?, ?, ?::jsonb, ?) RETURNING id",
                Long.class, taskId, ownerId, snapshotJson(), status);
    }

    private void insertResult(long runId, int seq, Map<String, String> data) {
        try {
            PGobject jsonb = new PGobject();
            jsonb.setType("jsonb");
            jsonb.setValue(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(data));
            jdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement(
                        "INSERT INTO run_result (run_id, sequence_no, data) VALUES (?, ?, ?)");
                ps.setLong(1, runId);
                ps.setInt(2, seq);
                ps.setObject(3, jsonb);
                return ps;
            });
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void insertEvent(long runId, String level, String message) {
        jdbc.update("INSERT INTO run_event (run_id, level, message) VALUES (?, ?, ?)",
                runId, level, message);
    }

    private String snapshotJson() {
        return "{\"taskId\":1,\"ownerId\":1,\"name\":\"x\",\"mode\":\"SINGLE_PAGE\","
                + "\"schemaVersion\":1,\"version\":1,\"definition\":" + taskJson() + "}";
    }

    private String taskJson() {
        return "{\"schemaVersion\":1,\"mode\":\"SINGLE_PAGE\",\"startUrl\":\"https://example.com\","
                + "\"viewport\":{\"width\":1280,\"height\":720},"
                + "\"waitPolicy\":{\"extraWaitSeconds\":0},"
                + "\"fields\":[{\"name\":\"title\",\"source\":\"VISIBLE_TEXT\","
                + "\"selector\":\"h1\",\"attributeName\":null,\"selectorType\":\"CSS\","
                + "\"resultType\":\"TEXT\",\"trim\":\"TRIM\",\"regex\":null,\"required\":true}]}";
    }
}