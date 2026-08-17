package com.visualspider.task.domain;

import java.time.OffsetDateTime;

/**
 * 任务完整快照（{@code GET /api/tasks/{id}} 返回）。
 */
public record TaskDraft(
        long id,
        long ownerId,
        String name,
        TaskMode mode,
        TaskStatus status,
        int schemaVersion,
        long version,
        TaskDefinition definition,
        OffsetDateTime updatedAt) {
}
