package com.visualspider.task.domain.exceptions;

/**
 * 乐观锁冲突：保存草稿时 expectedVersion 与当前 version 不一致。HTTP 409。
 *
 * <p>{@code TASK_STALE_VERSION} 错误码（spec §D7）。
 */
public class StaleTaskVersionException extends RuntimeException {

    public static final String CODE = "TASK_STALE_VERSION";

    public StaleTaskVersionException(long taskId, long expectedVersion, long currentVersion) {
        super("任务已被其他人更新，请刷新后重试 (taskId=" + taskId
                + ", expectedVersion=" + expectedVersion + ", currentVersion=" + currentVersion + ")");
    }
}
