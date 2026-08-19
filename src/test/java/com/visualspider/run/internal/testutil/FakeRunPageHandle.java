package com.visualspider.run.internal.testutil;

import com.visualspider.extraction.spi.ExtractionPreview;
import com.visualspider.run.spi.RunPageHandle;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link RunPageHandle} 的最小可配置 fake（M3-3 #25）。
 *
 * <p>记录所有调用，并按测试预设的"调用 → 结果"栈返回；让单测不依赖 Chromium 即可
 * 覆盖导航 / 等待选择器 / extraWait / DomState 等路径。
 *
 * <p>典型用法：
 * <pre>{@code
 * var handle = new FakeRunPageHandle();
 * handle.queueNavigation(new NavigationResult(true, 200, false, null));
 * handle.queueWaitForSelector(true);
 * handle.setDomState(new TestDomState(...));
 * }</pre>
 */
public class FakeRunPageHandle implements RunPageHandle {

    private final List<NavigationResult> navigationQueue = new ArrayList<>();
    private final List<Boolean> waitQueue = new ArrayList<>();
    private final List<Long> waitTimeouts = new ArrayList<>();
    private final List<Integer> extraWaits = new ArrayList<>();
    private final List<ClickResult> clickQueue = new ArrayList<>();
    private final List<String> clickSelectors = new ArrayList<>();
    private int navigationCalls;
    private int waitCalls;
    private String currentUrl = "https://example.com/entry";
    private ExtractionPreview.DomState domState;
    private boolean closed;

    public void queueNavigation(NavigationResult result) {
        navigationQueue.add(result);
    }

    public void queueWaitForSelector(boolean found) {
        waitQueue.add(found);
    }

    public void queueClick(ClickResult result) {
        clickQueue.add(result);
    }

    public void setDomState(ExtractionPreview.DomState state) {
        this.domState = state;
    }

    public void setCurrentUrl(String url) {
        this.currentUrl = url;
    }

    public List<Long> waitTimeouts() {
        return List.copyOf(waitTimeouts);
    }

    public List<Integer> extraWaits() {
        return List.copyOf(extraWaits);
    }

    public int extraWaitCallCount() {
        return extraWaits.size();
    }

    public int navigationCallCount() {
        return navigationCalls;
    }

    public int waitCallCount() {
        return waitCalls;
    }

    public List<String> clickSelectors() {
        return List.copyOf(clickSelectors);
    }

    public int clickCallCount() {
        return clickSelectors.size();
    }

    public boolean closed() {
        return closed;
    }

    @Override
    public NavigationResult navigateAndAwaitDomContentLoaded(String startUrl) {
        navigationCalls++;
        if (navigationQueue.isEmpty()) {
            return new NavigationResult(true, 200, false, null);
        }
        return navigationQueue.remove(0);
    }

    @Override
    public boolean waitForSelector(String selector, long timeoutMs) {
        waitCalls++;
        waitTimeouts.add(timeoutMs);
        if (waitQueue.isEmpty()) {
            return true;
        }
        return waitQueue.remove(0);
    }

    @Override
    public void extraWaitSeconds(int seconds) {
        extraWaits.add(seconds);
    }

    @Override
    public String currentUrl() {
        return currentUrl;
    }

    @Override
    public ExtractionPreview.DomState acquireDomState() {
        return domState == null ? new TestDomState(currentUrl, List.of()) : domState;
    }

    @Override
    public ClickResult click(String selector, long timeoutMs) {
        clickSelectors.add(selector);
        if (clickQueue.isEmpty()) {
            return ClickResult.NOT_FOUND;
        }
        return clickQueue.remove(0);
    }

    @Override
    public void close() {
        closed = true;
    }
}
