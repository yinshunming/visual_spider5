package com.visualspider.task.api;

import com.visualspider.task.domain.TaskDefinition;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 保存任务草稿请求 DTO。
 *
 * <p>{@code expectedVersion} 必须等于客户端当前持有的版本号；不一致时返回 409 + {@code TASK_STALE_VERSION}。
 */
public record SaveTaskRequest(
        @NotNull @Min(0) Long expectedVersion,
        @NotNull TaskDefinition definition) {
}
