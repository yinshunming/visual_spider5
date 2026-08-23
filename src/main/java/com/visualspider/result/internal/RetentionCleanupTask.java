package com.visualspider.result.internal;

import com.visualspider.result.spi.RetentionCleanup;
import com.visualspider.shared.metrics.MetricsRegistrar.MetricsHolders;
import com.visualspider.shared.time.Clock;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 到期清理实现 + 定时任务（M3 spec §D14 / D15 / M6-6 §D14）。
 *
 * <p>实现 {@link RetentionCleanup#deleteExpired}：单 SQL 删除
 * {@code finished_at < now - retention_days} 的 {@code collection_run}；FK
 * {@code ON DELETE CASCADE} 级联删 {@code run_result} / {@code run_event}；
 * 不删 {@code collection_task}。
 *
 * <p>M6-6：保留天数从 {@code system_setting.retention.days} 读，缺行或非法值 fallback 30。
 * 启动时幂等 seed 由 {@code AdminSettingSeeder} 完成。
 *
 * <p>删除行数通过 micrometer counter {@code retention.deletedRows} 上报（M6-6 §D11）。
 *
 * <p>{@code @EnableScheduling} 在 {@link RetentionCleanupConfig} 启用。
 */
@Component
public class RetentionCleanupTask implements RetentionCleanup {

    private static final Logger LOG = LoggerFactory.getLogger(RetentionCleanupTask.class);

    /** 默认保留天数（缺行 / 非法值 fallback；与 product-spec §9 一致）。 */
    public static final int DEFAULT_RETENTION_DAYS = 30;
    /** system_setting 中的保留天数 key。 */
    public static final String SETTING_KEY = "retention.days";
    /** 合法范围（min, max），校验 admin PUT 入参。 */
    public static final int MIN_DAYS = 1;
    public static final int MAX_DAYS = 365;

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private RetentionDeletedRowsCounter counter;

    @Autowired
    public RetentionCleanupTask(JdbcTemplate jdbc, Clock clock) {
        this(jdbc, clock, null);
    }

    /** 完整构造：注入删除行数计数器（M6-6 micrometer），测试可传 null。 */
    public RetentionCleanupTask(JdbcTemplate jdbc, Clock clock,
                                 RetentionDeletedRowsCounter counter) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.counter = counter;
    }

    @Override
    public int deleteExpired(Instant now) {
        int days = readRetentionDays();
        Instant threshold = now.minus(days, ChronoUnit.DAYS);
        int deleted = jdbc.update(
                "DELETE FROM collection_run WHERE finished_at IS NOT NULL AND finished_at < ?",
                Timestamp.from(threshold));
        if (counter != null) {
            counter.increment(deleted);
        }
        return deleted;
    }

    /** 注入 micrometer holders（生产用 bean 路径）。 */
    @Autowired
    public void wireMetrics(MetricsHolders holders) {
        if (this.counter == null) {
            this.counter = n -> holders.retentionDeletedRows().increment(n);
        }
    }

    /**
     * Spring 调度入口：每日 03:17（spec §D14 cron 字符串）。
     */
    @Scheduled(cron = "0 17 3 * * *")
    public void run() {
        Instant now = clock.instant();
        int days = readRetentionDays();
        try {
            int deleted = deleteExpired(now);
            LOG.info("retention cleanup deleted {} runs (days={}, now={})", deleted, days, now);
        } catch (RuntimeException ex) {
            LOG.error("retention cleanup failed (days={}, now={}): {}", days, now, ex.toString());
        }
    }

    /** 从 system_setting 读保留天数；缺行 / 非法值 fallback 30。 */
    int readRetentionDays() {
        try {
            String value = jdbc.queryForObject(
                    "SELECT value FROM system_setting WHERE key = ?",
                    String.class, SETTING_KEY);
            int parsed = Integer.parseInt(value);
            if (parsed < MIN_DAYS || parsed > MAX_DAYS) {
                LOG.warn("retention.days={} 超出合法范围 [{}, {}], fallback {}",
                        parsed, MIN_DAYS, MAX_DAYS, DEFAULT_RETENTION_DAYS);
                return DEFAULT_RETENTION_DAYS;
            }
            return parsed;
        } catch (EmptyResultDataAccessException notFound) {
            return DEFAULT_RETENTION_DAYS;
        } catch (NumberFormatException nfe) {
            LOG.warn("retention.days 值非法, fallback {}: {}", DEFAULT_RETENTION_DAYS, nfe.toString());
            return DEFAULT_RETENTION_DAYS;
        }
    }

    /** 删除行数计数器（micrometer bridge）。 */
    public interface RetentionDeletedRowsCounter {
        void increment(int n);
    }
}
