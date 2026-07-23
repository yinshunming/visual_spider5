package com.visualspider.visualbrowser.internal;

/** 配置会话不存在。 */
public final class VisualSessionNotFoundException extends RuntimeException {
    public VisualSessionNotFoundException(String sessionId) {
        super("配置会话不存在: " + sessionId);
    }
}
