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
     * 在当前 lane/Page 上对 definition 执行预览（M2-3 #19）。
     *
     * <p>在 lane 线程内直接查询 DOM 构造 {@link ExtractionPreview.DomState}，再委托
     * {@link ExtractionPreview}（与 M3 运行共用同一实现）。不写库。
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
                public List<ExtractionPreview.Node> querySelectorAll(String selector) {
                    String js = "(sel) => {"
                            + "  const nodes = Array.from(document.querySelectorAll(sel));"
                            + "  return nodes.map(el => {"
                            + "    const attrs = {};"
                            + "    for (const a of el.attributes) attrs[a.name] = a.value;"
                            + "    return { tagName: el.tagName, id: el.id || '', className: el.className || '',"
                            + "      textContent: (el.textContent || '').substring(0, 500), attributes: attrs };"
                            + "  });"
                            + "}";
                    List<Map<String, Object>> maps = (List<Map<String, Object>>) lane.page().evaluate(js, selector);
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
}
