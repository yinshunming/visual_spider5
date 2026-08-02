package com.visualspider.run.internal;

/**
 * 调用者无权访问指定运行（admin 全局可见；其它 owner / 非 admin 拒绝）。
 *
 * <p>M3 spec §D19：仅在 admin 之外的 collector 访问他人 run 时使用，
 * 比 {@link RunNotFoundException} 更具体（403 + RUN_NOT_OWNER）。
 */
public final class RunNotOwnerException extends RuntimeException {
    public RunNotOwnerException(long runId) {
        super("无权访问运行: id=" + runId);
    }
}