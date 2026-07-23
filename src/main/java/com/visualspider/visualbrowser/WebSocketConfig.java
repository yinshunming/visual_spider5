package com.visualspider.visualbrowser;

import com.visualspider.visualbrowser.api.VisualSessionWebSocketHandler;
import com.visualspider.visualbrowser.internal.SessionOwnerHandshakeInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/**
 * 注册远程浏览器 WebSocket 端点 {@code /ws/visual-sessions/{sessionId}}。
 *
 * <p>M2-1 #17 收紧了握手校验：JSESSIONID 已登录、query csrf token 与 cookie 一致、Origin
 * 同源、session owner 等于 actor。
 *
 * <p>旧 {@code /ws/visual} 端点（M0 spike）保留用于保持现有 spike IT（{@link VisualBrowserIT}）
 * 通过真实 Chromium 流程继续验证；后续 issue 移除。
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final VisualSessionWebSocketHandler handler;
    private final VisualBrowserEndpoint legacyEndpoint;
    private final SessionOwnerHandshakeInterceptor ownerInterceptor;

    public WebSocketConfig(VisualSessionWebSocketHandler handler,
                           VisualBrowserEndpoint legacyEndpoint,
                           SessionOwnerHandshakeInterceptor ownerInterceptor) {
        this.handler = handler;
        this.legacyEndpoint = legacyEndpoint;
        this.ownerInterceptor = ownerInterceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/visual-sessions/{sessionId}")
                .setAllowedOrigins()
                .addInterceptors(ownerInterceptor);

        registry.addHandler(legacyEndpoint, "/ws/visual").setAllowedOrigins();
    }

    /** 增大 WebSocket 消息缓冲，容纳 JPEG 帧（Tomcat 默认 8KB 不足）。 */
    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxBinaryMessageBufferSize(1024 * 1024);
        container.setMaxTextMessageBufferSize(512 * 1024);
        return container;
    }
}
