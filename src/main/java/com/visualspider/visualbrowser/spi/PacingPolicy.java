package com.visualspider.visualbrowser.spi;

import java.net.URL;

/**
 * 同域请求限速策略（M5 spec §D9 / issue #43）。
 *
 * <p>由 {@code MultiPageRunExecutor} / {@code ContentPageFetcher} 在每次
 * {@code page.navigate} 之前调用 {@link #beforeNavigate(URL)}，强制同目标域
 * 两次 navigate 间隔至少 {@code INTERVAL}（默认 1s），避免触发目标站点反爬
 * （{@code 429} / 持续 {@code 403} / 验证码）。
 *
 * <p>默认实现 {@code SingleDomainPacingPolicy}（并发 1 + 间隔 1s）；
 * 单实例约束（M3 + ADR-0006）下"并发 1"自动满足，本接口主要承担"间隔 ≥ 1s"。
 * M6 加 {@code system_setting} 时只改实现 + 读取路径，不改调用点。
 *
 * <p>调用方契约：在调用 {@code beforeNavigate} 后再调用 {@code page.navigate}；
 * 若调用方在同一线程串行执行（单 lane），{@code SingleDomainPacingPolicy}
 * 自动满足并发 1；如果将来引入并行 navigate，policy 也应保持正确。
 */
public interface PacingPolicy {

    /** 默认间隔（M5 spec §D9：1s）。 */
    long DEFAULT_INTERVAL_MS = 1_000L;

    /**
     * 在 navigate {@code targetUrl} 之前调用；本方法会阻塞至"距上次同域 navigate
     * 间隔 ≥ 1s"（默认实现行为）。
     *
     * @param targetUrl 即将 navigate 的目标 URL（已通过 {@code TargetUrlPolicy.validate}）
     */
    void beforeNavigate(URL targetUrl);

    /** 同上但接受 {@link String}（便捷调用，避免调用方处理 {@link java.net.URISyntaxException}）。 */
    default void beforeNavigate(String targetUrl) {
        if (targetUrl == null || targetUrl.isBlank()) {
            return;
        }
        try {
            beforeNavigate(java.net.URI.create(targetUrl).toURL());
        } catch (java.net.MalformedURLException | java.lang.IllegalArgumentException ex) {
            // 非法 URL：policy 仅关心 host 间隔；调用方后续 navigate 会失败。静默忽略。
        }
    }
}