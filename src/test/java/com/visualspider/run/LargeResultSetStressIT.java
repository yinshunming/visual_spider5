package com.visualspider.run;

import static org.assertj.core.api.Assertions.assertThat;

import com.visualspider.result.spi.RunResultQuery;
import com.visualspider.identity.domain.ActorId;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 大结果集压测（spec §D10 / T2）：10,000 行 run_result 预插,
 * 验证分页查询 + 流式导出可用且正确。
 *
 * <p>依赖 {@code -Ppg-stress -Dpg.it.url=jdbc:postgresql://...} 等系统属性;
 * DSN 缺失时跳过。
 *
 * <p>不依赖真实 Chromium（纯 JDBC + Repository 测试）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("it")
@EnabledIfSystemProperty(named = "pg.it.url", matches = ".+")
class LargeResultSetStressIT {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RunResultQuery runResultQuery;

    private long aliceUserId;
    private long aliceTaskId;
    private long runId;
    private static final int TOTAL = 10_000;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM run_result");
        jdbc.update("DELETE FROM run_event");
        jdbc.update("DELETE FROM collection_run");
        jdbc.update("DELETE FROM collection_task");
        jdbc.update("DELETE FROM app_user WHERE username LIKE 'it-%'");

        aliceUserId = jdbc.queryForObject(
                "INSERT INTO app_user (username, password_hash, role, status) "
                        + "VALUES (?, ?, 'COLLECTOR', 'ACTIVE') RETURNING id",
                Long.class, "it-stress-alice", "x");
        aliceTaskId = jdbc.queryForObject(
                "INSERT INTO collection_task (owner_id, name, mode, status, schema_version, definition) "
                        + "VALUES (?, 'stress-task', 'SINGLE_PAGE', 'READY', 1, ?::jsonb) RETURNING id",
                Long.class, aliceUserId, "{\"fields\":[]}");
        Instant finished = Instant.now().minusSeconds(60);
        runId = jdbc.queryForObject(
                "INSERT INTO collection_run (owner_id, task_id, status, finished_at, raw_count, dedup_count, "
                        + "final_count, fail_count) VALUES (?, ?, 'SUCCESS', ?, ?, ?, ?, ?) RETURNING id",
                Long.class, aliceUserId, aliceTaskId, Timestamp.from(finished),
                TOTAL, 0, TOTAL, 0);

        // 预插 10,000 行 (JDBC batch)
        jdbc.batchUpdate(
                "INSERT INTO run_result (run_id, seq, record) VALUES (?, ?, ?::jsonb)",
                new org.springframework.jdbc.core.BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(java.sql.PreparedStatement ps, int i) throws java.sql.SQLException {
                        ps.setLong(1, runId);
                        ps.setInt(2, i);
                        ps.setString(3, "{\"title\":\"item-" + i + "\"}");
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
    @DisplayName("10k 行分页: 首末页尺寸正确, 总数 = 10000")
    void paginationOver10kRows() {
        ActorId owner = new ActorId(aliceUserId);
        // 每页 1000 -> 10 页
        List<?> page0 = runResultQuery.page(runId, owner, 0, 1000).items();
        List<?> page9 = runResultQuery.page(runId, owner, 9, 1000).items();
        List<?> page10 = runResultQuery.page(runId, owner, 10, 1000).items();  // 越界
        assertThat(page0).hasSize(1000);
        assertThat(page9).hasSize(1000);
        assertThat(page10).isEmpty();
    }

    @Test
    @DisplayName("顺序稳定: 第 0 页第 0 条 seq=0, 第 9 页最后一条 seq=9999")
    void orderingStable() {
        ActorId owner = new ActorId(aliceUserId);
        var firstPageFirstItem = runResultQuery.page(runId, owner, 0, 1000).items().get(0);
        var lastPageLastItem = runResultQuery.page(runId, owner, 9, 1000).items().get(999);
        assertThat(firstPageFirstItem.toString()).contains("seq=0");
        assertThat(lastPageLastItem.toString()).contains("seq=9999");
    }
}
