package com.visualspider.run.spi;

import com.visualspider.task.domain.TaskDefinition;
import java.time.OffsetDateTime;

/**
 * 运行详情（含固化任务定义元数据）。
 *
 * <p>M3 spec §D2：{@code GET /api/runs/{runId}} 响应；快照以 {@link TaskDefinition}
 * 形式呈现（{@code taskId} / {@code schemaVersion} / {@code name} / {@code mode} / {@code definition}）。
 */
public record RunDetail(
        long runId,
        long taskId,
        long ownerId,
        RunState status,
        StopReason stopReason,
        boolean cancelRequested,
        int pageCount,
        int recordCountRaw,
        int recordCountDedup,
        int recordCountFinal,
        int failCount,
        String currentUrl,
        String stage,
        OffsetDateTime createdAt,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        TaskSnapshotMeta snapshotMeta) {

    /** 运行开始时固化的任务定义元数据（不含完整 snapshot JSONB 内容；详情页另查 snapshot 端点）。 */
    public record TaskSnapshotMeta(
            String name,
            com.visualspider.task.domain.TaskMode mode,
            int schemaVersion,
            long taskVersion,
            TaskDefinition definition) {
    }
}