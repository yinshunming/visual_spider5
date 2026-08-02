package com.visualspider.run.internal;

import com.visualspider.run.spi.RunPageHandle;
import com.visualspider.visualbrowser.BrowserLane;
import com.visualspider.visualbrowser.spi.Lease;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link RunPageHandleProvider} 默认实现（issue #25 / spec §D9）。
 *
 * <p>从 {@link RunLanePool} 反查 lease 关联的 {@link BrowserLane}，在其上创建
 * {@link DefaultRunPageHandle}（独立 BrowserContext + Page）。
 *
 * <p>未找到 lane（lease 已被归还 / 跨调用方伪造 lease）抛 {@link IllegalStateException}，
 * 由 dispatcher 在 leaseAndSubmit 兜底捕获并写 {@code BROWSER_START_FAILED}。
 */
public class DefaultRunPageHandleProvider implements RunPageHandleProvider {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultRunPageHandleProvider.class);

    private final RunLanePool lanePool;

    public DefaultRunPageHandleProvider(RunLanePool lanePool) {
        this.lanePool = lanePool;
    }

    @Override
    public RunPageHandle openFor(Lease lease, long runId) {
        BrowserLane lane = lanePool.laneOf(lease);
        if (lane == null) {
            LOG.error("RunPageHandleProvider: 找不到 lease 关联的 lane runId={}", runId);
            throw new IllegalStateException("RunLanePool.lease 找不到 lane（已归还或伪造）");
        }
        try {
            return new DefaultRunPageHandle(lane, runId);
        } catch (RuntimeException ex) {
            // Playwright 启动失败 / 资源耗尽；本 issue 允许 BROWSER_START_FAILED 兜底终态
            LOG.error("DefaultRunPageHandle 启动失败 runId={}", runId, ex);
            throw ex;
        }
    }
}
