package com.visualspider.result.spi;

import java.time.Instant;
import java.util.Map;

/**
 * 单条结果记录（M3 spec §D12 / M4 spec §D5）。
 *
 * <p>{@code data} 是字段名到清洗后值的映射；{@code sequenceNo} 是同一运行内单调递增序号，
 * 与 {@code (run_id, sequence_no)} 唯一索引对应。
 *
 * <p>M3 单页只发 1 条；批量接口为 M4/M5 多页/多记录前向兼容。
 *
 * <p><b>M4</b>：新增 {@code uniqueKeyHash}（{@link #forInsertWithKey}）表示去重 hash；
 * 全部空键时为 null，跳过去重。{@code data} 不含 uniqueKey 信息——hash 由 sink 在写入前
 * 计算（避免 sink 持有 task 定义）。
 */
public record ResultRecord(
        long id,
        long runId,
        int sequenceNo,
        Map<String, String> data,
        Instant createdAt,
        byte[] uniqueKeyHash) {

    public ResultRecord {
        if (data == null) {
            data = Map.of();
        } else {
            data = Map.copyOf(data);
        }
    }

    /** 写入路径便捷构造（无 hash）：DB 由 generated key 填充 {@code id} 与 {@code createdAt}。 */
    public static ResultRecord forInsert(long runId, int sequenceNo, Map<String, String> data) {
        return new ResultRecord(0L, runId, sequenceNo, data, null, null);
    }

    /** 写入路径便捷构造（带 uniqueKey hash）；null hash 视为去重无效。 */
    public static ResultRecord forInsertWithKey(long runId, int sequenceNo,
                                                 Map<String, String> data, byte[] uniqueKeyHash) {
        return new ResultRecord(0L, runId, sequenceNo, data, null, uniqueKeyHash);
    }

    /** M3 兼容：6-arg 显式构造 + 老 5-arg 委托（M3 reader 路径仍可构造）。 */
    public ResultRecord(long id, long runId, int sequenceNo,
                        Map<String, String> data, Instant createdAt) {
        this(id, runId, sequenceNo, data, createdAt, null);
    }
}
