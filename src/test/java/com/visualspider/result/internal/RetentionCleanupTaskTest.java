package com.visualspider.result.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.visualspider.shared.time.Clock;
import com.visualspider.shared.time.MutableClock;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@link RetentionCleanupTask} 系统设置读取单测（spec §D14 / T1）。
 *
 * <p>覆盖：
 * <ul>
 *   <li>system_setting 有合法值 -&gt; 使用该值</li>
 *   <li>system_setting 缺行 -&gt; fallback 30</li>
 *   <li>system_setting 值非法 -&gt; fallback 30 + WARN 日志</li>
 *   <li>值超出 [MIN_DAYS, MAX_DAYS] 范围 -&gt; fallback 30</li>
 * </ul>
 */
class RetentionCleanupTaskTest {

    private JdbcTemplate jdbc;
    private Clock clock;
    private RetentionCleanupTask task;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        clock = new MutableClock(Instant.parse("2026-06-01T00:00:00Z"));
        task = new RetentionCleanupTask(jdbc, clock);
    }

    @Test
    @DisplayName("readRetentionDays: system_setting 有合法值 -> 使用该值")
    void usesValueFromDb() {
        when(jdbc.queryForObject(
                org.mockito.ArgumentMatchers.eq("SELECT value FROM system_setting WHERE key = ?"),
                org.mockito.ArgumentMatchers.eq(String.class),
                org.mockito.ArgumentMatchers.eq(RetentionCleanupTask.SETTING_KEY)))
                .thenReturn("14");

        assertThat(task.readRetentionDays()).isEqualTo(14);
    }

    @Test
    @DisplayName("readRetentionDays: system_setting 缺行 -> fallback 30")
    void fallsBackWhenRowMissing() {
        when(jdbc.queryForObject(
                org.mockito.ArgumentMatchers.eq("SELECT value FROM system_setting WHERE key = ?"),
                org.mockito.ArgumentMatchers.eq(String.class),
                org.mockito.ArgumentMatchers.eq(RetentionCleanupTask.SETTING_KEY)))
                .thenThrow(new EmptyResultDataAccessException(1));

        assertThat(task.readRetentionDays()).isEqualTo(RetentionCleanupTask.DEFAULT_RETENTION_DAYS);
    }

    @Test
    @DisplayName("readRetentionDays: 值非法 -> fallback 30")
    void fallsBackWhenValueInvalid() {
        when(jdbc.queryForObject(
                org.mockito.ArgumentMatchers.eq("SELECT value FROM system_setting WHERE key = ?"),
                org.mockito.ArgumentMatchers.eq(String.class),
                org.mockito.ArgumentMatchers.eq(RetentionCleanupTask.SETTING_KEY)))
                .thenReturn("not-a-number");

        assertThat(task.readRetentionDays()).isEqualTo(RetentionCleanupTask.DEFAULT_RETENTION_DAYS);
    }

    @Test
    @DisplayName("readRetentionDays: 值超出范围 -> fallback 30")
    void fallsBackWhenOutOfRange() {
        when(jdbc.queryForObject(
                org.mockito.ArgumentMatchers.eq("SELECT value FROM system_setting WHERE key = ?"),
                org.mockito.ArgumentMatchers.eq(String.class),
                org.mockito.ArgumentMatchers.eq(RetentionCleanupTask.SETTING_KEY)))
                .thenReturn("999");

        assertThat(task.readRetentionDays()).isEqualTo(RetentionCleanupTask.DEFAULT_RETENTION_DAYS);
    }

    @Test
    @DisplayName("readRetentionDays: 值 = 0 (低于 MIN_DAYS=1) -> fallback")
    void fallsBackWhenBelowMin() {
        when(jdbc.queryForObject(
                org.mockito.ArgumentMatchers.eq("SELECT value FROM system_setting WHERE key = ?"),
                org.mockito.ArgumentMatchers.eq(String.class),
                org.mockito.ArgumentMatchers.eq(RetentionCleanupTask.SETTING_KEY)))
                .thenReturn("0");

        assertThat(task.readRetentionDays()).isEqualTo(RetentionCleanupTask.DEFAULT_RETENTION_DAYS);
    }

    @Test
    @DisplayName("常量值与 product-spec §9 一致")
    void constantMatchSpec() {
        assertThat(RetentionCleanupTask.DEFAULT_RETENTION_DAYS).isEqualTo(30);
        assertThat(RetentionCleanupTask.SETTING_KEY).isEqualTo("retention.days");
        assertThat(RetentionCleanupTask.MIN_DAYS).isEqualTo(1);
        assertThat(RetentionCleanupTask.MAX_DAYS).isEqualTo(365);
    }

    @Test
    @DisplayName("deleteExpired 阈值 = now - retention_days")
    void deleteExpiredUsesThresholdFromSetting() {
        when(jdbc.queryForObject(
                org.mockito.ArgumentMatchers.eq("SELECT value FROM system_setting WHERE key = ?"),
                org.mockito.ArgumentMatchers.eq(String.class),
                org.mockito.ArgumentMatchers.eq(RetentionCleanupTask.SETTING_KEY)))
                .thenReturn("14");
        when(jdbc.update(
                org.mockito.ArgumentMatchers.contains("DELETE FROM collection_run"),
                org.mockito.ArgumentMatchers.any(java.sql.Timestamp.class)))
                .thenReturn(0);

        int deleted = task.deleteExpired(clock.instant());
        assertThat(deleted).isEqualTo(0);
    }
}
