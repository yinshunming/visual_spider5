package com.visualspider.spike.m0;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.WebSocketContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 Chromium + WebSocket 集成测试：验证 screenshot 帧流、状态消息、导航与越界拒绝端到端。
 *
 * <p>需要 Chromium 已安装。每个 WS 连接创建一个独立 VisualSession/BrowserContext。
 * 坐标换算/序号/丢旧的纯逻辑由 ViewportMapper/InputSequencer/FrameBuffer 单测覆盖。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class VisualBrowserIT {
    private static final long TIMEOUT_SECONDS = 40;

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    private WebSocketSession clientSession;

    @AfterEach
    void tearDown() throws Exception {
        if (clientSession != null && clientSession.isOpen()) {
            clientSession.close();
        }
    }

    @Test
    void streamsFramesStatusAndNavigate() throws Exception {
        String fixtureUrl = getClass().getResource("/fixtures/dynamic.html").toURI().toString();
        String wsUrl = "ws://localhost:" + port + "/ws/visual?url="
                + URLEncoder.encode(fixtureUrl, StandardCharsets.UTF_8);

        CountDownLatch frameLatch = new CountDownLatch(1);
        CountDownLatch firstStatusLatch = new CountDownLatch(1);
        AtomicReference<String> latestStatus = new AtomicReference<>();

        // 显式配置客户端 WebSocket 容器缓冲，容纳 JPEG 帧（默认 8KB 不足）
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        container.setDefaultMaxBinaryMessageBufferSize(1024 * 1024);
        container.setDefaultMaxTextMessageBufferSize(512 * 1024);
        StandardWebSocketClient client = new StandardWebSocketClient(container);

        clientSession = client.doHandshake(new AbstractWebSocketHandler() {
            @Override
            public void handleTextMessage(WebSocketSession session, TextMessage message) {
                latestStatus.set(message.getPayload());
                firstStatusLatch.countDown();
            }

            @Override
            protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
                frameLatch.countDown();
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            }
        }, wsUrl).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertThat(frameLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).as("应收到至少一帧").isTrue();
        assertThat(firstStatusLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).as("应收到状态消息").isTrue();

        StatusMessage status = objectMapper.readValue(latestStatus.get(), StatusMessage.class);
        assertThat(status.sessionId()).isNotNull();
        assertThat(status.url()).startsWith("file:");
        assertThat(status.viewportWidth()).isEqualTo(1280);

        // 越界 click 应被拒绝（不崩溃连接）：x 超出 clientWidth
        String outOfBounds = objectMapper.writeValueAsString(new InputCommand(
                status.sessionId(), 1, 1000, 1000, InputCommand.TYPE_CLICK,
                9999, 10, null, null, null, null));
        clientSession.sendMessage(new TextMessage(outOfBounds));

        // navigate about:blank 应更新状态 URL（序号 2，在越界命令 1 之后）
        String nav = objectMapper.writeValueAsString(new InputCommand(
                status.sessionId(), 2, 1280, 720, InputCommand.TYPE_NAVIGATE,
                null, null, null, null, null, "about:blank"));
        clientSession.sendMessage(new TextMessage(nav));

        assertThat(waitForUrl(latestStatus, "about:blank"))
                .as("导航后状态 URL 应更新为 about:blank").isTrue();
    }

    private boolean waitForUrl(AtomicReference<String> latestStatus, String urlFragment) throws Exception {
        long deadline = System.currentTimeMillis() + TIMEOUT_SECONDS * 1000;
        while (System.currentTimeMillis() < deadline) {
            String raw = latestStatus.get();
            if (raw != null) {
                StatusMessage s = objectMapper.readValue(raw, StatusMessage.class);
                if (s.url() != null && s.url().contains(urlFragment)) {
                    return true;
                }
            }
            Thread.sleep(200);
        }
        return false;
    }
}
