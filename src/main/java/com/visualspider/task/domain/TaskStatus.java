package com.visualspider.task.domain;

/**
 * 任务状态。M1 仅使用 DRAFT；READY 由 M2 校验通过后设置。
 */
public enum TaskStatus {
    DRAFT,
    READY
}
