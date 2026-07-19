package com.visualspider.task.domain;

import java.time.OffsetDateTime;

/**
 * 任务列表摘要（不含完整 definition）。
 */
public record TaskSummary(
        long id,
        String name,
        TaskMode mode,
        TaskStatus status,
        long version,
        OffsetDateTime updatedAt) {
}
