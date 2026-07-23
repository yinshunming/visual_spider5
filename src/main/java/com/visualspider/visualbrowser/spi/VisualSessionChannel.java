package com.visualspider.visualbrowser.spi;

import com.visualspider.identity.domain.ActorId;

/**
 * 配置会话输入通道 SPI（M2-1 #17）。
 *
 * <p>{@link #handleCommand(String, com.visualspider.visualbrowser.InputCommand, ActorId)}
 * 由 WebSocket handler 在每条消息入口调用；实现层校验：
 * <ul>
 *   <li>sessionId 存在且属于 actor</li>
 *   <li>taskId 与 session 绑定一致（不允许越权切换）</li>
 *   <li>会话未处于 CLOSING / CLOSED</li>
 * </ul>
 *
 * <p>实现层调用 {@code Lease} 绑定的 lane 执行实际 Playwright 调用；
 * 坐标/序号合法性由底层 {@code PlaywrightControl} / {@code ViewportMapper}
 * / {@code InputSequencer} 保证。
 */
public interface VisualSessionChannel {

    void handleCommand(String sessionId, com.visualspider.visualbrowser.InputCommand command, ActorId actor);
}
