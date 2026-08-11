package com.visualspider.visualbrowser;

import com.visualspider.extraction.spi.ExtractionPreview;
import com.visualspider.extraction.spi.PreviewResult;
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
     * 在当前 lane/Page 上对 definition 执行预览（M2-3 #19 / M3 spec §D7）。
     *
     * <p>在 lane 线程内直接查询 DOM 构造 {@link ExtractionPreview.DomState}，再委托
     * {@link ExtractionPreview}（与 M3 运行共用同一实现）。不写库。
     *
     * <p>M3 扩展：实现 {@link ExtractionPreview.DomState#query(String, com.visualspider.task.domain.SelectorType)}
     * 按类型分发（CSS → {@code document.querySelectorAll}，XPath → {@code document.evaluate}）。
     * 与 {@code PlaywrightControl.validateSelector} 使用同一段 JS 模式（仅做节点摘要拉取）。
     */
    @SuppressWarnings("unchecked")
    public PreviewResult preview(TaskDefinition definition, ExtractionPreview extraction) {
        return lane.submit(() -> {
            ExtractionPreview.DomState dom = new ExtractionPreview.DomState() {
                @Override
                public String url() {
                    return lane.page().url();
                }

                @Override
                public List<ExtractionPreview.Node> query(String selector,
                                                          com.visualspider.task.domain.SelectorType type) {
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
                            + "    // CSS 语法错误 → M2 SELECTOR_SYNTAX_INVALID 路径（M3 spec §D7 仍允许 CSS 字段异常）"
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
                                    ? com.visualspider.task.domain.SelectorType.CSS.name().toLowerCase()
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
                    return nodes;
                }
            };
            return extraction.preview(definition, dom);
        }).join();
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

    /**
     * 在当前 lane/Page 上按远程视口坐标采集 {@link com.visualspider.extraction.spi.DomSnapshot}
     * （M4-2 #32 / spec §D3）。与 {@link #preview} 同一线程模型：在 lane 线程上 evaluate，
     * 主线程阻塞等待；不暴露 {@link java.util.concurrent.CompletableFuture} 给 Web 线程
     * （architecture §4.1）。
     *
     * @param remoteX 远程视口 CSS 像素 x（1280×720），由 {@link ViewportMapper#toRemote} 换算
     * @param remoteY 远程视口 CSS 像素 y
     * @throws IllegalArgumentException 当坐标处无 DOM 元素（{@code elementFromPoint} 返 null）
     */
    public com.visualspider.extraction.spi.DomSnapshot captureDomSnapshot(int remoteX, int remoteY) {
        return control.captureDomSnapshot(remoteX, remoteY).join();
    }
}
