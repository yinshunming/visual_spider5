package com.visualspider.result.spi;

import java.time.Instant;

/**
 * 运行事件读取输出（M3 spec §D5）。
 *
 * <p>从 {@code run_event} 表读出的完整事件记录；{@code message} 是用户可见摘要
 * （不含完整页面内容/堆栈，spec §D19）。
 */
public record RunEvent(
        long id,
        long runId,
        RunEventLevel level,
        String stage,
        String url,
        String errorCode,
        String message,
        Instant createdAt) {
}