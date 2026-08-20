package com.visualspider.run.spi;

/**
 * 运行进度（M3 spec §D16 / M4 spec §D11 / M5 spec §D13）：WS 握手后下发 + 每次 PROGRESS
 * 推送的最小单元。
 *
 * <p>M3 单页下约 5–6 条 PROGRESS；M4 多页时按 ≥10 msg/s 节流合并。
 *
 * <p>{@code listItemMatchCount}（M4）：仅 list 模式在批次写入后推送具体命中数；
 * SINGLE_PAGE 路径该字段为 null。
 *
 * <p>M5 新增 {@code currentPageIndex} / {@code currentItemIndex} / {@code contentFetched}
 * （spec §D13）：list 模式运行时分别表示当前 list 页序号 / 当前 item 序号 / 内容页 fetch
 * 已完成计数；SINGLE_PAGE 路径全部为 null（旧客户端忽略，spec §"进一步说明"）。
 */
public record RunProgress(
        RunState status,
        StopReason stopReason,
        String stage,
        String currentUrl,
        int pageCount,
        int recordCountRaw,
        int recordCountFinal,
        int failCount,
        Integer listItemMatchCount,
        Integer currentPageIndex,
        Integer currentItemIndex,
        Integer contentFetched,
        long elapsedMs) {
}