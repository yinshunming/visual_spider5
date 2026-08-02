package com.visualspider.run.internal;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import com.visualspider.extraction.spi.ExtractionPreview;
import com.visualspider.extraction.spi.ExtractionPreview.DomState;
import com.visualspider.run.spi.RunPageHandle;
import com.visualspider.task.domain.SelectorType;
import com.visualspider.visualbrowser.BrowserLane;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link RunPageHandle} 的生产实现（issue #25 / spec §D9）。
 *
 * <p>在 {@link BrowserLane} 固定线程上创建独立、非持久化的
 * {@link BrowserContext} + {@link Page}；所有 Playwright 操作经 {@code lane.submit}
 * 抛到 lane 线程执行，避免跨线程调用 Playwright 对象。
 *
 * <p>Playwright 类型只出现在 {@code run.internal} 包内，不泄漏到
 * {@link RunPageHandle} SPI / 模块接口（ADR-0003 seam）。
 */
public final class DefaultRunPageHandle implements RunPageHandle {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultRunPageHandle.class);
    /** captcha 检测的页面特征字符串（启发式；M3 一次会话级别即可避免 DoS，精确判定留 M6）。 */
    private static final List<String> CAPTCHA_MARKERS = List.of(
            "g-recaptcha", "h-captcha", "cf-challenge", "hcaptcha");

    private final BrowserLane lane;
    private final Page page;
    private final long runId;
    private volatile boolean closed;

    public DefaultRunPageHandle(BrowserLane lane, long runId) {
        this.lane = lane;
        this.runId = runId;
        this.page = lane.createRunPage();
    }

    @Override
    public NavigationResult navigateAndAwaitDomContentLoaded(String startUrl) {
        try {
            return lane.submit(() -> {
                Response resp;
                try {
                    resp = page.navigate(startUrl, new Page.NavigateOptions()
                            .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED)
                            .setTimeout(15_000));
                } catch (RuntimeException ex) {
                    return new NavigationResult(false, 0, false, safeMsg(ex));
                }
                int status = resp == null ? 0 : resp.status();
                boolean captcha = detectCaptcha(page);
                return new NavigationResult(true, status, captcha, null);
            }).join();
        } catch (RuntimeException ex) {
            return new NavigationResult(false, 0, false, safeMsg(ex));
        }
    }

    @Override
    public boolean waitForSelector(String selector, long timeoutMs) {
        try {
            return lane.submit(() -> {
                try {
                    page.waitForSelector(selector, new Page.WaitForSelectorOptions()
                            .setTimeout(timeoutMs));
                    return true;
                } catch (RuntimeException ex) {
                    return false;
                }
            }).join();
        } catch (RuntimeException ex) {
            LOG.warn("waitForSelector failed runId={} sel={}: {}", runId, selector, safeMsg(ex));
            return false;
        }
    }

    @Override
    public void extraWaitSeconds(int seconds) {
        if (seconds <= 0) {
            return;
        }
        try {
            lane.submit(() -> {
                try {
                    Thread.sleep((long) seconds * 1000L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                return null;
            }).join();
        } catch (RuntimeException ex) {
            LOG.warn("extraWaitSeconds failed runId={}: {}", runId, safeMsg(ex));
        }
    }

    @Override
    public String currentUrl() {
        try {
            return lane.submit(() -> page.url()).join();
        } catch (RuntimeException ex) {
            LOG.warn("currentUrl failed runId={}: {}", runId, safeMsg(ex));
            return "";
        }
    }

    @Override
    public DomState acquireDomState() {
        final String currentUrl = currentUrl();
        return new DomState() {
            @Override
            public String url() {
                return currentUrl;
            }

            @Override
            public List<ExtractionPreview.Node> query(String selector, SelectorType type) {
                try {
                    return lane.submit(() -> runQueryJs(selector, type)).join();
                } catch (RuntimeException ex) {
                    throw new RuntimeException("DOM query failed: " + safeMsg(ex), ex);
                }
            }
        };
    }

    @SuppressWarnings("unchecked")
    private List<ExtractionPreview.Node> runQueryJs(String selector, SelectorType type) {
        String js = "(args) => {"
                + "  const sel = args.sel, t = args.type;"
                + "  let raw = [];"
                + "  try {"
                + "    if (t === 'xpath') {"
                + "      const xr = document.evaluate(sel, document, null, XPathResult.ORDERED_NODE_SNAPSHOT_TYPE, null);"
                + "      for (let i = 0; i < xr.snapshotLength; i++) raw.push(xr.snapshotItem(i));"
                + "    } else {"
                + "      raw = Array.from(document.querySelectorAll(sel));"
                + "    }"
                + "  } catch (e) { return { error: String(e) }; }"
                + "  return raw.filter(n => n && n.nodeType === 1).map(el => {"
                + "    const attrs = {};"
                + "    for (const a of el.attributes) attrs[a.name] = a.value;"
                + "    return { tagName: el.tagName, id: el.id || '', className: el.className || '',"
                + "      textContent: (el.textContent || '').substring(0, 500), attributes: attrs };"
                + "  });"
                + "}";
        Object result;
        try {
            result = page.evaluate(js,
                    Map.of("sel", selector == null ? "" : selector,
                            "type", (type == null ? SelectorType.CSS : type).name().toLowerCase()));
        } catch (RuntimeException ex) {
            throw new RuntimeException("DOM query failed: " + safeMsg(ex), ex);
        }
        if (result instanceof Map<?, ?> errMap) {
            Object err = errMap.get("error");
            if (err != null) {
                throw new RuntimeException(String.valueOf(err));
            }
            return List.of();
        }
        List<Map<String, Object>> raw = (List<Map<String, Object>>) result;
        List<ExtractionPreview.Node> nodes = new ArrayList<>(raw.size());
        for (Map<String, Object> m : raw) {
            Map<String, String> attrs = (Map<String, String>) m.getOrDefault("attributes", Map.of());
            nodes.add(new ExtractionPreview.Node(
                    (String) m.getOrDefault("tagName", ""),
                    (String) m.getOrDefault("id", ""),
                    (String) m.getOrDefault("className", ""),
                    (String) m.getOrDefault("textContent", ""),
                    attrs));
        }
        return nodes;
    }

    /** captcha 启发式（页面 HTML 含常见 captcha 标记 -&gt; 视为验证码）。 */
    private boolean detectCaptcha(Page p) {
        try {
            String html = p.content();
            if (html == null) return false;
            String lower = html.toLowerCase();
            for (String marker : CAPTCHA_MARKERS) {
                if (lower.contains(marker)) return true;
            }
        } catch (RuntimeException ignored) {
            // CAPTCHA 探测失败不影响主流程
        }
        return false;
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
                try {
                    BrowserContext ctx = page.context();
                    if (ctx != null) ctx.close();
                } catch (RuntimeException ignored) {
                }
                return null;
            }).join();
        } catch (RuntimeException ex) {
            LOG.warn("DefaultRunPageHandle close failed runId={}: {}", runId, safeMsg(ex));
        }
    }

    private static String safeMsg(Throwable t) {
        if (t == null) return "";
        String m = t.getMessage();
        return m == null || m.isBlank() ? t.getClass().getSimpleName() : m;
    }
}
