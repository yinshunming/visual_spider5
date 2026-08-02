package com.visualspider.run.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.visualspider.identity.domain.ActorId;
import com.visualspider.run.spi.RunCoordinator;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

/**
 * 运行进度 WebSocket 处理器（M3-5 #27 / spec §D16 / D17）。
 *
 * <p>握手校验由 {@link RunOwnerHandshakeInterceptor} 在升级前完成（admin 可访问任意 run；
 * 与配置会话策略相反）；本 handler 仅处理已通过握手的会话：
 * <ul>
 *   <li>连接建立 -&gt; 委托 {@link RunProgressBroadcaster#subscribe(long, WebSocketSession, ActorId)}
 *       启动订阅（先发 {@code PROGRESS} + 已写入事件，再守护线程轮询增量）</li>
 *   <li>收到客户端消息：仅接受 {@code {schemaVersion:1,type:CANCEL}}；
 *       重校所有权后调用 {@link RunCoordinator#cancel}；服务端 close</li>
 *   <li>连接关闭 -&gt; 停订阅</li>
 * </ul>
 */
@Component
public class RunProgressWebSocketHandler extends AbstractWebSocketHandler {

    private static final Logger LOG = LoggerFactory.getLogger(RunProgressWebSocketHandler.class);

    private final RunProgressBroadcaster broadcaster;
    private final RunCoordinator runCoordinator;
    private final ObjectMapper objectMapper;

    public RunProgressWebSocketHandler(RunProgressBroadcaster broadcaster,
                                       RunCoordinator runCoordinator,
                                       ObjectMapper objectMapper) {
        this.broadcaster = broadcaster;
        this.runCoordinator = runCoordinator;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession ws) {
        Map<String, Object> attrs = ws.getAttributes();
        ActorId actor = (ActorId) attrs.get(RunOwnerHandshakeInterceptor.ATTR_ACTOR);
        Long runIdObj = (Long) attrs.get(RunOwnerHandshakeInterceptor.ATTR_RUN_ID);
        if (actor == null || runIdObj == null) {
            closeQuietly(ws, CloseStatus.POLICY_VIOLATION);
            return;
        }
        long runId = runIdObj;
        try {
            RunProgressBroadcaster.Subscription sub = broadcaster.subscribe(runId, ws, actor);
            attrs.put(ATTR_SUB, sub);
            LOG.info("ws run progress connected: runId={} actor={}", runId, actor.value());
        } catch (IOException ex) {
            LOG.warn("ws run progress subscribe failed runId={}: {}", runId, ex.getMessage());
            closeQuietly(ws, CloseStatus.SERVER_ERROR);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession ws, TextMessage message) throws Exception {
        Map<String, Object> attrs = ws.getAttributes();
        ActorId actor = (ActorId) attrs.get(RunOwnerHandshakeInterceptor.ATTR_ACTOR);
        Long runIdObj = (Long) attrs.get(RunOwnerHandshakeInterceptor.ATTR_RUN_ID);
        if (actor == null || runIdObj == null) {
            closeQuietly(ws, CloseStatus.POLICY_VIOLATION);
            return;
        }
        long runId = runIdObj;

        RunProgressBroadcaster.CancelMessage parsed;
        try {
            parsed = broadcaster.parseClientMessage(message.getPayload());
        } catch (IOException ex) {
            LOG.warn("invalid ws run payload runId={}: {}", runId, ex.getMessage());
            closeQuietly(ws, CloseStatus.POLICY_VIOLATION);
            return;
        }
        if (parsed == null) {
            // 非法或非 CANCEL 类型：拒，不转发任何调用方逻辑
            closeQuietly(ws, CloseStatus.POLICY_VIOLATION);
            return;
        }

        // CANCEL：重校所有权，等价 POST cancel
        if (!broadcaster.canCancel(runId, actor)) {
            closeQuietly(ws, CloseStatus.POLICY_VIOLATION);
            return;
        }
        try {
            runCoordinator.cancel(runId, actor);
        } catch (RuntimeException ex) {
            // 终态运行 -> RunNotCancellableException 等：忽略（TERMINAL 推送会发），仍让订阅正常关闭
            LOG.debug("ws CANCEL ignored runId={}: {}", runId, ex.getClass().getSimpleName());
        }
        // CANCEL 已发出：让 broadcaster 的下一次 tick 检测到 terminal 时自动 close；这里不强关。
    }

    @Override
    public void afterConnectionClosed(WebSocketSession ws, CloseStatus status) {
        RunProgressBroadcaster.Subscription sub = (RunProgressBroadcaster.Subscription)
                ws.getAttributes().get(ATTR_SUB);
        broadcaster.onClientDisconnect(sub);
        Map<String, Object> attrs = ws.getAttributes();
        Long runIdObj = (Long) attrs.get(RunOwnerHandshakeInterceptor.ATTR_RUN_ID);
        if (runIdObj != null) {
            LOG.debug("ws run progress closed: runId={} status={}", runIdObj, status);
        }
        attrs.remove(ATTR_SUB);
    }

    @Override
    public void handleTransportError(WebSocketSession ws, Throwable exception) {
        LOG.warn("ws run progress transport error runId={}: {}",
                ws.getAttributes().get(RunOwnerHandshakeInterceptor.ATTR_RUN_ID),
                exception == null ? "null" : exception.getMessage());
        // 客户端断开 / 网络错：停订阅
        RunProgressBroadcaster.Subscription sub = (RunProgressBroadcaster.Subscription)
                ws.getAttributes().get(ATTR_SUB);
        broadcaster.onClientDisconnect(sub);
    }

    private static final String ATTR_SUB = "run.subscription";

    private static void closeQuietly(WebSocketSession ws, CloseStatus status) {
        try {
            ws.close(status);
        } catch (IOException ignored) {
        }
    }

    /** 测试辅助：检查错误响应。保留以备 WS IT 用。 */
    @SuppressWarnings("unused")
    private Map<String, Object> errorPayload(String reason) {
        Map<String, Object> m = new HashMap<>();
        m.put("schemaVersion", RunProgressBroadcaster.SCHEMA_VERSION);
        m.put("type", "ERROR");
        m.put("error", reason);
        return m;
    }

    /** 测试辅助：取 objectMapper（仅供类自身诊断使用）。 */
    @SuppressWarnings("unused")
    ObjectMapper objectMapper() {
        return objectMapper;
    }
}
