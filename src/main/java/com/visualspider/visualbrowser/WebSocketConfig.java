package com.visualspider.visualbrowser;

import com.visualspider.run.internal.RunOwnerHandshakeInterceptor;
import com.visualspider.run.internal.RunProgressWebSocketHandler;
import com.visualspider.visualbrowser.api.VisualSessionWebSocketHandler;
import com.visualspider.visualbrowser.internal.SessionOwnerHandshakeInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/**
 * 注册远程浏览器 / 运行进度 WebSocket 端点。
 *
 * <ul>
 *   <li>{@code /ws/visual-sessions/{sessionId}}：远程浏览器；M2-1 #17 收紧了握手校验。</li>
 *   <li>{@code /ws/runs/{runId}}：运行进度推送；M3-5 #27 — admin 可访问任意 run；
 *       服务端推 PROGRESS / EVENT / TERMINAL，客户端只发 CANCEL。</li>
 * </ul>
 *
 * <p>旧 {@code /ws/visual} 端点（M0 spike）保留用于保持现有 spike IT 通过；后续 issue 移除。
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final VisualSessionWebSocketHandler handler;
    private final RunProgressWebSocketHandler runProgressHandler;
    private final VisualBrowserEndpoint legacyEndpoint;
    private final SessionOwnerHandshakeInterceptor ownerInterceptor;
    private final RunOwnerHandshakeInterceptor runOwnerInterceptor;

    public WebSocketConfig(VisualSessionWebSocketHandler handler,
                           RunProgressWebSocketHandler runProgressHandler,
                           VisualBrowserEndpoint legacyEndpoint,
                           SessionOwnerHandshakeInterceptor ownerInterceptor,
                           RunOwnerHandshakeInterceptor runOwnerInterceptor) {
        this.handler = handler;
        this.runProgressHandler = runProgressHandler;
        this.legacyEndpoint = legacyEndpoint;
        this.ownerInterceptor = ownerInterceptor;
        this.runOwnerInterceptor = runOwnerInterceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/visual-sessions/{sessionId}")
                .setAllowedOrigins()
                .addInterceptors(ownerInterceptor);

        registry.addHandler(runProgressHandler, "/ws/runs/{runId}")
                .setAllowedOrigins()
                .addInterceptors(runOwnerInterceptor);

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
