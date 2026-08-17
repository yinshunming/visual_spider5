package com.visualspider.run.internal;

import com.visualspider.run.spi.RunPageHandle;
import com.visualspider.visualbrowser.spi.Lease;

/**
 * 把运行 lease 转为 per-run {@link RunPageHandle} 的工厂（spec §D9）。
 *
 * <p>实现位于 {@link DefaultRunPageHandleProvider}（按 lease 关联 lane 创建独立非持久化
 * BrowserContext）；测试可注入返回 {@code null} 或 fake handle 的 stub。
 *
 * <p>包级私有（M3-3 仅 {@code RunDispatcher} 装配使用）；不在 {@code run.spi.*} 暴露，
 * 保持模块 seam 收口。
 */
@FunctionalInterface
public interface RunPageHandleProvider {

    /**
     * 为给定 lease 创建 per-run page handle；返回的 handle 内含独立 BrowserContext，
     * 必须在执行完成后由调用方 {@link RunPageHandle#close()} 关闭。
     */
    RunPageHandle openFor(Lease lease, long runId);
}
