package com.visualspider.visualbrowser;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.websocket.ContainerProvider;
import jakarta.websocket.WebSocketContainer;
import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/**
 * {@link InboundGuard} 真实 WebSocket 集成测试（spec §D7 / T2）。
 *
 * <p>覆盖：
 * <ul>
 *   <li>文本 &gt; 8KB → 服务端断连（POLICY_VIOLATION）</li>
 *   <li>二进制消息 → 服务端断连</li>
 *   <li>21+ msg/s 持续 → 服务端断连</li>
 *   <li>正常小消息 → 服务端不断连</li>
 * </ul>
 *
 * <p>用 {@link TestWsConfig} 注册 {@code /ws/test-guard} 端点（{@link InboundGuard}
 * 包裹 echo handler），真实 WS 客户端连接验证。
 *
 * <p>M8-3：{@code @TestPropertySource(properties = "visualbrowser.target-url.allow-loopback=false")}
 * 显式覆盖 property，避免 {@link com.visualspider.shared.config.LoopbackStartupFailFastValidator}
 * 在无 {@code @ActiveProfiles("it")} 上下文下误触发 fail-fast。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {com.visualspider.Application.class, InboundGuardIT.TestWsConfig.class})
@TestPropertySource(properties = "visualbrowser.target-url.allow-loopback=false")
class InboundGuardIT {

    @LocalServerPort
    private int port;

    @BeforeEach
    void setUp() {
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        container.setDefaultMaxBinaryMessageBufferSize(1024 * 1024);
        container.setDefaultMaxTextMessageBufferSize(512 * 1024);
    }

    private WebSocketSession connect(AtomicReference<CloseStatus> closedStatus,
                                     CountDownLatch closedLatch) throws Exception {
        StandardWebSocketClient client = new StandardWebSocketClient();
        return client.doHandshake(new AbstractWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession session, TextMessage message) {
                // 不关心 echo 内容
            }

            @Override
            protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
                // 不关心
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
                closedStatus.set(status);
                closedLatch.countDown();
            }

            @Override
            public void handleTransportError(WebSocketSession session, Throwable exception) {
                closedStatus.set(CloseStatus.SERVER_ERROR);
                closedLatch.countDown();
            }
        }, null, URI.create("ws://localhost:" + port + "/ws/test-guard")).get(5, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("二进制消息 -> 服务端断连 (POLICY_VIOLATION)")
    void binaryRejected() throws Exception {
        AtomicReference<CloseStatus> ref = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        WebSocketSession s = connect(ref, latch);

        s.sendMessage(new BinaryMessage(new byte[]{1, 2, 3}));

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(ref.get()).isNotNull();
        assertThat(ref.get().getCode()).isEqualTo(CloseStatus.POLICY_VIOLATION.getCode());
    }

    @Test
    @DisplayName("文本 > 8KB -> 服务端断连")
    void oversizedRejected() throws Exception {
        AtomicReference<CloseStatus> ref = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        WebSocketSession s = connect(ref, latch);

        byte[] big = new byte[InboundGuard.MAX_TEXT_BYTES + 1];
        s.sendMessage(new TextMessage(new String(big)));

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(ref.get()).isNotNull();
        assertThat(ref.get().getCode()).isEqualTo(CloseStatus.POLICY_VIOLATION.getCode());
    }

    @Test
    @DisplayName("正常小消息 -> 服务端不断连")
    void normalPassesThrough() throws Exception {
        AtomicReference<CloseStatus> ref = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        WebSocketSession s = connect(ref, latch);

        s.sendMessage(new TextMessage("hello"));

        // 等 2s,确认没断连
        boolean closed = latch.await(2, TimeUnit.SECONDS);
        assertThat(closed).isFalse();
        assertThat(s.isOpen()).isTrue();
    }

    @Test
    @DisplayName("速率超限: 3 个连续 1s 桶各超限 -> 第 3 桶第 21 条触发断连")
    void rateLimitTriggersDisconnect() throws Exception {
        AtomicReference<CloseStatus> ref = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        WebSocketSession s = connect(ref, latch);

        // 桶 1: 21 条 (violations=1)
        for (int i = 0; i < 21; i++) {
            s.sendMessage(new TextMessage("a" + i));
        }
        Thread.sleep(1100);
        // 桶 2: 21 条 (violations=2)
        for (int i = 0; i < 21; i++) {
            s.sendMessage(new TextMessage("b" + i));
        }
        Thread.sleep(1100);
        // 桶 3: 21 条 -- 第 21 条触发 violations=3 -> 断连
        for (int i = 0; i < 21; i++) {
            s.sendMessage(new TextMessage("c" + i));
        }

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(ref.get()).isNotNull();
        assertThat(ref.get().getCode()).isEqualTo(CloseStatus.POLICY_VIOLATION.getCode());
    }

    /**
     * 测试专用 WS 配置：注册 {@code /ws/test-guard} 端点，用 {@link InboundGuard}
     * 包裹 echo handler。
     */
    @Configuration
    @EnableWebSocket
    static class TestWsConfig implements WebSocketConfigurer {

        @Bean
        public WebSocketHandler testGuardHandler() {
            return new InboundGuard(new AbstractWebSocketHandler() {
                @Override
                protected void handleTextMessage(WebSocketSession session, TextMessage message)
                        throws Exception {
                    session.sendMessage(new TextMessage("echo:" + message.getPayload()));
                }
            });
        }

        @Override
        public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
            registry.addHandler(testGuardHandler(), "/ws/test-guard").setAllowedOrigins();
        }

        @Bean
        public ServletServerContainerFactoryBean testWebSocketContainer() {
            ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
            container.setMaxBinaryMessageBufferSize(1024 * 1024);
            container.setMaxTextMessageBufferSize(512 * 1024);
            return container;
        }
    }
}
