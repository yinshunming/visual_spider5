package com.visualspider.run.spi;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 运行内协作式控制：取消标志 + 计数器（spec §D9）。
 *
 * <p>持有者：{@code RunDispatcher} 在派发时构造并随 {@link RunExecutor} 调用传入；
 * 每轮 / 每个 checkpoint 由执行器读 {@link #isCancelRequested()} 决定提前退出。
 *
 * <p>取消标志由 {@code RunCoordinator.cancel} 翻 true（volatile 保证可见性）。
 * 计数（{@code pageCount} / {@code recordCount}）由执行器在写结果时累加。
 *
 * <p>不暴露 lane / Playwright 对象，符合 ADR-0003 seam 约束。
 */
public final class RunExecutionContext {

    private volatile boolean cancelRequested;
    private final long startedAtMs;
    private final long maxDurationMs;
    private final int maxPages;
    private final int maxRecords;
    private final AtomicInteger pageCount = new AtomicInteger(0);
    private final AtomicInteger recordCountFinal = new AtomicInteger(0);

    public RunExecutionContext(long startedAtMs, long maxDurationMs, int maxPages, int maxRecords) {
        this.startedAtMs = startedAtMs;
        this.maxDurationMs = maxDurationMs;
        this.maxPages = maxPages;
        this.maxRecords = maxRecords;
    }

    /** 标记取消。多次调用幂等；{@code RunCoordinator.cancel} 调用。 */
    public void requestCancel() {
        this.cancelRequested = true;
    }

    /** 检查是否已请求取消。 */
    public boolean isCancelRequested() {
        return cancelRequested;
    }

    /** 当前已推进页数（含重试每次 attempt）。 */
    public int pageCount() {
        return pageCount.get();
    }

    /** 当前已写入 run_result 的最终记录数。 */
    public int recordCountFinal() {
        return recordCountFinal.get();
    }

    /** 执行器在写入结果时累加。 */
    public void incrementPageCount() {
        pageCount.incrementAndGet();
    }

    /** 执行器在写入结果时累加。 */
    public void incrementRecordCount(int n) {
        if (n > 0) {
            recordCountFinal.addAndGet(n);
        }
    }

    public boolean pageLimitExceeded() {
        return maxPages > 0 && pageCount.get() >= maxPages;
    }

    public boolean recordLimitExceeded() {
        return maxRecords > 0 && recordCountFinal.get() >= maxRecords;
    }

    public boolean timeLimitExceeded(long nowMs) {
        return maxDurationMs > 0 && (nowMs - startedAtMs) >= maxDurationMs;
    }

    public long startedAtMs() {
        return startedAtMs;
    }

    public long maxDurationMs() {
        return maxDurationMs;
    }
}