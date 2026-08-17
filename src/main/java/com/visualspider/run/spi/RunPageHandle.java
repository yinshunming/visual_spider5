package com.visualspider.run.spi;

import com.visualspider.extraction.spi.ExtractionPreview;

/**
 * 单页运行对运行 lane Page 的薄封装（M3 spec §D9）。
 *
 * <p>每次运行在 RunLanePool 固定线程上新建独立、非持久化的 BrowserContext；
 * 本接口对外暴露导航 / 等待选择器 / 额外等待 / 取最终 URL / 构造 DomState 等
 * 能力，不暴露 Playwright 原始类型（ADR-0003 seam）。
 *
 * <p>实现位于 {@code run.internal.DefaultRunPageHandle}；测试可注入 fake
 * （{@code run.internal.testutil.FakeRunPageHandle}）以不依赖真实 Chromium。
 */
public interface RunPageHandle extends AutoCloseable {

    /**
     * 导航到指定入口 URL 并等待 {@code DOMContentLoaded}。
     *
     * @param startUrl 任务定义中的入口 URL（已通过 {@code TargetUrlPolicy.validate}）
     * @return 导航结果：成功 / HTTP 状态码 / 错误信息 / 是否命中验证码
     */
    NavigationResult navigateAndAwaitDomContentLoaded(String startUrl);

    /**
     * 等待指定选择器首次出现（CSS 或 XPath 字符串）；超时返回 false。
     *
     * @param selector 字段选择器字符串
     * @param timeoutMs 超时毫秒（默认 15s）
     * @return 是否找到
     */
    boolean waitForSelector(String selector, long timeoutMs);

    /** 字段配置中允许的额外等待（{@code extraWaitSeconds 0-5}）。 */
    void extraWaitSeconds(int seconds);

    /** 导航 + 重定向后的最终 URL。 */
    String currentUrl();

    /**
     * 构造满足 {@link ExtractionPreview.DomState} 的实现，供本运行复用
     * （在 lane 线程内查询 DOM）。
     */
    ExtractionPreview.DomState acquireDomState();

    /** 关闭 BrowserContext 与 Page；运行结束后由 dispatcher / executor 在 finally 块调用。 */
    @Override
    void close();

    /**
     * 导航结果（spec §D9 + §D20：429 / 持续 403 / 验证码处理）。
     *
     * @param ok              导航是否成功到达 DOMContentLoaded
     * @param httpStatus      HTTP 响应码；导航失败或非 HTTP 场景为 {@code 0}
     * @param captchaDetected 是否命中验证码页面
     * @param errorMessage    失败时的简要错误（用户可见摘要，不含堆栈）
     */
    record NavigationResult(boolean ok, int httpStatus, boolean captchaDetected, String errorMessage) {
    }
}
