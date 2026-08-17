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
            // 保留最近一次 query 的 selector/type/nodes，供 scopeToNode 按 identity 定位 index。
            // previewList 调用模式：query(listItemRule) 一次 -> 对每个 node scopeToNode -> scoped.query(field)。
            private String lastSelector;
            private SelectorType lastType;
            private List<ExtractionPreview.Node> lastNodes = List.of();

            @Override
            public String url() {
                return currentUrl;
            }

            @Override
            public List<ExtractionPreview.Node> query(String selector, SelectorType type) {
                List<ExtractionPreview.Node> nodes;
                try {
                    nodes = lane.submit(() -> runQueryJs(selector, type)).join();
                } catch (RuntimeException ex) {
                    throw new RuntimeException("DOM query failed: " + safeMsg(ex), ex);
                }
                lastSelector = selector;
                lastType = type;
                lastNodes = nodes == null ? List.of() : nodes;
                return lastNodes;
            }

            @Override
            public DomState scopeToNode(ExtractionPreview.Node item) {
                if (item == null || lastSelector == null) {
                    throw new UnsupportedOperationException("scopeToNode: 无可定位的父查询");
                }
                int index = -1;
                for (int i = 0; i < lastNodes.size(); i++) {
                    if (lastNodes.get(i) == item) {  // identity：previewList 回传同一 Node 实例
                        index = i;
                        break;
                    }
                }
                if (index < 0) {
                    throw new UnsupportedOperationException("scopeToNode: node 不在最近一次 query 结果中");
                }
                final String parentSelector = lastSelector;
                final SelectorType parentType = lastType == null ? SelectorType.CSS : lastType;
                final int itemIndex = index;
                return new DomState() {
                    @Override
                    public String url() {
                        return currentUrl;
                    }

                    @Override
                    public List<ExtractionPreview.Node> query(String selector, SelectorType type) {
                        try {
                            return lane.submit(() -> runScopedQueryJs(
                                    parentSelector, parentType, itemIndex, selector, type)).join();
                        } catch (RuntimeException ex) {
                            throw new RuntimeException("DOM scoped query failed: " + safeMsg(ex), ex);
                        }
                    }
                };
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
        return toNodes(result);
    }

    /**
     * 在第 {@code itemIndex} 个 listItemRule 命中元素子树内查询字段选择器（spec §D9 list-item 作用域）。
     *
     * <p>先按 {@code (parentSelector, parentType)} 重新定位父元素集合取第 {@code itemIndex} 个，
     * 再在其子树内按 {@code (fieldSelector, fieldType)} 查询。CSS 走 {@code element.querySelectorAll}，
     * XPath 走 {@code document.evaluate(sel, element, ...)}。
     */
    @SuppressWarnings("unchecked")
    private List<ExtractionPreview.Node> runScopedQueryJs(
            String parentSelector, SelectorType parentType, int itemIndex,
            String fieldSelector, SelectorType fieldType) {
        String js = "(args) => {"
                + "  const pSel = args.pSel, pType = args.pType, idx = args.idx;"
                + "  const fSel = args.fSel, fType = args.fType;"
                + "  let parents = [];"
                + "  try {"
                + "    if (pType === 'xpath') {"
                + "      const xr = document.evaluate(pSel, document, null, XPathResult.ORDERED_NODE_SNAPSHOT_TYPE, null);"
                + "      for (let i = 0; i < xr.snapshotLength; i++) parents.push(xr.snapshotItem(i));"
                + "    } else {"
                + "      parents = Array.from(document.querySelectorAll(pSel));"
                + "    }"
                + "  } catch (e) { return { error: String(e) }; }"
                + "  const parent = parents[idx];"
                + "  if (!parent) return [];"
                + "  let raw = [];"
                + "  try {"
                + "    if (fType === 'xpath') {"
                + "      const xr2 = document.evaluate(fSel, parent, null, XPathResult.ORDERED_NODE_SNAPSHOT_TYPE, null);"
                + "      for (let i = 0; i < xr2.snapshotLength; i++) raw.push(xr2.snapshotItem(i));"
                + "    } else {"
                + "      raw = Array.from(parent.querySelectorAll(fSel));"
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
            result = page.evaluate(js, Map.of(
                    "pSel", parentSelector == null ? "" : parentSelector,
                    "pType", parentType.name().toLowerCase(),
                    "idx", itemIndex,
                    "fSel", fieldSelector == null ? "" : fieldSelector,
                    "fType", (fieldType == null ? SelectorType.CSS : fieldType).name().toLowerCase()));
        } catch (RuntimeException ex) {
            throw new RuntimeException("DOM scoped query failed: " + safeMsg(ex), ex);
        }
        if (result instanceof Map<?, ?> errMap) {
            Object err = errMap.get("error");
            if (err != null) {
                throw new RuntimeException(String.valueOf(err));
            }
            return List.of();
        }
        return toNodes(result);
    }

    /** 把 page.evaluate 返回的 JS 对象数组映射为 {@link ExtractionPreview.Node} 列表（query 与 scoped 共用）。 */
    @SuppressWarnings("unchecked")
    private List<ExtractionPreview.Node> toNodes(Object result) {
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
