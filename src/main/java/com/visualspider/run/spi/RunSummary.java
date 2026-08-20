package com.visualspider.run.spi;

import java.time.OffsetDateTime;

/**
 * 运行列表摘要（不含完整 snapshot JSON）。
 *
 * <p>M3 spec §D2：列表 / 概览接口传输形态；详情另由 {@link RunDetail} 承载。
 *
 * <p>M5-4（spec §D8 / §D13）扩展 {@code contentFailCount}：内容页 navigate 失败
 * （retry 耗尽）的记录数；M4 reader 不返回该字段时由 controller 兜底为 0（向后兼容）。
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
        int contentFailCount,
        OffsetDateTime createdAt,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt) {
}