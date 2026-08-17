package com.visualspider.run.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * {@code POST /api/runs} 请求体（spec §D17）。
 *
 * <p>仅一个字段 {@link #taskId()}：要求为正整数。
 */
public record RunStartRequest(@NotNull @Positive Long taskId) {
}
