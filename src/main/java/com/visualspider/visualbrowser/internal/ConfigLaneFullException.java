package com.visualspider.visualbrowser.internal;

/** 配置 lane 池借满（默认 3 lane 全部被占用）。 */
public final class ConfigLaneFullException extends RuntimeException {
    public ConfigLaneFullException() {
        super("配置 lane 已占满（最多 3 个并发会话），请稍候重试");
    }
}
