package com.visualspider.result.spi;

/**
 * {@link RunResultSink#appendBatch} 返回值（M4 spec §D6；M5-4 / spec §D8）。
 *
 * <p>{@code rawCount} 写入 sink 的总行数；{@code dedupCount} hash 重复跳过；{@code insertedCount}
 * 真正写入新行；{@code failedCount} 写入异常；{@code contentFailCount} 内容页 navigate 失败
 * （retry 耗尽）的记录数（M5 新增；M4 reader 旧构造器默认 0，向后兼容）。
 *
 * <p>每批次 sink 一次性返回给运行引擎用于 {@code collection_run.*_count} 累加；
 * {@code failedCount} 与 {@code contentFailCount} 语义互不重叠：
 * 前者代表 sink 行级失败（SQLState 22x 等），后者代表"内容页 navigate 失败 + content 字段全 null"。
 */
public record BatchOutcome(
        int rawCount,
        int dedupCount,
        int insertedCount,
        int failedCount,
        int contentFailCount) {

    /** M4 兼容构造器：{@code contentFailCount} 默认 0（spec §D8 旧 reader 不破）。 */
    public BatchOutcome(int rawCount, int dedupCount, int insertedCount, int failedCount) {
        this(rawCount, dedupCount, insertedCount, failedCount, 0);
    }

    public static BatchOutcome empty() {
        return new BatchOutcome(0, 0, 0, 0);
    }
}
