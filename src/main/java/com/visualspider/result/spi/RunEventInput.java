package com.visualspider.result.spi;

/**
 * 运行事件写入输入（M3 spec §D12）。
 *
 * <p>{@link com.visualspider.result.spi.RunResultSink#appendBatch} 用此 record 写入
 * {@code run_event}。{@code message} 是用户可见摘要，不含完整页面内容或 Java 堆栈
 * （spec §D13 / AGENTS 数据与安全约束）。
 *
 * @param level    事件级别
 * @param stage    阶段名（可选）；可空
 * @param url      相关 URL（可选）；可空
 * @param errorCode 错误码（可选）；可空
 * @param message  用户可见摘要；不可空/不可全空白
 */
public record RunEventInput(
        RunEventLevel level,
        String stage,
        String url,
        String errorCode,
        String message) {

    public RunEventInput {
        if (level == null) {
            throw new IllegalArgumentException("RunEventInput.level 不能为空");
        }
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("RunEventInput.message 不能为空或全空白");
        }
    }
}