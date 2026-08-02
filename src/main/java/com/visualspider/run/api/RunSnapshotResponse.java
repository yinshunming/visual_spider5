package com.visualspider.run.api;

import com.visualspider.task.domain.TaskDefinition;

/**
 * {@code GET /api/runs/{runId}/snapshot} 响应（spec §D17）。
 *
 * <p>返回运行开始时固化的 {@link TaskDefinition} + 元数据。
 */
public record RunSnapshotResponse(
        long runId,
        long taskId,
        String name,
        String mode,
        int schemaVersion,
        long taskVersion,
        TaskDefinition definition) {
}
