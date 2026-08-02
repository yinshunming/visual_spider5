package com.visualspider.result.spi;

/**
 * 结果模块访问拒绝（M3 spec §D12 / D19）。
 *
 * <p>对运行结果做读写时非 owner 且非 admin 调用 -> 抛本异常；
 * 由 {@code GlobalExceptionHandler} 映射为 {@code 404 RESOURCE_NOT_FOUND}（不回显存在性）。
 */
public class RunAccessDeniedException extends RuntimeException {

    public RunAccessDeniedException(long runId) {
        super("运行不存在或无权访问: runId=" + runId);
    }

    public RunAccessDeniedException(String message) {
        super(message);
    }
}