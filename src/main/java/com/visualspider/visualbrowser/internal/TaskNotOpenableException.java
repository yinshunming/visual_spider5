package com.visualspider.visualbrowser.internal;

import com.visualspider.task.domain.TaskStatus;

/** 任务状态不允许打开可视会话（DRAFT / READY 之外的中间状态）。 */
public class TaskNotOpenableException extends RuntimeException {

    public TaskNotOpenableException(long taskId, TaskStatus status) {
        super("task " + taskId + " 状态 " + status + " 不可打开可视会话");
    }
}
