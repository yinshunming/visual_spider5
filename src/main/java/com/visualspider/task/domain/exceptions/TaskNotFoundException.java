package com.visualspider.task.domain.exceptions;

/**
 * 任务不存在或调用者无权访问。HTTP 404。
 */
public class TaskNotFoundException extends RuntimeException {
    public TaskNotFoundException(long taskId) {
        super("任务不存在: id=" + taskId);
    }
}
