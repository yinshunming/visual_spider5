package com.visualspider.run.spi;

/**
 * 单页运行执行 SPI（M3 spec §D9）。
 *
 * <p>M3-2 stub（{@code TestRunExecutor}）：写一条假结果即返回 SUCCESS；
 * M3-3 替换为 {@code SinglePageRunExecutor} 真实执行。
 *
 * <p>运行在 {@code RunLanePool} 固定线程上，由 {@code RunDispatcher} 提交；
 * 实现内协作式检查 {@link RunExecutionContext#isCancelRequested()}。
 */
public interface RunExecutor {

    /**
     * 执行一次单页运行（最坏 3 次重试；完成 / 终态后由调用方把状态写回 DB）。
     *
     * @param context 协作式取消 / 计数 / 时间
     * @param runId 当前 run id（用于结果 / 事件写入）
     */
    void execute(RunExecutionContext context, long runId);
}