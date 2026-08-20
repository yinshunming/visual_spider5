package com.visualspider.run.internal;

import com.microsoft.playwright.Page;
import com.visualspider.extraction.spi.ExtractionPreview;
import com.visualspider.extraction.spi.ExtractionPreview.DomState;
import com.visualspider.extraction.spi.ExtractionPreview.Node;
import com.visualspider.run.spi.ContentPageHandle;
import com.visualspider.task.domain.SelectorType;
import com.visualspider.visualbrowser.BrowserLane;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 内容页句柄生产实现（M5-4 / issue #42 / spec §D6）。
 *
 * <p>在 {@link BrowserLane} 固定线程上通过 {@code BrowserContext.newPage()} 创建独立 Page；
 * 所有 Playwright 操作经 {@code lane.submit} 抛到 lane 线程执行，避免跨线程调用。
 * Playwright 类型只出现在本包内，不泄漏到 {@link ContentPageHandle} SPI。
 */
final class DefaultContentPageHandle implements ContentPageHandle {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultContentPageHandle.class);

    private final BrowserLane lane;
    private final Page page;
    private volatile boolean closed;

    DefaultContentPageHandle(BrowserLane lane, Page page) {
        this.lane = lane;
        this.page = page;
    }

    @Override
    public String currentUrl() {
        return lane.submit(() -> page.url()).join();
    }

    @Override
    public DomState acquireDomState() {
        final String url = currentUrl();
        return new DomState() {
            @Override
            public String url() {
                return url;
            }

            @Override
            public List<Node> query(String selector, SelectorType type) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> raw;
                try {
                    raw = lane.submit(() -> {
                        String js = buildQueryJs(selector, type);
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> r = (List<Map<String, Object>>) page.evaluate(js,
                                Map.of("sel", selector == null ? "" : selector,
                                        "type", (type == null ? SelectorType.CSS : type).name().toLowerCase()));
                        return r;
                    }).join();
                } catch (RuntimeException ex) {
                    throw new RuntimeException("DOM query failed: " + safeMsg(ex), ex);
                }
                List<Node> nodes = new ArrayList<>(raw == null ? 0 : raw.size());
                if (raw != null) {
                    for (Map<String, Object> m : raw) {
                        @SuppressWarnings("unchecked")
                        Map<String, String> attrs = (Map<String, String>) m.getOrDefault("attributes", Map.of());
                        nodes.add(new Node(
                                (String) m.getOrDefault("tagName", ""),
                                (String) m.getOrDefault("id", ""),
                                (String) m.getOrDefault("className", ""),
                                (String) m.getOrDefault("textContent", ""),
                                attrs));
                    }
                }
                return nodes;
            }
        };
    }

    private static String buildQueryJs(String selector, SelectorType type) {
        // 内容页查询：scope 已是整页，不需要 scoped 路径；用 querySelectorAll / xpath。
        return "(args) => {"
                + "  const sel = args.sel, t = args.type;"
                + "  let raw = [];"
                + "  try {"
                + "    if (t === 'xpath') {"
                + "      const xr = document.evaluate(sel, document, null, XPathResult.ORDERED_NODE_SNAPSHOT_TYPE, null);"
                + "      for (let i = 0; i < xr.snapshotLength; i++) raw.push(xr.snapshotItem(i));"
                + "    } else {"
                + "      raw = Array.from(document.querySelectorAll(sel));"
                + "    }"
                + "  } catch (e) { return []; }"
                + "  return raw.filter(n => n && n.nodeType === 1).map(el => {"
                + "    const attrs = {};"
                + "    for (const a of el.attributes) attrs[a.name] = a.value;"
                + "    return { tagName: el.tagName, id: el.id || '', className: el.className || '',"
                + "      textContent: (el.textContent || '').substring(0, 500), attributes: attrs };"
                + "  });"
                + "}";
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try {
            lane.submit(() -> {
                try {
                    page.close();
                } catch (RuntimeException ignored) {
                }
                return null;
            }).join();
        } catch (RuntimeException ex) {
            LOG.warn("DefaultContentPageHandle close failed: {}", safeMsg(ex));
        }
    }

    private static String safeMsg(Throwable t) {
        if (t == null) return "";
        String m = t.getMessage();
        return m == null || m.isBlank() ? t.getClass().getSimpleName() : m;
    }
}