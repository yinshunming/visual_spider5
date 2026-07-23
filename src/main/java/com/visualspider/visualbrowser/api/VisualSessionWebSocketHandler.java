package com.visualspider.visualbrowser.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.visualspider.identity.domain.ActorId;
import com.visualspider.identity.spi.IdentityAccess;
import com.visualspider.visualbrowser.InputCommand;
import com.visualspider.visualbrowser.internal.FrameSenderExecutor;
import com.visualspider.visualbrowser.internal.SessionOwnerHandshakeInterceptor;
import com.visualspider.visualbrowser.spi.VisualSession;
import com.visualspider.visualbrowser.spi.VisualSessionManager;
import java.io.IOException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

/**
 * 远程浏览器 WS 处理器（M2-1 #17）。与 {@link SessionOwnerHandshakeInterceptor} 配合，
 * 每次连接复用 {@link VisualSession} 帧通道，并由 {@link FrameSenderExecutor} 守护线程池。
 */
@Component
public class VisualSessionWebSocketHandler extends AbstractWebSocketHandler {

    private static final Logger LOG = LoggerFactory.getLogger(VisualSessionWebSocketHandler.class);
    private static final String ATTR_LEGACY = "visual.legacy";

    private final VisualSessionManager manager;
    private final IdentityAccess identityAccess;
    private final ObjectMapper objectMapper;
    private final LegacySessionFactory legacyFactory;
    private final FrameSenderExecutor frameSenders;

    public VisualSessionWebSocketHandler(VisualSessionManager manager, IdentityAccess identityAccess,
                                         ObjectMapper objectMapper, LegacySessionFactory legacyFactory,
                                         FrameSenderExecutor frameSenders) {
        this.manager = manager;
        this.identityAccess = identityAccess;
        this.objectMapper = objectMapper;
        this.legacyFactory = legacyFactory;
        this.frameSenders = frameSenders;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession ws) {
        Map<String, Object> attrs = ws.getAttributes();
        ActorId actor = (ActorId) attrs.get(SessionOwnerHandshakeInterceptor.ATTR_ACTOR);
        String sessionId = (String) attrs.get(SessionOwnerHandshakeInterceptor.ATTR_SESSION_ID);
        if (actor == null || sessionId == null) {
            closeQuietly(ws, CloseStatus.POLICY_VIOLATION);
            return;
        }
        try {
            var legacy = legacyFactory.create(actor, sessionId);
            attrs.put(ATTR_LEGACY, legacy);
            frameSenders.submit(() -> frameLoop(ws, legacy));
            LOG.info("ws connected: sessionId={} actor={}", sessionId, actor.value());
        } catch (RuntimeException ex) {
            LOG.warn("failed to bind session", ex);
            closeQuietly(ws, CloseStatus.SERVER_ERROR);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession ws, TextMessage message) throws Exception {
        var attrs = ws.getAttributes();
        ActorId actor = (ActorId) attrs.get(SessionOwnerHandshakeInterceptor.ATTR_ACTOR);
        String sessionId = (String) attrs.get(SessionOwnerHandshakeInterceptor.ATTR_SESSION_ID);
        if (actor == null || sessionId == null) {
            closeQuietly(ws, CloseStatus.POLICY_VIOLATION);
            return;
        }
        InputCommand command;
        try {
            command = objectMapper.readValue(message.getPayload(), InputCommand.class);
        } catch (IOException ex) {
            sendError(ws, "invalid command format");
            return;
        }
        // 重新校验 + 复用 channel 风格校验
        var owned = manager.requireOwnedBy(sessionId, actor);
        if (!sessionId.equals(command.sessionId())) {
            sendError(ws, "sessionId mismatch");
            return;
        }
        if (owned.lifecycle() == com.visualspider.visualbrowser.spi.SessionLifecycleState.CLOSED) {
            closeQuietly(ws, CloseStatus.POLICY_VIOLATION);
            return;
        }
        var legacy = (com.visualspider.visualbrowser.VisualSession) attrs.get(ATTR_LEGACY);
        if (legacy == null) {
            sendError(ws, "session not ready");
            return;
        }
        boolean accepted;
        try {
            accepted = legacy.handle(command);
        } catch (RuntimeException ex) {
            LOG.warn("command execution failed", ex);
            return;
        }
        if (accepted) {
            manager.heartbeat(sessionId, actor);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession ws, CloseStatus status) {
        Map<String, Object> attrs = ws.getAttributes();
        ActorId actor = (ActorId) attrs.get(SessionOwnerHandshakeInterceptor.ATTR_ACTOR);
        String sessionId = (String) attrs.get(SessionOwnerHandshakeInterceptor.ATTR_SESSION_ID);
        if (sessionId != null && actor != null) {
            // 仅在客户端断开时尝试关闭；本处理器不再强制释放 session；
            // session 可能由另一个 tab 复用，由 manager 自身生命周期负责。
            LOG.debug("ws closed: sessionId={} status={}", sessionId, status);
        }
        Object legacy = attrs.remove(ATTR_LEGACY);
        if (legacy instanceof com.visualspider.visualbrowser.VisualSession vs) {
            try {
                vs.drainFrame();
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void frameLoop(WebSocketSession ws, com.visualspider.visualbrowser.VisualSession legacy) {
        long lastActivity = System.currentTimeMillis();
        try {
            while (ws.isOpen()) {
                byte[] frame = legacy.drainFrame();
                if (frame != null && frame.length > 0) {
                    ws.sendMessage(new org.springframework.web.socket.BinaryMessage(frame));
                    lastActivity = System.currentTimeMillis();
                } else {
                    if (System.currentTimeMillis() - lastActivity > 1000) {
                        Thread.sleep(20);
                    } else {
                        Thread.sleep(5);
                    }
                }
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
            // 连接关闭：循环退出
        }
    }

    private void sendError(WebSocketSession ws, String reason) {
        try {
            ws.sendMessage(new TextMessage("{\"error\":\"" + reason + "\"}"));
        } catch (IOException ignored) {
        }
    }

    private static void closeQuietly(WebSocketSession ws, CloseStatus status) {
        try {
            ws.close(status);
        } catch (IOException ignored) {
        }
    }
}
