package com.visualspider.result.spi;

import java.time.Instant;

/**
 * 到期清理 SPI（M3 spec §D14）。
 *
 * <p>删除 {@code finished_at < now - 30 days} 的 {@code collection_run} 记录；
 * FK {@code ON DELETE CASCADE} 级联删 {@code run_result} / {@code run_event}；
 * 不删 {@code collection_task}。
 *
 * <p>30 天为 M3 写死常量（{@link #RETENTION_DAYS}）；{@code system_setting} 可配延后 M6
 * （与 ADR-0004 同先例）。
 */
public interface RetentionCleanup {

    /** M3 写死常量；M6 入 {@code system_setting}（spec §D14 / D20）。 */
    int RETENTION_DAYS = 30;

    /**
     * 删除到期运行。
     *
     * @param now 当前时间（{@code Clock.instant()}；便于测试 fake clock）
     * @return 实际删除的 {@code collection_run} 条数
     */
    int deleteExpired(Instant now);
}