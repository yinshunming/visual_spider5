package com.visualspider.result;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.visualspider.identity.domain.ActorId;
import com.visualspider.identity.spi.IdentityAccess;
import com.visualspider.result.spi.RunAccessDeniedException;
import com.visualspider.result.spi.RunExport;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PGobject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * RunExport PostgreSQL 集成测试（spec §D13 / T5）。
 *
 * <p>10k fixture 行 CSV/JSON 流式；断言行数、首尾行；admin 全局访问；
 * 非 owner -> {@link RunAccessDeniedException}。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("it")
class RunExportIT {

    private static final int TOTAL = 10_000;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RunExport runExport;

    @MockBean
    private IdentityAccess identityAccess;

    private long aliceUserId;
    private long adminUserId;
    private long aliceRunId;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("DELETE FROM run_result");
        jdbc.update("DELETE FROM run_event");
        jdbc.update("DELETE FROM collection_run");
        jdbc.update("DELETE FROM collection_task");
        jdbc.update("DELETE FROM app_user WHERE username LIKE 'it-%'");

        aliceUserId = insertUser("it-alice", "alice-pwd-12+chars", "COLLECTOR");
        adminUserId = insertUser("it-admin", "admin-pwd-12+chars", "ADMIN");

        long aliceTaskId = jdbc.queryForObject(
                "INSERT INTO collection_task (owner_id, name, mode, status, schema_version, definition) "
                        + "VALUES (?, 'alice-task', 'SINGLE_PAGE', 'READY', 1, ?::jsonb) RETURNING id",
                Long.class,
                aliceUserId,
                taskJson());

        aliceRunId = jdbc.queryForObject(
                "INSERT INTO collection_run (task_id, owner_id, snapshot, status) "
                        + "VALUES (?, ?, ?::jsonb, 'WAITING') RETURNING id",
                Long.class, aliceTaskId, aliceUserId, snapshotJson());

        // 批量插入 10k 行
        jdbc.batchUpdate("INSERT INTO run_result (run_id, sequence_no, data) VALUES (?, ?, ?::jsonb)",
                new org.springframework.jdbc.core.BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int i) throws java.sql.SQLException {
                        ps.setLong(1, aliceRunId);
                        ps.setInt(2, i);
                        ps.setObject(3, jsonb(Map.of("title", "row-" + i, "url", "https://x/" + i,
                                "score", String.valueOf(i))));
                    }
                    @Override
                    public int getBatchSize() {
                        return TOTAL;
                    }
                });
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
    @DisplayName("CSV: 10k 行流式输出；行数=10000 + 表头；首末行匹配")
    void csvTenThousandRows() throws Exception {
        when(identityAccess.canAccessTask(anyLong(), any())).thenAnswer(inv -> {
            long ownerId = inv.getArgument(0);
            ActorId actor = inv.getArgument(1);
            return actor.value() == ownerId;
        });

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        runExport.writeCsv(aliceRunId, new ActorId(aliceUserId), out);

        String csv = out.toString(StandardCharsets.UTF_8);
        String[] lines = csv.split("\n");
        assertThat(lines).hasSize(TOTAL + 1); // 1 header + 10000 data
        assertThat(lines[0]).isEqualTo("title,url,score");
        assertThat(lines[1]).isEqualTo("row-0,https://x/0,0");
        assertThat(lines[TOTAL]).isEqualTo("row-" + (TOTAL - 1) + ",https://x/" + (TOTAL - 1) + "," + (TOTAL - 1));
    }

    @Test
    @DisplayName("JSON: 10k 行流式输出；顶层数组 10000 元素；首末元素匹配")
    void jsonTenThousandRows() throws Exception {
        when(identityAccess.canAccessTask(anyLong(), any())).thenAnswer(inv -> {
            long ownerId = inv.getArgument(0);
            ActorId actor = inv.getArgument(1);
            return actor.value() == ownerId;
        });

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        runExport.writeJson(aliceRunId, new ActorId(aliceUserId), out);

        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(out.toByteArray());
        assertThat(root.isArray()).isTrue();
        assertThat(root.size()).isEqualTo(TOTAL);
        assertThat(root.get(0).get("title").asText()).isEqualTo("row-0");
        assertThat(root.get(TOTAL - 1).get("title").asText()).isEqualTo("row-" + (TOTAL - 1));
    }

    @Test
    @DisplayName("admin 可导出任意 run")
    void adminExports() throws Exception {
        when(identityAccess.canAccessTask(anyLong(), any())).thenReturn(true);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        runExport.writeCsv(aliceRunId, new ActorId(adminUserId), out);

        String csv = out.toString(StandardCharsets.UTF_8);
        assertThat(csv.split("\n")).hasSize(TOTAL + 1);
    }

    @Test
    @DisplayName("非 owner -> RunAccessDeniedException")
    void deniedForNonOwner() {
        // 创建 bob
        long bobUserId = insertUser("it-bob", "bob-pwd-12+chars-extra", "COLLECTOR");
        when(identityAccess.canAccessTask(anyLong(), any())).thenAnswer(inv -> {
            long ownerId = inv.getArgument(0);
            ActorId actor = inv.getArgument(1);
            return actor.value() == ownerId;
        });

        assertThatThrownBy(() -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            runExport.writeCsv(aliceRunId, new ActorId(bobUserId), out);
        }).isInstanceOf(RunAccessDeniedException.class);
    }

    // ============================ helpers ============================

    private long insertUser(String username, String password, String role) {
        return jdbc.queryForObject(
                "INSERT INTO app_user (username, password_hash, role, status) "
                        + "VALUES (?, ?, ?, 'ACTIVE') RETURNING id",
                Long.class, username, "{noop}" + password, role);
    }

    private PGobject jsonb(Map<String, String> data) {
        try {
            PGobject jsonb = new PGobject();
            jsonb.setType("jsonb");
            jsonb.setValue(new ObjectMapper().writeValueAsString(data));
            return jsonb;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
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
                + "\"resultType\":\"TEXT\",\"trim\":\"TRIM\",\"regex\":null,\"required\":true},"
                + "{\"name\":\"url\",\"source\":\"VISIBLE_TEXT\",\"selector\":\"a\","
                + "\"attributeName\":null,\"selectorType\":\"CSS\",\"resultType\":\"TEXT\","
                + "\"trim\":\"TRIM\",\"regex\":null,\"required\":false},"
                + "{\"name\":\"score\",\"source\":\"VISIBLE_TEXT\",\"selector\":\"span\","
                + "\"attributeName\":null,\"selectorType\":\"CSS\",\"resultType\":\"TEXT\","
                + "\"trim\":\"TRIM\",\"regex\":null,\"required\":false}]}";
    }
}