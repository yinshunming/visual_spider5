package com.visualspider.result.internal;

import com.visualspider.result.spi.RetentionCleanup;
import com.visualspider.shared.time.Clock;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 到期清理实现 + 定时任务（M3 spec §D14 / D15）。
 *
 * <p>实现 {@link RetentionCleanup#deleteExpired}：单 SQL 删除
 * {@code finished_at < now - 30 days} 的 {@code collection_run}；FK
 * {@code ON DELETE CASCADE} 级联删 {@code run_result} / {@code run_event}；
 * 不删 {@code collection_task}。
 *
 * <p>{@link #run()} 由 Spring 调度触发（每日 03:17），调用 {@link #deleteExpired(Instant)}
 * 并写 INFO 日志。技术日志不含结果字段值（run_event.message 是用户摘要，本类不读）。
 *
 * <p>{@code @EnableScheduling} 在 {@link RetentionCleanupConfig} 启用。
 */
@Component
public class RetentionCleanupTask implements RetentionCleanup {

    private static final Logger LOG = LoggerFactory.getLogger(RetentionCleanupTask.class);

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public RetentionCleanupTask(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public int deleteExpired(Instant now) {
        Instant threshold = now.minus(RETENTION_DAYS, ChronoUnit.DAYS);
        int deleted = jdbc.update(
                "DELETE FROM collection_run WHERE finished_at IS NOT NULL AND finished_at < ?",
                Timestamp.from(threshold));
        return deleted;
    }

    /**
     * Spring 调度入口：每日 03:17（spec §D14 cron 字符串）。
     *
     * <p>仅做"调用 deleteExpired + 写 INFO 日志"；不抛异常到调用方（Spring 会按 ERROR
     * 处理异常，影响其它调度）。任何 SQL 异常已由 {@link JdbcTemplate} 包装。
     */
    @Scheduled(cron = "0 17 3 * * *")
    public void run() {
        Instant now = clock.instant();
        try {
            int deleted = deleteExpired(now);
            LOG.info("retention cleanup deleted {} runs (threshold=now-{}d, now={})",
                    deleted, RETENTION_DAYS, now);
        } catch (RuntimeException ex) {
            // 不向上抛：避免影响其它 @Scheduled 任务；运维通过 ERROR 日志发现
            LOG.error("retention cleanup failed (now={}): {}", now, ex.toString());
        }
    }
}