package com.visualspider.run.api;

import java.time.OffsetDateTime;

/**
 * {@code POST /api/runs} 响应（spec §D17）。
 *
 * <p>固定字段：{@code {runId, status, createdAt}}。
 */
public record RunStartResponse(long runId, String status, OffsetDateTime createdAt) {
}
