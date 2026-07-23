package com.visualspider.visualbrowser;

/**
 * 客户端经 WebSocket 发送的输入命令。携带会话 ID、单调序号、客户端显示尺寸，
 * 服务端按远程视口换算坐标并拒绝越界/过期命令。type 决定哪些字段有意义。
 * selector + selectorType 仅 type=validate 使用。
 *
 * <p>M2-2 #18：新增 {@code TYPE_SWITCH_MODE}（值 "switchMode"）表示切换浏览/选择模式，
 * mode 通过 {@code session.mode()} 字段传给 handler；服务端在内部 handler 链外单独
 * 维护会话模式状态，避免 record 增加 instance 字段。
 */
public record InputCommand(
        String sessionId,
        long sequence,
        int clientWidth,
        int clientHeight,
        String type,
        Integer x,
        Integer y,
        Integer deltaX,
        Integer deltaY,
        String key,
        String url,
        String selector,
        String selectorType
) {
    public enum Mode {
        BROWSE,
        SELECT
    }

    /** 兼容旧 11 参数构造（selector/selectorType 默认为 null）。 */
    public InputCommand(String sessionId, long sequence, int clientWidth, int clientHeight, String type,
                        Integer x, Integer y, Integer deltaX, Integer deltaY, String key, String url) {
        this(sessionId, sequence, clientWidth, clientHeight, type, x, y, deltaX, deltaY, key, url, null, null);
    }

    public static final String TYPE_CLICK = "click";
    public static final String TYPE_WHEEL = "wheel";
    public static final String TYPE_KEY = "key";
    public static final String TYPE_NAVIGATE = "navigate";
    public static final String TYPE_BACK = "back";
    public static final String TYPE_FORWARD = "forward";
    public static final String TYPE_RELOAD = "reload";
    public static final String TYPE_SELECT = "select";
    public static final String TYPE_VALIDATE_SELECTOR = "validate";
    public static final String TYPE_SWITCH_MODE = "switchMode";
}
