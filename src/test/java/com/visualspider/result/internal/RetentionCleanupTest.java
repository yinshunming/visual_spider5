package com.visualspider.result.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.visualspider.shared.time.MutableClock;
import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * RetentionCleanupTask 单元测试（mocked JdbcTemplate + {@link MutableClock}）。
 *
 * <p>覆盖：
 * <ul>
 *   <li>{@code finished_at < now - 30 days} 删除</li>
 *   <li>SQL 含 {@code finished_at IS NOT NULL} 守卫</li>
 *   <li>返回受影响行数</li>
 *   <li>{@code @Scheduled run} 调 deleteExpired + 异常不向上抛</li>
 *   <li>常量 {@link com.visualspider.result.spi.RetentionCleanup#RETENTION_DAYS = 30}</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class RetentionCleanupTest {

    @Mock
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("RETENTION_DAYS = 30")
    void retentionDaysIs30() {
        assertThat(com.visualspider.result.spi.RetentionCleanup.RETENTION_DAYS).isEqualTo(30);
    }

    @Test
    @DisplayName("deleteExpired 调 SQL：DELETE FROM collection_run WHERE finished_at IS NOT NULL AND finished_at < ?")
    void deleteExpiredIssuesGuardedDelete() {
        Instant now = Instant.parse("2026-06-01T00:00:00Z");
        MutableClock clock = new MutableClock(now);
        RetentionCleanupTask task = new RetentionCleanupTask(jdbc, clock);

        when(jdbc.update(any(String.class), any(Timestamp.class))).thenReturn(2);

        int deleted = task.deleteExpired(now);
        assertThat(deleted).isEqualTo(2);

        ArgumentCaptor<String> sqlCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> argCap = ArgumentCaptor.forClass(Object.class);
        verify(jdbc).update(sqlCap.capture(), argCap.capture());
        String sql = sqlCap.getValue();
        assertThat(sql).contains("DELETE FROM collection_run");
        assertThat(sql).contains("finished_at IS NOT NULL");
        assertThat(sql).contains("finished_at < ?");
        Timestamp threshold = (Timestamp) argCap.getValue();
        assertThat(threshold.toInstant())
                .isEqualTo(now.minus(30, java.time.temporal.ChronoUnit.DAYS));
    }

    @Test
    @DisplayName("deleteExpired 返回 SQL 影响行数（0 行：没过期的 run）")
    void deleteExpiredReturnsAffectedCount() {
        Instant now = Instant.parse("2026-06-01T00:00:00Z");
        MutableClock clock = new MutableClock(now);
        RetentionCleanupTask task = new RetentionCleanupTask(jdbc, clock);

        when(jdbc.update(any(String.class), any(Timestamp.class))).thenReturn(0);

        int deleted = task.deleteExpired(now);
        assertThat(deleted).isZero();
    }

    @Test
    @DisplayName("@Scheduled run 调用 deleteExpired；不抛异常")
    void scheduledRunInvokesDelete() {
        MutableClock clock = new MutableClock(Instant.parse("2026-06-01T00:00:00Z"));
        RetentionCleanupTask task = new RetentionCleanupTask(jdbc, clock);
        when(jdbc.update(any(String.class), any(Timestamp.class))).thenReturn(5);

        task.run(); // 不抛
        verify(jdbc).update(any(String.class), eq(Timestamp.from(
                clock.instant().minus(30, java.time.temporal.ChronoUnit.DAYS))));
    }

    @Test
    @DisplayName("@Scheduled run 抛异常时不向上抛（避免影响其它调度）")
    void scheduledRunSwallowsExceptions() {
        MutableClock clock = new MutableClock(Instant.parse("2026-06-01T00:00:00Z"));
        RetentionCleanupTask task = new RetentionCleanupTask(jdbc, clock);
        when(jdbc.update(any(String.class), any(Timestamp.class))).thenThrow(new RuntimeException("boom"));

        task.run(); // 不抛
    }
}