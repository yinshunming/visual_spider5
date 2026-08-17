package com.visualspider.visualbrowser.internal;

/** 调用者不是配置会话的所有者。 */
public final class VisualSessionNotOwnerException extends RuntimeException {
    public VisualSessionNotOwnerException(String sessionId) {
        super("无权访问配置会话: " + sessionId);
    }
}
