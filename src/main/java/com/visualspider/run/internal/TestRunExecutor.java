package com.visualspider.run.internal;

import com.visualspider.run.spi.RunExecutionContext;
import com.visualspider.run.spi.RunExecutor;
import com.visualspider.run.spi.RunState;
import com.visualspider.run.spi.StopReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 单页运行执行器 stub（M3-2 #24）。
 *
 * <p>仅用于验证 {@code RunDispatcher} 的调度链路：检查取消、写 1 条假结果、终态 SUCCESS。
 * M3-3 ({@code SinglePageRunExecutor}) 替换为真实导航 / 等待 / 提取 / 重试。
 *
 * <p>在 RunLanePool 固定线程上调用；不持有 Playwright / Browser 对象（与 ADR-0003 seam 一致）。
 *
 * <p>由 {@code RunModuleConfig} 显式注册 bean；非 Spring 自动扫描，避免与未来的
 * {@code SinglePageRunExecutor} 形成多个候选。
 */
public class TestRunExecutor implements RunExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(TestRunExecutor.class);

    private final RunRepository repository;

    public TestRunExecutor(RunRepository repository) {
        this.repository = repository;
    }

    @Override
    public void execute(RunExecutionContext context, long runId) {
        if (context.isCancelRequested()) {
            repository.markTerminal(runId, RunState.CANCELLED, StopReason.USER_CANCEL);
            LOG.info("test run executor: cancelled before start runId={}", runId);
            return;
        }
        context.incrementPageCount();
        context.incrementRecordCount(1);
        repository.markTerminal(runId, RunState.SUCCESS, StopReason.COMPLETED);
        LOG.info("test run executor: success runId={}", runId);
    }
}