package com.visualspider.run.api;

import com.visualspider.run.spi.RunSummary;
import java.util.List;

/**
 * {@code GET /api/runs} 列表响应（spec §D17）。
 */
public record RunListResponse(List<RunSummary> items, long total, int page, int size) {
}
