package com.visualspider.task.domain;

/**
 * 任务运行时不可变快照。
 *
 * <p>M1-3 在 {@link com.visualspider.task.spi.TaskSnapshotFactory} 内抛 {@code UnsupportedOperationException("M3 启用")}；
 * M3 才接真实实现（immutable + JSONB 反序列化 + version 校验）。
 */
public record TaskSnapshot(
        long taskId,
        long ownerId,
        String name,
        TaskMode mode,
        int schemaVersion,
        long version,
        TaskDefinition definition) {
}
