package com.visualspider.run.api;

import com.visualspider.result.spi.ResultRecord;
import java.util.List;

/**
 * {@code GET /api/runs/{runId}/results} 分页响应（spec §D17）。
 *
 * <p>{@code items} 是 {@link ResultRecord}；序列化后字段名映射到
 * {@code frontend/src/contracts/run.ts} 的 {@code ResultRecord}。
 */
public record RunResultsResponse(List<ResultRecord> items, long total, int page, int size) {
}
