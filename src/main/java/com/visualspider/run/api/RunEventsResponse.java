package com.visualspider.run.api;

import java.util.List;

/**
 * {@code GET /api/runs/{runId}/events} 分页响应（spec §D17）。
 */
public record RunEventsResponse(List<RunEventDto> items, long total, int page, int size) {
}
