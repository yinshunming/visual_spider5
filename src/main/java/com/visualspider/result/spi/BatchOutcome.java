package com.visualspider.result.spi;

/**
 * {@link RunResultSink#appendBatch} 返回值（M4 spec §D6）。
 *
 * <p>{@code rawCount} 写入 sink 的总行数；{@code dedupCount} hash 重复跳过；{@code insertedCount}
 * 真正写入新行；{@code failedCount} 写入异常。每批次 sink 一次性返回给运行引擎用于
 * {@code collection_run.*_count} 累加。
 */
public record BatchOutcome(
        int rawCount,
        int dedupCount,
        int insertedCount,
        int failedCount) {

    public static BatchOutcome empty() {
        return new BatchOutcome(0, 0, 0, 0);
    }
}
