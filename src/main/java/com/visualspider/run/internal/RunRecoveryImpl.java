package com.visualspider.run.internal;

import com.visualspider.run.spi.RunRecovery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 启动恢复（{@link RunRecovery} 默认实现）。
 *
 * <p>M3 spec §D15 / ADR-0006：启动时把遗留 WAITING / RUNNING 标记 INTERRUPTED +
 * stop_reason=APP_INTERRUPTED。不续跑；已写结果仍可查询 / 导出。
 *
 * <p>{@code ApplicationRunner} 入口由 {@code RunModuleConfig.runRecoveryRunner} 显式注册
 * （带 {@code @Order} + {@code @ConditionalOnProperty}）；本类不直接实现
 * {@code ApplicationRunner}，避免被组件扫描默认装配。
 */
public class RunRecoveryImpl implements RunRecovery {

    private static final Logger LOG = LoggerFactory.getLogger(RunRecoveryImpl.class);

    private final RunRepository repository;
    /** 单调递增确保 {@link #markInterruptedOnStartup()} 只执行一次。 */
    private volatile boolean ran;

    public RunRecoveryImpl(RunRepository repository) {
        this.repository = repository;
    }

    @Override
    public int markInterruptedOnStartup() {
        if (ran) {
            return 0;
        }
        ran = true;
        int n = repository.markAllActiveInterrupted();
        if (n > 0) {
            LOG.info("run recovery: marked {} leftover runs INTERRUPTED", n);
        } else {
            LOG.info("run recovery: no leftover WAITING/RUNNING runs");
        }
        return n;
    }
}