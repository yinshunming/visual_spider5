package com.visualspider.spike.m0;

/**
 * 服务端经 WebSocket 发送的 JSON 状态消息：会话 ID、当前 URL、远程视口、加载态、错误。
 * 选择结果（selection）留到 M0-3 浏览/选择模式。
 */
public record StatusMessage(
        String sessionId,
        String url,
        int viewportWidth,
        int viewportHeight,
        boolean loading,
        String error
) {}
