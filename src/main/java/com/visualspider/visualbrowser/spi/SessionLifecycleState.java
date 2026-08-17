package com.visualspider.visualbrowser.spi;

/**
 * 配置会话生命周期状态（M2-1 #17）。
 *
 * <p>会话状态机：
 * <pre>
 *   ACTIVE --idle 15min--> IDLE_CLOSING --closed--> CLOSED
 *   ACTIVE --max 2h--> MAX_REACHED_CLOSING --closed--> CLOSED
 *   ACTIVE --DELETE /api--> USER_CLOSING --closed--> CLOSED
 * </pre>
 */
public enum SessionLifecycleState {
    ACTIVE,
    IDLE_CLOSING,
    MAX_REACHED_CLOSING,
    USER_CLOSING,
    CLOSED
}
