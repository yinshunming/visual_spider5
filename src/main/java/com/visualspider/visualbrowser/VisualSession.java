package com.visualspider.visualbrowser;

import com.visualspider.extraction.spi.ExtractionPreview;
import com.visualspider.extraction.spi.PreviewResult;
import com.visualspider.task.domain.SelectorType;
import com.visualspider.task.domain.TaskDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;

/**
 * 远程浏览器配置会话：绑定一个 BrowserLane + Playwright 控制类 + 帧生产 + 帧缓冲 + 序号守卫。
 *
 * <p>M0 spike：不做认证/所有权/每用户一会话限制（M2）；单会话独立非持久化 BrowserContext。
 * 输入命令按远程视口换算坐标、拒绝越界/过期；帧通道只留最新帧（丢旧）。
 * 选择模式（TYPE_SELECT）按坐标检查 DOM 元素，不触发原页面动作，不保存 ElementHandle。
 * 手写选择器（TYPE_VALIDATE_SELECTOR）重新查询 DOM，不保存 ElementHandle。
 */
public final class VisualSession implements AutoCloseable {
    private final String sessionId;
    private final BrowserLane lane;
    private final PlaywrightControl control;
    private final FrameBuffer frameBuffer;
    private final InputSequencer sequencer;
    private final FrameProducer frameProducer;
    private FrameProducer.FrameHandle frameHandle;
    private volatile SelectionRecord selection;
    private volatile ValidationResult validationResult;

    public VisualSession(String sessionId, String startUrl) {
        this.sessionId = sessionId;
        this.lane = new BrowserLane();
        this.control = new PlaywrightControl(lane);
        this.frameBuffer = new FrameBuffer();
        this.sequencer = new InputSequencer();
        this.frameProducer = new ScreenshotFrameProducer(control, 100);
        try {
            control.navigate(startUrl).join();
            this.frameHandle = frameProducer.start(frameBuffer::push);
        } catch (Throwable t) {
            // 构造失败时回收已创建的 lane（含 Chromium），避免进程泄漏
            lane.close();
            throw t;
        }
    }

    public String sessionId() {
        return sessionId;
    }

    /** 包级访问控制类，供测试取元素坐标。 */
    public PlaywrightControl control() {
        return control;
    }

    /**
     * 处理输入命令。
     *
     * @return true 若接受（会话匹配 + 序号有效 + 坐标合法 + 类型已知）；false 拒绝
     */
    public boolean handle(InputCommand cmd) {
        if (!sessionId.equals(cmd.sessionId())) {
            return false;
        }
        if (!sequencer.accept(cmd.sequence())) {
            return false;
        }
        try {
            switch (cmd.type()) {
                case InputCommand.TYPE_CLICK -> {
                    int[] r = ViewportMapper.toRemote(cmd.x(), cmd.y(), cmd.clientWidth(), cmd.clientHeight());
                    if (r == null) {
                        return false;
                    }
                    control.click(r[0], r[1]).join();
                }
                case InputCommand.TYPE_WHEEL -> {
                    if (cmd.deltaX() == null || cmd.deltaY() == null) {
                        return false;
                    }
                    control.wheel(cmd.deltaX(), cmd.deltaY()).join();
                }
                case InputCommand.TYPE_KEY -> {
                    if (cmd.key() == null) {
                        return false;
                    }
                    control.type(cmd.key()).join();
                }
                case InputCommand.TYPE_NAVIGATE -> {
                    if (cmd.url() == null) {
                        return false;
                    }
                    control.navigate(cmd.url()).join();
                }
                case InputCommand.TYPE_BACK -> control.goBack().join();
                case InputCommand.TYPE_FORWARD -> control.goForward().join();
                case InputCommand.TYPE_RELOAD -> control.reload().join();
                case InputCommand.TYPE_SELECT -> {
                    int[] r = ViewportMapper.toRemote(cmd.x(), cmd.y(), cmd.clientWidth(), cmd.clientHeight());
                    if (r == null) {
                        return false;
                    }
                    this.selection = control.inspectElement(r[0], r[1]).join();
                }
                case InputCommand.TYPE_VALIDATE_SELECTOR -> {
                    if (cmd.selector() == null || cmd.selectorType() == null) {
                        return false;
                    }
                    this.validationResult = control.validateSelector(cmd.selector(), cmd.selectorType()).join();
                }
                default -> {
                    return false;
                }
            }
        } catch (CompletionException e) {
            return false;
        }
        return true;
    }

    /** 取最新帧并清空缓冲（丢旧语义：未发送的旧帧已被新帧覆盖）。 */
    public byte[] drainFrame() {
        return frameBuffer.drain();
    }

    public StatusMessage status() {
        return lane.submit(() -> new StatusMessage(
                sessionId,
                lane.page().url(),
                ViewportMapper.REMOTE_WIDTH,
                ViewportMapper.REMOTE_HEIGHT,
                false,
                null,
                selection,
                validationResult
        )).join();
    }

    public boolean isNavigation(InputCommand cmd) {
        return InputCommand.TYPE_NAVIGATE.equals(cmd.type())
                || InputCommand.TYPE_BACK.equals(cmd.type())
                || InputCommand.TYPE_FORWARD.equals(cmd.type())
                || InputCommand.TYPE_RELOAD.equals(cmd.type());
    }

    /**
     * 在当前 lane/Page 上对 definition 执行单条预览（M2-3 #19 / M3 spec §D7）。
     * 与 {@link #previewList} 共享 {@link #buildDomState()} 的 scopeToNode 实现，避免漂移。
     */
    public PreviewResult preview(TaskDefinition definition, ExtractionPreview extraction) {
        return lane.submit(() -> extraction.preview(definition, buildDomState())).join();
    }

    /**
     * 在当前 lane/Page 上对 definition 执行 list 受限预览（M4-3 #33 / spec §D9）：
     * 截前 {@code maxItems} 项逐条 scope 后预览。
     */
    public ExtractionPreview.ListPreviewResult previewList(TaskDefinition definition,
                                                          ExtractionPreview extraction,
                                                          int maxItems) {
        return lane.submit(() -> extraction.previewList(definition, buildDomState(), maxItems)).join();
    }

    /**
     * 在 lane 线程上按远程视口坐标采集 {@link com.visualspider.extraction.spi.DomSnapshot}
     * （M4-2 #32 / spec §D3）。与 {@link #preview} / {@link #previewList} 同一线程模型：
     * 在 lane 线程上 evaluate，主线程阻塞等待；不暴露 {@link java.util.concurrent.CompletableFuture}
     * 给 Web 线程（architecture §4.1）。
     */
    public com.visualspider.extraction.spi.DomSnapshot captureDomSnapshot(int remoteX, int remoteY) {
        return control.captureDomSnapshot(remoteX, remoteY).join();
    }

    /**
     * 构造 lane-线程绑定的 {@link ExtractionPreview.DomState}：query 一次性 evaluate 返回静态
     * Node 摘要（不持 ElementHandle）；缓存最近一次 query 的 selector/type/nodes，
     * 供 {@code scopeToNode(item)} 按 identity 定位 index 并在父元素子树内查询字段。
     *
     * <p>仅在 {@code lane.submit} 内调用；内层方法访问 {@code lane.page()} 仅 lane 线程安全。
     */
    @SuppressWarnings("unchecked")
    private ExtractionPreview.DomState buildDomState() {
        return new ExtractionPreview.DomState() {
            private String lastSelector;
            private SelectorType lastType;
            private List<ExtractionPreview.Node> lastNodes = List.of();

            @Override
            public String url() {
                return lane.page().url();
            }

            @Override
            public List<ExtractionPreview.Node> query(String selector, SelectorType type) {
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
                        + "  } catch (e) {"
                        + "    return { error: String(e) };"
                        + "  }"
                        + "  return raw.filter(n => n && n.nodeType === 1).map(el => {"
                        + "    const attrs = {};"
                        + "    for (const a of el.attributes) attrs[a.name] = a.value;"
                        + "    return { tagName: el.tagName, id: el.id || '', className: el.className || '',"
                        + "      textContent: (el.textContent || '').substring(0, 500), attributes: attrs };"
                        + "  });"
                        + "}";
                Object result = lane.page().evaluate(js,
                        java.util.Map.of("sel", selector, "type", type == null
                                ? SelectorType.CSS.name().toLowerCase()
                                : type.name().toLowerCase()));
                if (result instanceof java.util.Map) {
                    // 选择器语法错误；抛给调用方 → ExtractionPreviewImpl.readRaw 捕到并记 SELECTOR_SYNTAX_INVALID。
                    java.util.Map<?, ?> errMap = (java.util.Map<?, ?>) result;
                    Object err = errMap.get("error");
                    if (err != null) {
                        throw new RuntimeException(String.valueOf(err));
                    }
                }
                List<Map<String, Object>> maps = (List<Map<String, Object>>) result;
                List<ExtractionPreview.Node> nodes = new ArrayList<>();
                for (Map<String, Object> m : maps) {
                    nodes.add(new ExtractionPreview.Node(
                            (String) m.get("tagName"),
                            (String) m.get("id"),
                            (String) m.get("className"),
                            (String) m.get("textContent"),
                            (Map<String, String>) m.get("attributes")));
                }
                lastSelector = selector;
                lastType = type;
                lastNodes = nodes;
                return nodes;
            }

            @Override
            public ExtractionPreview.DomState scopeToNode(ExtractionPreview.Node item) {
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
                return new ExtractionPreview.DomState() {
                    @Override
                    public String url() {
                        return lane.page().url();
                    }

                    @Override
                    public List<ExtractionPreview.Node> query(String selector, SelectorType type) {
                        return runScopedQueryJs(parentSelector, parentType, itemIndex, selector, type);
                    }
                };
            }
        };
    }

    /**
     * 在第 {@code itemIndex} 个父元素子树内按字段选择器查询（spec §D9 list-item 作用域）。
     * 与 {@code DefaultRunPageHandle.runScopedQueryJs} 同样用 JS 一次性 evaluate；
     * preview 路径不持 ElementHandle（spec §D3）。
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
        Object result = lane.page().evaluate(js,
                java.util.Map.of("pSel", parentSelector, "pType", parentType.name().toLowerCase(),
                        "idx", itemIndex, "fSel", fieldSelector,
                        "fType", fieldType == null
                                ? SelectorType.CSS.name().toLowerCase()
                                : fieldType.name().toLowerCase()));
        if (result instanceof java.util.Map) {
            java.util.Map<?, ?> errMap = (java.util.Map<?, ?>) result;
            Object err = errMap.get("error");
            if (err != null) {
                throw new RuntimeException(String.valueOf(err));
            }
        }
        List<Map<String, Object>> maps = (List<Map<String, Object>>) result;
        List<ExtractionPreview.Node> nodes = new ArrayList<>();
        for (Map<String, Object> m : maps) {
            nodes.add(new ExtractionPreview.Node(
                    (String) m.get("tagName"),
                    (String) m.get("id"),
                    (String) m.get("className"),
                    (String) m.get("textContent"),
                    (Map<String, String>) m.get("attributes")));
        }
        return nodes;
    }

    @Override
    public void close() {
        if (frameHandle != null) {
            try {
                frameHandle.close();
            } catch (Exception ignored) {
            }
        }
        lane.close();
    }
}