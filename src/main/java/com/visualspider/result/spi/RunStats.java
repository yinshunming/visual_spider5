package com.visualspider.result.spi;

/**
 * 运行汇总统计（M3 spec §D12 / D17）。
 *
 * <p>与 {@code collection_run} 的四个计数字段对应：
 * <ul>
 *   <li>{@code raw}：累计原始结果数（{@code record_count_raw}）</li>
 *   <li>{@code dedup}：去重后结果数（{@code record_count_dedup}，M3 恒等于 {@code raw}）</li>
 *   <li>{@code finalCount}：最终入库结果数（{@code record_count_final}）</li>
 *   <li>{@code fail}：失败字段计数（{@code fail_count}）</li>
 * </ul>
 */
public record RunStats(
        long raw,
        long dedup,
        long finalCount,
        long fail) {
}