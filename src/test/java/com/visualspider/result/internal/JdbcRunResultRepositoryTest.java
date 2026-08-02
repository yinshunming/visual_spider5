package com.visualspider.result.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.visualspider.identity.domain.ActorId;
import com.visualspider.identity.spi.IdentityAccess;
import com.visualspider.result.spi.Page;
import com.visualspider.result.spi.ResultRecord;
import com.visualspider.result.spi.RunAccessDeniedException;
import com.visualspider.result.spi.RunEventInput;
import com.visualspider.result.spi.RunEventLevel;
import com.visualspider.result.spi.RunStats;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;

/**
 * JdbcRunResultRepository 单元测试（mocked JdbcTemplate + IdentityAccess）。
 *
 * <p>覆盖：
 * <ul>
 *   <li>{@code appendBatch}：写入 run_result + run_event；run 不存在抛 {@link RunAccessDeniedException}</li>
 *   <li>{@code page}：keyset 分页（按 sequence_no 升序）；非 owner 抛异常</li>
 *   <li>{@code stats}：返回 raw/dedup/final/fail；非 owner 抛异常</li>
 *   <li>{@code verifyAccess}：run 不存在 / 非 owner 抛异常</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class JdbcRunResultRepositoryTest {

    @Mock
    private JdbcTemplate jdbc;

    @Mock
    private IdentityAccess identityAccess;

    private JdbcRunResultRepository repo;

    @BeforeEach
    void setUp() {
        repo = new JdbcRunResultRepository(jdbc, new ObjectMapper(), identityAccess);
    }

    // ============================ appendBatch ============================

    @Test
    @DisplayName("appendBatch: run 存在时写入 run_result + run_event")
    void appendBatchWritesRowsAndEvents() {
        // run 存在
        when(jdbc.query(anyString(), any(ResultSetExtractor.class), eq(7L)))
                .thenReturn(1);
        when(jdbc.update(any(PreparedStatementCreator.class))).thenReturn(1);

        ResultRecord r1 = ResultRecord.forInsert(7L, 1, Map.of("title", "hello"));
        RunEventInput ev = new RunEventInput(RunEventLevel.INFO, "extract", "https://x", null, "ok");

        repo.appendBatch(7L, List.of(r1), List.of(ev));

        verify(jdbc, times(2)).update(any(PreparedStatementCreator.class));
    }

    @Test
    @DisplayName("appendBatch: run 不存在 -> RunAccessDeniedException")
    void appendBatchMissingRunThrows() {
        when(jdbc.query(anyString(), any(ResultSetExtractor.class), eq(99L)))
                .thenReturn(null);

        assertThatThrownBy(() -> repo.appendBatch(99L, List.of(), List.of()))
                .isInstanceOf(RunAccessDeniedException.class);
    }

    @Test
    @DisplayName("appendBatch: 仅写结果不写事件（events 为 null）")
    void appendBatchOnlyResults() {
        when(jdbc.query(anyString(), any(ResultSetExtractor.class), eq(7L)))
                .thenReturn(1);
        when(jdbc.update(any(PreparedStatementCreator.class))).thenReturn(1);

        repo.appendBatch(7L,
                List.of(ResultRecord.forInsert(7L, 1, Map.of("a", "b"))),
                null);
        verify(jdbc, times(1)).update(any(PreparedStatementCreator.class));
    }

    // ============================ page ============================

    @Test
    @DisplayName("page: keyset 分页 - 计算 startSeq = (page-1)*size 并按 sequence_no>=startSeq 查询")
    void pageKeysetComputation() {
        // 第一次 queryForObject (verifyAccess -> owner_id) -> 2L
        // 第二次 queryForObject (page -> count) -> 100L
        // query 调用（结果）
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(7L)))
                .thenReturn(2L)
                .thenReturn(100L);
        when(identityAccess.canAccessTask(eq(2L), any(ActorId.class))).thenReturn(true);
        when(jdbc.query(anyString(), any(RowMapper.class), eq(7L), eq(0), eq(10)))
                .thenReturn(List.of(
                        new ResultRecord(1L, 7L, 0, Map.of("k", "v"), null),
                        new ResultRecord(2L, 7L, 1, Map.of("k", "v2"), null)));

        Page<ResultRecord> page = repo.page(7L, new ActorId(2L), 1, 10);

        assertThat(page.page()).isEqualTo(1);
        assertThat(page.size()).isEqualTo(10);
        assertThat(page.total()).isEqualTo(100L);
        assertThat(page.items()).hasSize(2);
    }

    @Test
    @DisplayName("page: page<=0 视为 1；size<=0 视为 1")
    void pageClampsBounds() {
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(7L)))
                .thenReturn(2L)
                .thenReturn(0L);
        when(identityAccess.canAccessTask(eq(2L), any(ActorId.class))).thenReturn(true);
        when(jdbc.query(anyString(), any(RowMapper.class), eq(7L), eq(0), eq(1)))
                .thenReturn(List.of());

        Page<ResultRecord> page = repo.page(7L, new ActorId(2L), 0, 0);
        assertThat(page.page()).isEqualTo(1);
        assertThat(page.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("page: size > 1000 截断到 1000")
    void pageSizeCapped() {
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(7L)))
                .thenReturn(2L)
                .thenReturn(0L);
        when(identityAccess.canAccessTask(eq(2L), any(ActorId.class))).thenReturn(true);
        when(jdbc.query(anyString(), any(RowMapper.class), eq(7L), eq(0), eq(1000)))
                .thenReturn(List.of());

        Page<ResultRecord> page = repo.page(7L, new ActorId(2L), 1, 5000);
        assertThat(page.size()).isEqualTo(1000);
    }

    @Test
    @DisplayName("page: 非 owner 且非 admin -> RunAccessDeniedException")
    void pageDeniedForNonOwner() {
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(7L))).thenReturn(2L);
        when(identityAccess.canAccessTask(eq(2L), any(ActorId.class))).thenReturn(false);

        assertThatThrownBy(() -> repo.page(7L, new ActorId(99L), 1, 10))
                .isInstanceOf(RunAccessDeniedException.class);
    }

    @Test
    @DisplayName("page: run 不存在 -> RunAccessDeniedException")
    void pageMissingRunThrows() {
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(99L)))
                .thenThrow(new EmptyResultDataAccessException(1));

        assertThatThrownBy(() -> repo.page(99L, new ActorId(1L), 1, 10))
                .isInstanceOf(RunAccessDeniedException.class);
    }

    // ============================ stats ============================

    @Test
    @DisplayName("stats: 返回 raw/dedup/final/fail 计数")
    void statsReturnsCounters() {
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(7L))).thenReturn(2L);
        when(identityAccess.canAccessTask(eq(2L), any(ActorId.class))).thenReturn(true);
        when(jdbc.queryForObject(anyString(), any(RowMapper.class), eq(7L)))
                .thenReturn(new RunStats(5, 4, 4, 1));

        RunStats stats = repo.stats(7L, new ActorId(2L));
        assertThat(stats.raw()).isEqualTo(5);
        assertThat(stats.dedup()).isEqualTo(4);
        assertThat(stats.finalCount()).isEqualTo(4);
        assertThat(stats.fail()).isEqualTo(1);
    }

    @Test
    @DisplayName("stats: 非 owner -> RunAccessDeniedException")
    void statsDeniedForNonOwner() {
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(7L))).thenReturn(2L);
        when(identityAccess.canAccessTask(eq(2L), any(ActorId.class))).thenReturn(false);

        assertThatThrownBy(() -> repo.stats(7L, new ActorId(99L)))
                .isInstanceOf(RunAccessDeniedException.class);
    }

    // ============================ verifyAccess ============================

    @Test
    @DisplayName("verifyAccess: run 不存在 -> RunAccessDeniedException")
    void verifyAccessMissingRun() {
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(99L)))
                .thenThrow(new EmptyResultDataAccessException(1));

        assertThatThrownBy(() -> repo.verifyAccess(99L, new ActorId(1L)))
                .isInstanceOf(RunAccessDeniedException.class);
    }

    @Test
    @DisplayName("verifyAccess: owner 通过")
    void verifyAccessOwnerPasses() {
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(7L))).thenReturn(2L);
        when(identityAccess.canAccessTask(eq(2L), any(ActorId.class))).thenReturn(true);

        repo.verifyAccess(7L, new ActorId(2L));
    }

    @Test
    @DisplayName("verifyAccess: admin 始终通过（IdentityAccess 决定 admin）")
    void verifyAccessAdminPasses() {
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(7L))).thenReturn(99L);
        when(identityAccess.canAccessTask(eq(99L), any(ActorId.class))).thenReturn(true);

        repo.verifyAccess(7L, new ActorId(1L));
    }
}