package com.visualspider.run.spi;

/**
 * 采集运行停止原因（M3 spec §D11）。
 *
 * <p>前向兼容全集；M3 单页只发部分：
 * <ul>
 *   <li>M3 单页可达：{@link #COMPLETED} / {@link #USER_CANCEL} / {@link #ENTRY_FAILED} /
 *       {@link #BROWSER_START_FAILED} / {@link #PAGE_RETRY_EXHAUSTED} / {@link #TIME_LIMIT} /
 *       {@link #HTTP_429} / {@link #HTTP_403} / {@link #CAPTCHA} / {@link #APP_INTERRUPTED}</li>
 *   <li>M4/M5 多页可达：{@link #PAGE_LIMIT} / {@link #RECORD_LIMIT}</li>
 * </ul>
 *
 * <p>{@code WAITING} / {@code RUNNING} 阶段 {@code stop_reason} 为 {@code null}。
 */
public enum StopReason {
    /** 正常完成（M3 单页可达）。 */
    COMPLETED,
    /** 用户取消。 */
    USER_CANCEL,
    /** 入口页打不开 / 最终 URL 不合法。 */
    ENTRY_FAILED,
    /** 浏览器 / BrowserContext 启动失败。 */
    BROWSER_START_FAILED,
    /** 单页重试 3 次全败。 */
    PAGE_RETRY_EXHAUSTED,
    /** 200 页上限（M4/M5 可达）。 */
    PAGE_LIMIT,
    /** 10000 条上限（M4/M5 可达）。 */
    RECORD_LIMIT,
    /** 30 分钟上限。 */
    TIME_LIMIT,
    /** 429 退避停止。 */
    HTTP_429,
    /** 持续 403 停止。 */
    HTTP_403,
    /** 验证码停止。 */
    CAPTCHA,
    /** 启动恢复标记。 */
    APP_INTERRUPTED
}