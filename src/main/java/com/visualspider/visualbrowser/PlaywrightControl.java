package com.visualspider.visualbrowser;

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

    /** 视口截图（非整页），存内存返回 JPEG 字节数组，供 screenshot 帧生产回退使用。 */
    public CompletableFuture<byte[]> screenshotViewport() {
        return lane.submit(() -> page().screenshot(new Page.ScreenshotOptions()
                .setType(ScreenshotType.JPEG)
                .setQuality(80)));
    }

    /**
     * 选择模式：按远程视口坐标检查 DOM 元素，返回摘要（含 boundingBox 与候选 CSS/XPath）。
     * 用 {@code document.elementFromPoint} 重新查询，不触发原页面动作，不保存 ElementHandle。
     */
    @SuppressWarnings("unchecked")
    public CompletableFuture<SelectionRecord> inspectElement(int x, int y) {
        return lane.submit(() -> {
            String js = "() => {"
                    + "  const el = document.elementFromPoint(" + x + ", " + y + ");"
                    + "  if (!el) return null;"
                    + "  const r = el.getBoundingClientRect();"
                    + "  return { tagName: el.tagName, id: el.id || '', className: el.className || '',"
                    + "    text: (el.textContent || '').substring(0, 200),"
                    + "    x: r.x, y: r.y, width: r.width, height: r.height };"
                    + "}";
            java.util.Map<String, Object> info = (java.util.Map<String, Object>) page().evaluate(js);
            if (info == null) {
                return null;
            }
            String tagName = (String) info.get("tagName");
            String id = (String) info.get("id");
            String className = (String) info.get("className");
            return new SelectionRecord(
                    tagName,
                    id,
                    className,
                    (String) info.get("text"),
                    ((Number) info.get("x")).doubleValue(),
                    ((Number) info.get("y")).doubleValue(),
                    ((Number) info.get("width")).doubleValue(),
                    ((Number) info.get("height")).doubleValue(),
                    CandidateGenerator.css(tagName, id, className),
                    CandidateGenerator.xpath(tagName, id, className)
            );
        });
    }

    /**
     * 手写选择器校验：querySelectorAll(css) 或 document.evaluate(xpath) 重新查询 DOM，
     * 返回匹配数与每个元素摘要（boundingBox 远程视口坐标）；非法语法返回 valid=false + error。
     * 不保存 ElementHandle。
     */
    @SuppressWarnings("unchecked")
    public CompletableFuture<ValidationResult> validateSelector(String selector, String type) {
        return lane.submit(() -> {
            Object result = page().evaluate(
                    "(args) => {"
                            + "  const sel = args.sel, t = args.type;"
                            + "  try {"
                            + "    let nodes = [];"
                            + "    if (t === 'css') {"
                            + "      nodes = Array.from(document.querySelectorAll(sel));"
                            + "    } else {"
                            + "      const xr = document.evaluate(sel, document, null, XPathResult.ORDERED_NODE_SNAPSHOT_TYPE, null);"
                            + "      for (let i = 0; i < xr.snapshotLength; i++) nodes.push(xr.snapshotItem(i));"
                            + "    }"
                            + "    return nodes.filter(n => n.nodeType === 1).map(el => {"
                            + "      const r = el.getBoundingClientRect();"
                            + "      return { tagName: el.tagName, id: el.id || '', className: el.className || '',"
                            + "        text: (el.textContent || '').substring(0, 100),"
                            + "        x: r.x, y: r.y, width: r.width, height: r.height };"
                            + "    });"
                            + "  } catch (e) { return { error: String(e) }; }"
                            + "}",
                    java.util.Map.of("sel", selector, "type", type)
            );
            if (result instanceof java.util.Map) {
                java.util.Map<?, ?> errMap = (java.util.Map<?, ?>) result;
                Object err = errMap.get("error");
                if (err != null) {
                    return new ValidationResult(false, 0, String.valueOf(err), java.util.List.of());
                }
            }
            java.util.List<java.util.Map<String, Object>> maps =
                    (java.util.List<java.util.Map<String, Object>>) result;
            java.util.List<ElementSummary> elements = new java.util.ArrayList<>();
            for (java.util.Map<String, Object> m : maps) {
                elements.add(new ElementSummary(
                        (String) m.get("tagName"),
                        (String) m.get("id"),
                        (String) m.get("className"),
                        (String) m.get("text"),
                        ((Number) m.get("x")).doubleValue(),
                        ((Number) m.get("y")).doubleValue(),
                        ((Number) m.get("width")).doubleValue(),
                        ((Number) m.get("height")).doubleValue()
                ));
            }
            return new ValidationResult(true, elements.size(), null, elements);
        });
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

    /**
     * 查询选择器匹配的所有节点摘要（tagName/id/className/textContent/attributes）。
     * 在 lane 线程执行；返回的 Map 不含 ElementHandle，供 M2-3 #19 预览管道构造 DomState。
     */
    @SuppressWarnings("unchecked")
    public CompletableFuture<java.util.List<java.util.Map<String, Object>>> queryFieldNodes(String selector) {
        return lane.submit(() -> {
            String js = "(sel) => {"
                    + "  const nodes = Array.from(document.querySelectorAll(sel));"
                    + "  return nodes.map(el => {"
                    + "    const attrs = {};"
                    + "    for (const a of el.attributes) attrs[a.name] = a.value;"
                    + "    return { tagName: el.tagName, id: el.id || '', className: el.className || '',"
                    + "      textContent: (el.textContent || '').substring(0, 500), attributes: attrs };"
                    + "  });"
                    + "}";
            return (java.util.List<java.util.Map<String, Object>>) page().evaluate(js, selector);
        });
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
