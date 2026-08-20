package com.visualspider.run.internal.testutil;

import com.visualspider.extraction.spi.ExtractionPreview.DomState;
import com.visualspider.run.spi.ContentPageHandle;
import java.util.List;

/**
 * ContentPageHandle 的最小 fake（issue #42）。
 *
 * <p>每个实例绑一个 URL 与一个 DomState（空查询）；用于 ContentPageFetcherTest
 * 与 MultiPageRunExecutorTest 不依赖真实 Chromium 的场景。
 */
public final class FakeContentPageHandle implements ContentPageHandle {

    private final String url;
    private final DomState domState;
    private boolean closed;

    public FakeContentPageHandle(String url) {
        this(url, new DomState() {
            @Override public String url() { return url; }
            @Override public List<com.visualspider.extraction.spi.ExtractionPreview.Node> query(
                    String selector, com.visualspider.task.domain.SelectorType type) {
                return List.of();
            }
        });
    }

    public FakeContentPageHandle(String url, DomState domState) {
        this.url = url;
        this.domState = domState;
    }

    @Override public String currentUrl() { return url; }
    @Override public DomState acquireDomState() { return domState; }
    @Override public void close() { closed = true; }

    public boolean closed() { return closed; }
}