package com.visualspider.spike.m0;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/**
 * 注册远程浏览器 WebSocket 端点 /ws/visual。
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    private final VisualBrowserEndpoint endpoint;

    public WebSocketConfig(VisualBrowserEndpoint endpoint) {
        this.endpoint = endpoint;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(endpoint, "/ws/visual").setAllowedOrigins("*");
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
