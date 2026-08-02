package com.visualspider.run.internal;

import com.visualspider.run.spi.RunState;

/**
 * 对终态运行发起 cancel 即拒（409 + RUN_NOT_CANCELLABLE）。
 *
 * <p>M3 spec §D2 / §D19：终态 run 不允许 cancel；cancel 只作用于
 * {@link RunState#WAITING} / {@link RunState#RUNNING}。
 */
public final class RunNotCancellableException extends RuntimeException {
    public RunNotCancellableException(long runId, RunState currentState) {
        super("运行已结束不能取消: runId=" + runId + " state=" + currentState);
    }
}