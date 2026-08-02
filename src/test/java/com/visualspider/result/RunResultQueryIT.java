package com.visualspider.result;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import com.visualspider.identity.domain.ActorId;
import com.visualspider.identity.spi.IdentityAccess;
import com.visualspider.result.spi.Page;
import com.visualspider.result.spi.ResultRecord;
import com.visualspider.result.spi.RunAccessDeniedException;
import com.visualspider.result.spi.RunResultQuery;
import com.visualspider.result.spi.RunStats;
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
 * RunResultQuery PostgreSQL 集成测试（spec §D12 / D17 / T2）。
 *
 * <p>依赖真实 PostgreSQL（{@code -Ppg-it -Dpg.it.url=...}）。
 * 直接通过 {@link JdbcTemplate} 插入 fixture；{@link IdentityAccess} 用 {@code @MockBean}
 * 替换以精确控制所有权判定，避免依赖 SecurityContext 真实身份链。
 *
 * <p>覆盖：
 * <ul>
 *   <li>keyset 分页（按 sequence_no 升序）</li>
 *   <li>stats 计数（raw/dedup/final/fail）</li>
 *   <li>非 owner -> {@link RunAccessDeniedException}</li>
 *   <li>run 不存在 -> {@link RunAccessDeniedException}</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("it")
class RunResultQueryIT {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RunResultQuery query;

    @MockBean
    private IdentityAccess identityAccess;

    private long aliceUserId;
    private long bobUserId;
    private long adminUserId;
    private long aliceTaskId;
    private long aliceRunId;
    private long bobRunId;

    @BeforeEach
    void setUp() throws Exception {
        // 清表（IT 独立 PG schema，依赖外键 ON DELETE CASCADE）
        jdbc.update("DELETE FROM run_result");
        jdbc.update("DELETE FROM run_event");
        jdbc.update("DELETE FROM collection_run");
        jdbc.update("DELETE FROM collection_task");
        jdbc.update("DELETE FROM app_user WHERE username LIKE 'it-%'");

        // 创建 3 个用户
        aliceUserId = insertUser("it-alice", "alice-pwd-12+chars", "COLLECTOR");
        bobUserId = insertUser("it-bob", "bob-pwd-12+chars-extra", "COLLECTOR");
        adminUserId = insertUser("it-admin", "admin-pwd-12+chars", "ADMIN");

        // 准备 task
        aliceTaskId = jdbc.queryForObject(
                "INSERT INTO collection_task (owner_id, name, mode, status, schema_version, definition) "
                        + "VALUES (?, 'alice-task', 'SINGLE_PAGE', 'READY', 1, ?::jsonb) RETURNING id",
                Long.class,
                aliceUserId,
                taskJson());
        long bobTaskId = jdbc.queryForObject(
                "INSERT INTO collection_task (owner_id, name, mode, status, schema_version, definition) "
                        + "VALUES (?, 'bob-task', 'SINGLE_PAGE', 'READY', 1, ?::jsonb) RETURNING id",
                Long.class,
                bobUserId,
                taskJson());

        // 准备 run
        aliceRunId = insertRun(aliceUserId, aliceTaskId, "WAITING");
        bobRunId = insertRun(bobUserId, bobTaskId, "WAITING");

        // 插入 25 条 result（sequence_no 0..24）
        for (int i = 0; i < 25; i++) {
            insertResult(aliceRunId, i, Map.of("title", "row-" + i));
        }
        // bob 的 run 也有 10 条（admin 访问测试）
        for (int i = 0; i < 10; i++) {
            insertResult(bobRunId, i, Map.of("title", "bob-" + i));
        }
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
    @DisplayName("page: keyset 分页返回首 10 条按 sequence_no 升序")
    void pageFirstPage() {
        // Mock IdentityAccess：alice 通过自己的资源
        when(identityAccess.canAccessTask(anyLong(), any())).thenAnswer(inv -> {
            long ownerId = inv.getArgument(0);
            ActorId actor = inv.getArgument(1);
            return actor.value() == ownerId;
        });

        Page<ResultRecord> page = query.page(aliceRunId, new ActorId(aliceUserId), 1, 10);

        assertThat(page.page()).isEqualTo(1);
        assertThat(page.size()).isEqualTo(10);
        assertThat(page.total()).isEqualTo(25L);
        assertThat(page.items()).hasSize(10);
        assertThat(page.items().get(0).sequenceNo()).isZero();
        assertThat(page.items().get(9).sequenceNo()).isEqualTo(9);
    }

    @Test
    @DisplayName("page: 第二页 10 条（sequence_no 10..19）")
    void pageSecondPage() {
        when(identityAccess.canAccessTask(anyLong(), any())).thenAnswer(inv -> {
            long ownerId = inv.getArgument(0);
            ActorId actor = inv.getArgument(1);
            return actor.value() == ownerId;
        });

        Page<ResultRecord> page = query.page(aliceRunId, new ActorId(aliceUserId), 2, 10);

        assertThat(page.items()).hasSize(10);
        assertThat(page.items().get(0).sequenceNo()).isEqualTo(10);
        assertThat(page.items().get(9).sequenceNo()).isEqualTo(19);
    }

    @Test
    @DisplayName("page: 第三页只有 5 条（25 - 20）")
    void pageThirdPagePartial() {
        when(identityAccess.canAccessTask(anyLong(), any())).thenAnswer(inv -> {
            long ownerId = inv.getArgument(0);
            ActorId actor = inv.getArgument(1);
            return actor.value() == ownerId;
        });

        Page<ResultRecord> page = query.page(aliceRunId, new ActorId(aliceUserId), 3, 10);

        assertThat(page.items()).hasSize(5);
        assertThat(page.items().get(0).sequenceNo()).isEqualTo(20);
        assertThat(page.items().get(4).sequenceNo()).isEqualTo(24);
    }

    @Test
    @DisplayName("page: admin 可访问任意 run")
    void pageAdminAccess() {
        when(identityAccess.canAccessTask(anyLong(), any())).thenReturn(true);

        Page<ResultRecord> page = query.page(bobRunId, new ActorId(adminUserId), 1, 10);

        assertThat(page.items()).hasSize(10);
    }

    @Test
    @DisplayName("page: bob 访问 alice 的 run -> RunAccessDeniedException")
    void pageDenied() {
        when(identityAccess.canAccessTask(anyLong(), any())).thenAnswer(inv -> {
            long ownerId = inv.getArgument(0);
            ActorId actor = inv.getArgument(1);
            return actor.value() == ownerId;
        });

        assertThatThrownBy(() -> query.page(aliceRunId, new ActorId(bobUserId), 1, 10))
                .isInstanceOf(RunAccessDeniedException.class);
    }

    @Test
    @DisplayName("page: run 不存在 -> RunAccessDeniedException")
    void pageMissingRun() {
        assertThatThrownBy(() -> query.page(999_999L, new ActorId(adminUserId), 1, 10))
                .isInstanceOf(RunAccessDeniedException.class);
    }

    @Test
    @DisplayName("stats: 返回 raw/dedup/final/fail 计数")
    void stats() {
        // 设置 collection_run 上的计数字段
        jdbc.update("UPDATE collection_run SET record_count_raw = 5, record_count_dedup = 5, "
                + "record_count_final = 5, fail_count = 2 WHERE id = ?", aliceRunId);

        when(identityAccess.canAccessTask(anyLong(), any())).thenAnswer(inv -> {
            long ownerId = inv.getArgument(0);
            ActorId actor = inv.getArgument(1);
            return actor.value() == ownerId;
        });

        RunStats stats = query.stats(aliceRunId, new ActorId(aliceUserId));
        assertThat(stats.raw()).isEqualTo(5);
        assertThat(stats.dedup()).isEqualTo(5);
        assertThat(stats.finalCount()).isEqualTo(5);
        assertThat(stats.fail()).isEqualTo(2);
    }

    // ============================ helpers ============================

    private long insertUser(String username, String password, String role) {
        return jdbc.queryForObject(
                "INSERT INTO app_user (username, password_hash, role, status) "
                        + "VALUES (?, ?, ?, 'ACTIVE') RETURNING id",
                Long.class, username, "{noop}" + password, role);
    }

    private long insertRun(long ownerId, long taskId, String status) {
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

    private String snapshotJson() {
        // snapshot 内嵌完整 TaskDefinition
        return "{\"taskId\":1,\"ownerId\":1,\"name\":\"x\",\"mode\":\"SINGLE_PAGE\","
                + "\"schemaVersion\":1,\"version\":1,\"definition\":"
                + taskJson() + "}";
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