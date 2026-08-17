package com.visualspider.visualbrowser.internal;

import com.microsoft.playwright.Page;
import com.visualspider.extraction.spi.ExtractionPreview;
import com.visualspider.extraction.spi.ExtractionPreview.DomState;
import com.visualspider.extraction.spi.ExtractionPreview.ListPreviewResult;
import com.visualspider.extraction.spi.ExtractionDiagnostic;
import com.visualspider.extraction.spi.ExtractionDiagnostic.DiagnosticCode;
import com.visualspider.extraction.spi.PreviewResult.FieldOutcome;
import com.visualspider.task.domain.TaskDefinition;
import com.visualspider.task.domain.TaskMode;
import com.visualspider.task.spi.LiveReadinessHook;
import com.visualspider.visualbrowser.BrowserLane;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * 实匹配校验 hook 生产实现（M4-3 #33 / spec §D10）。
 *
 * <p>在共享 {@link BrowserLane} 上为每次 check 创建独立非持久化 BrowserContext + Page
 * （spec §D9：每次运行独立 BrowserContext），导航到 {@code definition.startUrl} 后跑
 * {@link ExtractionPreview#previewList} 拿 {@code totalMatchCount} + 字段 {@code MULTIPLE_MATCH}
 * 诊断，映射为 {@link LiveReadinessOutcome}。
 *
 * <p>仅 LIST 模式触发（与 {@code TaskReadinessImpl.validate} 一致）；SINGLE_PAGE 模式返 ok。
 * 异常（导航失败 / lane 已关闭）走安全路径返 ok，避免单次校验异常阻塞整个 save 流程；
 * 真实错误在日志暴露（{@code spec §3} 不记录页面内容 / 字段值，仅记 startUrl 与异常类型）。
 *
 * <p>生产性能注意：每次 check 冷启 Chromium（~秒级）。M4-3 acceptance 用真实路径，M6 再考虑
 * BrowserLane 池化 / 复用（不在本 ticket 范围）。
 */
@Component
@Primary
public class PlaywrightLiveReadinessHook implements LiveReadinessHook, DisposableBean {

    private static final Logger LOG = LoggerFactory.getLogger(PlaywrightLiveReadinessHook.class);
    private static final int MAX_PREVIEW_ITEMS = 20;

    private final BrowserLane lane;
    private final ExtractionPreview extraction;
    private final BasicTargetUrlPolicy targetUrlPolicy;

    public PlaywrightLiveReadinessHook(BrowserLane lane,
                                      ExtractionPreview extraction,
                                      BasicTargetUrlPolicy targetUrlPolicy) {
        if (lane == null) {
            throw new IllegalArgumentException("lane 不能为空");
        }
        if (extraction == null) {
            throw new IllegalArgumentException("extraction 不能为空");
        }
        if (targetUrlPolicy == null) {
            throw new IllegalArgumentException("targetUrlPolicy 不能为空");
        }
        this.lane = lane;
        this.extraction = extraction;
        this.targetUrlPolicy = targetUrlPolicy;
    }

    @Override
    public LiveReadinessOutcome check(TaskDefinition definition, long actorId) {
        if (definition == null) {
            return LiveReadinessOutcome.ok();
        }
        if (!(definition.mode() instanceof TaskMode.List)) {
            return LiveReadinessOutcome.ok();
        }
        if (definition.startUrl() == null || definition.startUrl().isBlank()
                || definition.listItemRule() == null
                || definition.listItemRule().selector() == null
                || definition.listItemRule().selector().isBlank()) {
            return LiveReadinessOutcome.ok();  // 语法层 validate 已拒，不必 live
        }
        targetUrlPolicy.validate(definition.startUrl());  // 抛 = 配置错（保存前已通过）
        Page page = null;
        try {
            page = lane.createRunPage();
            page.navigate(definition.startUrl());
            DomState dom = buildDomState(page);
            ListPreviewResult result = extraction.previewList(definition, dom, MAX_PREVIEW_ITEMS);
            return buildOutcome(result);
        } catch (RuntimeException ex) {
            // 真实错误记日志但不阻塞 save：M4 阶段2 acceptance 让 save 流程不被单次 live check 异常卡住
            LOG.warn("live readiness check failed startUrl={} cause={}",
                    safeUrl(definition.startUrl()), ex.getMessage());
            return LiveReadinessOutcome.ok();
        } finally {
            if (page != null) {
                try {
                    page.context().close();
                } catch (RuntimeException ignored) {
                    // 关闭异常不影响 check 结果
                }
            }
        }
    }

    @Override
    public void destroy() {
        // BrowserLane 由 Spring 容器管理（ConfigLanePool / 独立 bean），不在此关闭
    }

    /**
     * 构造单次 check 用的 DomState（与 {@code VisualSession.buildDomState} 同模式：
     * cache lastSelector/lastType/lastNodes + scopeToNode 跑第 N 个父元素子树）。
     */
    private DomState buildDomState(Page page) {
        return new DomState() {
            private String lastSelector;
            private com.visualspider.task.domain.SelectorType lastType;
            private List<com.visualspider.extraction.spi.ExtractionPreview.Node> lastNodes = List.of();

            @Override
            public String url() {
                return page.url();
            }

            @Override
            public List<com.visualspider.extraction.spi.ExtractionPreview.Node> query(
                    String selector, com.visualspider.task.domain.SelectorType type) {
                String js = "(args) => {"
                        + "  const sel = args.sel, t = args.type;"
                        + "  let raw = [];"
                        + "  try {"
                        + "    if (t === 'xpath') {"
                        + "      const xr = document.evaluate(sel, document, null, "
                        + "          XPathResult.ORDERED_NODE_SNAPSHOT_TYPE, null);"
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
                Object result = page.evaluate(js, java.util.Map.of("sel", selector,
                        "type", type == null
                                ? com.visualspider.task.domain.SelectorType.CSS.name().toLowerCase()
                                : type.name().toLowerCase()));
                if (result instanceof java.util.Map) {
                    java.util.Map<?, ?> errMap = (java.util.Map<?, ?>) result;
                    Object err = errMap.get("error");
                    if (err != null) {
                        throw new RuntimeException(String.valueOf(err));
                    }
                }
                List<java.util.Map<String, Object>> maps = (List<java.util.Map<String, Object>>) result;
                List<com.visualspider.extraction.spi.ExtractionPreview.Node> nodes = new ArrayList<>();
                for (java.util.Map<String, Object> m : maps) {
                    nodes.add(new com.visualspider.extraction.spi.ExtractionPreview.Node(
                            (String) m.get("tagName"),
                            (String) m.get("id"),
                            (String) m.get("className"),
                            (String) m.get("textContent"),
                            (java.util.Map<String, String>) m.get("attributes")));
                }
                lastSelector = selector;
                lastType = type;
                lastNodes = nodes;
                return nodes;
            }

            @Override
            public DomState scopeToNode(com.visualspider.extraction.spi.ExtractionPreview.Node item) {
                if (item == null || lastSelector == null) {
                    throw new UnsupportedOperationException("scopeToNode: 无可定位的父查询");
                }
                int index = -1;
                for (int i = 0; i < lastNodes.size(); i++) {
                    if (lastNodes.get(i) == item) {
                        index = i;
                        break;
                    }
                }
                if (index < 0) {
                    throw new UnsupportedOperationException("scopeToNode: node 不在最近一次 query 结果中");
                }
                final String parentSelector = lastSelector;
                final com.visualspider.task.domain.SelectorType parentType =
                        lastType == null ? com.visualspider.task.domain.SelectorType.CSS : lastType;
                final int itemIndex = index;
                return new DomState() {
                    @Override
                    public String url() {
                        return page.url();
                    }

                    @Override
                    public List<com.visualspider.extraction.spi.ExtractionPreview.Node> query(
                            String selector, com.visualspider.task.domain.SelectorType type) {
                        return runScopedQueryJs(page, parentSelector, parentType, itemIndex, selector, type);
                    }
                };
            }
        };
    }

    @SuppressWarnings("unchecked")
    private List<com.visualspider.extraction.spi.ExtractionPreview.Node> runScopedQueryJs(
            Page page, String parentSelector, com.visualspider.task.domain.SelectorType parentType,
            int itemIndex, String fieldSelector, com.visualspider.task.domain.SelectorType fieldType) {
        String js = "(args) => {"
                + "  const pSel = args.pSel, pType = args.pType, idx = args.idx;"
                + "  const fSel = args.fSel, fType = args.fType;"
                + "  let parents = [];"
                + "  try {"
                + "    if (pType === 'xpath') {"
                + "      const xr = document.evaluate(pSel, document, null, XPathResult.ORDERED_NODE_SNAPSHOT_TYPE, null);"
                + "      for (let i = 0; i < xr.snapshotLength; i++) parents.push(xr.snapshotItem(i));"
                + "    } else { parents = Array.from(document.querySelectorAll(pSel)); }"
                + "  } catch (e) { return { error: String(e) }; }"
                + "  const parent = parents[idx];"
                + "  if (!parent) return [];"
                + "  let raw = [];"
                + "  try {"
                + "    if (fType === 'xpath') {"
                + "      const xr2 = document.evaluate(fSel, parent, null, XPathResult.ORDERED_NODE_SNAPSHOT_TYPE, null);"
                + "      for (let i = 0; i < xr2.snapshotLength; i++) raw.push(xr2.snapshotItem(i));"
                + "    } else { raw = Array.from(parent.querySelectorAll(fSel)); }"
                + "  } catch (e) { return { error: String(e) }; }"
                + "  return raw.filter(n => n && n.nodeType === 1).map(el => {"
                + "    const attrs = {};"
                + "    for (const a of el.attributes) attrs[a.name] = a.value;"
                + "    return { tagName: el.tagName, id: el.id || '', className: el.className || '',"
                + "      textContent: (el.textContent || '').substring(0, 500), attributes: attrs };"
                + "  });"
                + "}";
        Object result = page.evaluate(js, java.util.Map.of(
                "pSel", parentSelector, "pType", parentType.name().toLowerCase(),
                "idx", itemIndex, "fSel", fieldSelector,
                "fType", fieldType == null
                        ? com.visualspider.task.domain.SelectorType.CSS.name().toLowerCase()
                        : fieldType.name().toLowerCase()));
        if (result instanceof java.util.Map) {
            java.util.Map<?, ?> errMap = (java.util.Map<?, ?>) result;
            Object err = errMap.get("error");
            if (err != null) {
                throw new RuntimeException(String.valueOf(err));
            }
        }
        List<java.util.Map<String, Object>> maps = (List<java.util.Map<String, Object>>) result;
        List<com.visualspider.extraction.spi.ExtractionPreview.Node> nodes = new ArrayList<>();
        for (java.util.Map<String, Object> m : maps) {
            nodes.add(new com.visualspider.extraction.spi.ExtractionPreview.Node(
                    (String) m.get("tagName"),
                    (String) m.get("id"),
                    (String) m.get("className"),
                    (String) m.get("textContent"),
                    (java.util.Map<String, String>) m.get("attributes")));
        }
        return nodes;
    }

    private static LiveReadinessOutcome buildOutcome(ListPreviewResult result) {
        List<String> codes = new ArrayList<>();
        List<String> messages = new ArrayList<>();
        // listItemRule 命中数 < 2
        if (result.totalMatchCount() < 2) {
            codes.add("LIST_ITEM_RULE_NO_MATCH");
            messages.add("列表项规则匹配数少于 2 (实际 " + result.totalMatchCount() + ")");
        }
        // 任一字段 MULTIPLE_MATCH
        for (com.visualspider.extraction.spi.PreviewResult pr : result.previews()) {
            for (ExtractionDiagnostic d : pr.diagnostics()) {
                if (d.code() == DiagnosticCode.MULTIPLE_MATCH) {
                    codes.add("MULTIPLE_MATCH");
                    messages.add(d.userMessage() + " (field=" + d.fieldName() + ")");
                }
            }
            // field.isEmpty 但 preview 不记 MULTIPLE_MATCH：兜底看 rawValue 非 null 且 cleanedValue null
            // 此处只走显式 MULTIPLE_MATCH 诊断，与 preview 路径一致（M3 取首个 + WARN）。
        }
        if (codes.isEmpty()) {
            return LiveReadinessOutcome.ok();
        }
        return LiveReadinessOutcome.block(codes, messages);
    }

    private static String safeUrl(String url) {
        if (url == null) return "<null>";
        // spec §3：日志不记完整 startUrl（含 query）会泄漏内部路径；仅记 scheme + host
        int slash = url.indexOf("//");
        if (slash < 0) return "<no-scheme>";
        int third = url.indexOf('/', slash + 2);
        return third < 0 ? url : url.substring(0, third);
    }
}