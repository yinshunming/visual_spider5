package com.visualspider.spike.m0;

/**
 * 客户端经 WebSocket 发送的输入命令。携带会话 ID、单调序号和客户端显示尺寸，
 * 服务端按远程视口换算坐标并拒绝越界/过期命令。type 决定哪些字段有意义。
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
        String url
) {
    public static final String TYPE_CLICK = "click";
    public static final String TYPE_WHEEL = "wheel";
    public static final String TYPE_KEY = "key";
    public static final String TYPE_NAVIGATE = "navigate";
    public static final String TYPE_BACK = "back";
    public static final String TYPE_FORWARD = "forward";
    public static final String TYPE_RELOAD = "reload";
}
