package com.visualspider.result.spi;

import java.time.Instant;
import java.util.Map;

/**
 * 单条结果记录（M3 spec §D12）。
 *
 * <p>{@code data} 是字段名到清洗后值的映射；{@code sequenceNo} 是同一运行内单调递增序号，
 * 与 {@code (run_id, sequence_no)} 唯一索引对应。
 *
 * <p>M3 单页只发 1 条；批量接口为 M4/M5 多页/多记录前向兼容。
 */
public record ResultRecord(
        long id,
        long runId,
        int sequenceNo,
        Map<String, String> data,
        Instant createdAt) {

    public ResultRecord {
        if (data == null) {
            data = Map.of();
        } else {
            data = Map.copyOf(data);
        }
    }

    /** 写入路径便捷构造：DB 由 generated key 填充 {@code id} 与 {@code createdAt}。 */
    public static ResultRecord forInsert(long runId, int sequenceNo, Map<String, String> data) {
        return new ResultRecord(0L, runId, sequenceNo, data, null);
    }
}