package com.visualspider.task.api;

import com.visualspider.task.domain.TaskDefinition;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 创建任务请求 DTO。
 */
public record CreateTaskRequest(
        @NotBlank @Size(max = 200) String name,
        TaskDefinition definition) {
}
