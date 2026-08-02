package com.visualspider.run.internal;

/**
 * 任务未通过运行前校验（READY 校验失败）即被启动 run。
 *
 * <p>M3 spec §D2 / §D19：{@code RunCoordinator.start} 在所有权通过后调用
 * {@code TaskReadiness.validateForRun}，失败即抛本异常（409 + TASK_NOT_READY）。
 */
public final class TaskNotReadyException extends RuntimeException {
    public TaskNotReadyException(long taskId, String message) {
        super("任务未通过校验不能启动: taskId=" + taskId + " " + message);
    }
}