package com.visualspider.run.spi;

import com.visualspider.extraction.spi.ExtractionPreview.DomState;

/**
 * 内容页句柄（M5-4 / issue #42 / spec §D6）。
 *
 * <p>由 {@link RunPageHandle#openContentPageAndAwaitDomContentLoaded} 在同一
 * {@code BrowserContext} 内打开独立 Page 并完成 {@code DOMContentLoaded} 后返回；
 * {@link ExtractionPreview} 用同一实例查询 {@code scope=CONTENT} 字段；用毕
 * {@link #close()} 关掉 Page，list page 不受影响。
 *
 * <p>实现位于 {@code run.internal.DefaultContentPageHandle}；生产 lane 线程内创建；
 * 测试可注入 fake（{@code run.internal.testutil.FakeContentPageHandle}）。
 */
public interface ContentPageHandle extends AutoCloseable {

    /** navigate + 重定向后的最终 URL（已通过 {@code TargetUrlPolicy.validate}）。 */
    String currentUrl();

    /** 内容页 DomState：供 {@link com.visualspider.extraction.spi.ExtractionPreview#preview} 查询 scope=CONTENT 字段。 */
    DomState acquireDomState();

    /** 关闭内容页 Page；调用方须用 try-with-resources 或 finally 保证关闭。 */
    @Override
    void close();
}