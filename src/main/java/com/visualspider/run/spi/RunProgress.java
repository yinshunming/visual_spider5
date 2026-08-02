package com.visualspider.run.spi;

/**
 * 运行进度（M3 spec §D16）：WS 握手后下发 + 每次 PROGRESS 推送的最小单元。
 *
 * <p>M3 单页下约 5–6 条 PROGRESS；M4/M5 多页时按 ≥10 msg/s 节流合并。
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
        long elapsedMs) {
}