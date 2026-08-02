package com.visualspider.run.internal;

/**
 * 运行不存在或调用者无权访问（映射 {@code RUN_NOT_FOUND} 404）。
 *
 * <p>M3 spec §D19：非 owner / 非 admin 调用 get / list / cancel / progress 时也走
 * {@code RUN_NOT_FOUND}（不回显存在性），与 {@code result} 模块同模式。
 */
public final class RunNotFoundException extends RuntimeException {
    public RunNotFoundException(long runId) {
        super("运行不存在: id=" + runId);
    }
}