package com.visualspider.run.spi;

import java.time.OffsetDateTime;

/**
 * 运行列表摘要（不含完整 snapshot JSON）。
 *
 * <p>M3 spec §D2：列表 / 概览接口传输形态；详情另由 {@link RunDetail} 承载。
 */
public record RunSummary(
        long runId,
        long taskId,
        long ownerId,
        RunState status,
        StopReason stopReason,
        boolean cancelRequested,
        int pageCount,
        int recordCountFinal,
        int failCount,
        OffsetDateTime createdAt,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt) {
}