package com.visualspider.spike.m0;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.BoundingBox;
import com.microsoft.playwright.options.ScreenshotType;

import java.util.concurrent.CompletableFuture;

/**
 * Playwright 控制类：所有 Playwright API 调用集中于此，经 {@link BrowserLane} 在固定线程执行。
 *
 * <p>方法返回 {@link CompletableFuture}，以便 M0-2 接入 WebSocket 异步输入通道时复用同一调用形式。
 * 截图只存内存返回字节数组，不写磁盘。
 *
 * <p>M0 spike；M2 提取为 visualbrowser 模块 adapter。
 */
public final class PlaywrightControl {

    private final BrowserLane lane;

    public PlaywrightControl(BrowserLane lane) {
        this.lane = lane;
    }

    public CompletableFuture<Void> navigate(String url) {
        return lane.submit(() -> {
            page().navigate(url);
            return null;
        });
    }

    public CompletableFuture<String> currentUrl() {
        return lane.submit(() -> page().url());
    }

    public CompletableFuture<Void> click(int x, int y) {
        return lane.submit(() -> {
            page().mouse().click(x, y);
            return null;
        });
    }

    public CompletableFuture<Void> type(String text) {
        return lane.submit(() -> {
            page().keyboard().type(text);
            return null;
        });
    }

    public CompletableFuture<Void> wheel(int deltaX, int deltaY) {
        return lane.submit(() -> {
            page().mouse().wheel(deltaX, deltaY);
            return null;
        });
    }

    public CompletableFuture<Void> goBack() {
        return lane.submit(() -> {
            page().goBack();
            return null;
        });
    }

    public CompletableFuture<Void> goForward() {
        return lane.submit(() -> {
            page().goForward();
            return null;
        });
    }

    public CompletableFuture<Void> reload() {
        return lane.submit(() -> {
            page().reload();
            return null;
        });
    }

    /** 整页截图，存内存返回 JPEG 字节数组，不写磁盘。 */
    public CompletableFuture<byte[]> screenshotFullPage() {
        return lane.submit(() -> page().screenshot(new Page.ScreenshotOptions()
                .setFullPage(true)
                .setType(ScreenshotType.JPEG)));
    }

    public CompletableFuture<String> textContent(String selector) {
        return lane.submit(() -> page().textContent(selector));
    }

    /** 元素中心坐标（viewport CSS 像素），供按坐标点击。 */
    public CompletableFuture<double[]> elementCenter(String selector) {
        return lane.submit(() -> {
            BoundingBox box = page().querySelector(selector).boundingBox();
            return new double[]{box.x + box.width / 2, box.y + box.height / 2};
        });
    }

    public CompletableFuture<Long> scrollY() {
        return lane.submit(() -> ((Number) page().evaluate("() => window.scrollY")).longValue());
    }

    /** 等待选择器匹配元素（Playwright 自动等待），供测试避免固定 sleep。 */
    public CompletableFuture<Void> waitForSelector(String selector) {
        return lane.submit(() -> {
            page().waitForSelector(selector);
            return null;
        });
    }

    /** 等待 window.scrollY 超过阈值（Playwright 自动等待），供滚轮测试避免固定 sleep。 */
    public CompletableFuture<Void> waitForScrollPast(long threshold) {
        return lane.submit(() -> {
            page().waitForFunction("t => window.scrollY > t", (int) threshold);
            return null;
        });
    }

    private Page page() {
        return lane.page();
    }
}
