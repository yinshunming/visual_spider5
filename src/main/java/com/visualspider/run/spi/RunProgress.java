package com.visualspider.run.spi;

/**
 * 运行进度（M3 spec §D16 / M4 spec §D11）：WS 握手后下发 + 每次 PROGRESS 推送的最小单元。
 *
 * <p>M3 单页下约 5–6 条 PROGRESS；M4 多页时按 ≥10 msg/s 节流合并。
 *
 * <p>{@code listItemMatchCount}（M4）：仅 list 模式在批次写入后推送具体命中数；
 * SINGLE_PAGE 路径该字段为 null。
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
        long elapsedMs) {
}