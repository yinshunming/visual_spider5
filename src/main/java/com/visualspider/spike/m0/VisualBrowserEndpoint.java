package com.visualspider.spike.m0;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 远程浏览器 WebSocket 端点：每连接创建一个 {@link VisualSession}。
 *
 * <p>发送：二进制 JPEG 帧（从 FrameBuffer drain，客户端跟不上时丢旧）+ JSON 状态消息。
 * 接收：JSON 输入命令，按远程视口换算坐标、拒绝越界/过期。
 * M0 spike：不做握手认证/所有权（M2）。
 */
@Component
public class VisualBrowserEndpoint extends AbstractWebSocketHandler {
    private static final String SESSION_KEY = "visualSession";
    private static final long FRAME_POLL_INTERVAL_MS = 20;

    private final VisualSessionManager manager;
    private final ObjectMapper objectMapper;
    private final ExecutorService frameSenders = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "ws-frame-sender");
        t.setDaemon(true);
        return t;
    });

    public VisualBrowserEndpoint(VisualSessionManager manager, ObjectMapper objectMapper) {
        this.manager = manager;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession ws) {
        VisualSession session = manager.open(extractStartUrl(ws));
        ws.getAttributes().put(SESSION_KEY, session);
        sendStatus(ws, session);
        frameSenders.submit(() -> frameLoop(ws, session));
    }

    /** 帧发送循环：drain 最新帧发送；无帧时短暂等待。慢客户端使 sendMessage 阻塞，期间新帧覆盖旧帧（丢旧）。 */
    private void frameLoop(WebSocketSession ws, VisualSession session) {
        try {
            while (ws.isOpen()) {
                byte[] frame = session.drainFrame();
                if (frame != null && frame.length > 0) {
                    ws.sendMessage(new BinaryMessage(frame));
                } else {
                    Thread.sleep(FRAME_POLL_INTERVAL_MS);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
            // 连接关闭或发送异常：循环退出
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession ws, TextMessage message) {
        VisualSession session = (VisualSession) ws.getAttributes().get(SESSION_KEY);
        if (session == null) {
            return;
        }
        try {
            InputCommand cmd = objectMapper.readValue(message.getPayload(), InputCommand.class);
            boolean accepted = session.handle(cmd);
            if (accepted && session.isNavigation(cmd)) {
                sendStatus(ws, session);
            }
        } catch (Exception e) {
            sendError(ws, session, "invalid command: " + e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession ws, CloseStatus status) {
        VisualSession session = (VisualSession) ws.getAttributes().get(SESSION_KEY);
        if (session != null) {
            manager.close(session.sessionId());
        }
    }

    private void sendStatus(WebSocketSession ws, VisualSession session) {
        try {
            ws.sendMessage(new TextMessage(objectMapper.writeValueAsString(session.status())));
        } catch (Exception ignored) {
        }
    }

    private void sendError(WebSocketSession ws, VisualSession session, String message) {
        try {
            StatusMessage err = new StatusMessage(session.sessionId(), null, ViewportMapper.REMOTE_WIDTH,
                    ViewportMapper.REMOTE_HEIGHT, false, message);
            ws.sendMessage(new TextMessage(objectMapper.writeValueAsString(err)));
        } catch (Exception ignored) {
        }
    }

    private String extractStartUrl(WebSocketSession ws) {
        String query = ws.getUri() == null ? null : ws.getUri().getQuery();
        if (query != null) {
            for (String pair : query.split("&")) {
                if (pair.startsWith("url=")) {
                    return URLDecoder.decode(pair.substring(4), StandardCharsets.UTF_8);
                }
            }
        }
        return "about:blank";
    }
}
