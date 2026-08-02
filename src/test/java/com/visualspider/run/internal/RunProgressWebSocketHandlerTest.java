package com.visualspider.run.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.visualspider.identity.domain.ActorId;
import com.visualspider.identity.spi.IdentityAccess;
import com.visualspider.run.spi.RunCoordinator;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * {@link RunProgressWebSocketHandler} 单元测试（#27 / spec §D16）。
 *
 * <p>覆盖：
 * <ul>
 *   <li>握手 attrs 不全时拒（POLICY_VIOLATION）</li>
 *   <li>收到合法 CANCEL -&gt; 调 coordinator.cancel + 关闭订阅</li>
 *   <li>收到非法帧 -&gt; 拒 + POLICY_VIOLATION</li>
 *   <li>非 owner CANCEL -&gt; 拒 + 不调 coordinator.cancel</li>
 * </ul>
 */
class RunProgressWebSocketHandlerTest {

    private RunProgressBroadcaster broadcaster;
    private RunCoordinator coordinator;
    private ObjectMapper objectMapper;
    private RunProgressWebSocketHandler handler;
    private RunRepository repository;
    private IdentityAccess identityAccess;

    @BeforeEach
    void setUp() {
        broadcaster = mock(RunProgressBroadcaster.class);
        coordinator = mock(RunCoordinator.class);
        repository = mock(RunRepository.class);
        identityAccess = mock(IdentityAccess.class);
        objectMapper = new ObjectMapper();
        handler = new RunProgressWebSocketHandler(broadcaster, coordinator, objectMapper);
    }

    private WebSocketSession wsWithAttrs(Map<String, Object> attrs) {
        WebSocketSession ws = mock(WebSocketSession.class);
        when(ws.getAttributes()).thenReturn(attrs);
        when(ws.isOpen()).thenReturn(true);
        return ws;
    }

    @Test
    void connectionWithMissingAttrsIsClosed() throws IOException {
        Map<String, Object> attrs = new HashMap<>();
        WebSocketSession ws = wsWithAttrs(attrs);

        handler.afterConnectionEstablished(ws);

        verify(ws, times(1)).close(eq(CloseStatus.POLICY_VIOLATION));
    }

    @Test
    void connectionWithValidAttrsSubscribes() throws IOException {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put(RunOwnerHandshakeInterceptor.ATTR_ACTOR, new ActorId(1L));
        attrs.put(RunOwnerHandshakeInterceptor.ATTR_RUN_ID, 42L);
        WebSocketSession ws = wsWithAttrs(attrs);

        RunProgressBroadcaster.Subscription sub = mock(RunProgressBroadcaster.Subscription.class);
        when(broadcaster.subscribe(eq(42L), eq(ws), any(ActorId.class))).thenReturn(sub);

        handler.afterConnectionEstablished(ws);

        verify(broadcaster, times(1)).subscribe(eq(42L), eq(ws), any(ActorId.class));
        verify(ws, never()).close(any(CloseStatus.class));
    }

    @Test
    void subscribeFailureClosesWs() throws IOException {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put(RunOwnerHandshakeInterceptor.ATTR_ACTOR, new ActorId(1L));
        attrs.put(RunOwnerHandshakeInterceptor.ATTR_RUN_ID, 42L);
        WebSocketSession ws = wsWithAttrs(attrs);

        when(broadcaster.subscribe(eq(42L), eq(ws), any(ActorId.class)))
                .thenThrow(new IOException("send fail"));

        handler.afterConnectionEstablished(ws);

        verify(ws, times(1)).close(eq(CloseStatus.SERVER_ERROR));
    }

    @Test
    void cancelMessageTriggersCoordinatorCancel() throws Exception {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put(RunOwnerHandshakeInterceptor.ATTR_ACTOR, new ActorId(1L));
        attrs.put(RunOwnerHandshakeInterceptor.ATTR_RUN_ID, 42L);
        WebSocketSession ws = wsWithAttrs(attrs);

        RunProgressBroadcaster.CancelMessage parsed =
                new RunProgressBroadcaster.CancelMessage(1, "CANCEL");
        when(broadcaster.parseClientMessage(any(String.class))).thenReturn(parsed);
        when(broadcaster.canCancel(eq(42L), any(ActorId.class))).thenReturn(true);

        handler.handleTextMessage(ws, new TextMessage(
                "{\"schemaVersion\":1,\"type\":\"CANCEL\"}"));

        verify(coordinator, times(1)).cancel(eq(42L), any(ActorId.class));
        verify(ws, never()).close(any(CloseStatus.class));
    }

    @Test
    void cancelMessageRejectedForNonOwner() throws Exception {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put(RunOwnerHandshakeInterceptor.ATTR_ACTOR, new ActorId(2L));
        attrs.put(RunOwnerHandshakeInterceptor.ATTR_RUN_ID, 42L);
        WebSocketSession ws = wsWithAttrs(attrs);

        RunProgressBroadcaster.CancelMessage parsed =
                new RunProgressBroadcaster.CancelMessage(1, "CANCEL");
        when(broadcaster.parseClientMessage(any(String.class))).thenReturn(parsed);
        when(broadcaster.canCancel(eq(42L), any(ActorId.class))).thenReturn(false);

        handler.handleTextMessage(ws, new TextMessage(
                "{\"schemaVersion\":1,\"type\":\"CANCEL\"}"));

        verify(coordinator, never()).cancel(anyLong(), any(ActorId.class));
        verify(ws, times(1)).close(eq(CloseStatus.POLICY_VIOLATION));
    }

    @Test
    void unknownFrameTypeClosesWs() throws Exception {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put(RunOwnerHandshakeInterceptor.ATTR_ACTOR, new ActorId(1L));
        attrs.put(RunOwnerHandshakeInterceptor.ATTR_RUN_ID, 42L);
        WebSocketSession ws = wsWithAttrs(attrs);

        when(broadcaster.parseClientMessage(any(String.class))).thenReturn(null);

        handler.handleTextMessage(ws, new TextMessage(
                "{\"schemaVersion\":1,\"type\":\"OTHER\"}"));

        verify(ws, times(1)).close(eq(CloseStatus.POLICY_VIOLATION));
        verify(coordinator, never()).cancel(anyLong(), any(ActorId.class));
    }

    @Test
    void invalidJsonClosesWs() throws Exception {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put(RunOwnerHandshakeInterceptor.ATTR_ACTOR, new ActorId(1L));
        attrs.put(RunOwnerHandshakeInterceptor.ATTR_RUN_ID, 42L);
        WebSocketSession ws = wsWithAttrs(attrs);

        when(broadcaster.parseClientMessage(any(String.class)))
                .thenThrow(new IOException("parse fail"));

        handler.handleTextMessage(ws, new TextMessage("not json"));

        verify(ws, times(1)).close(eq(CloseStatus.POLICY_VIOLATION));
    }

    @Test
    void handleTextMessageMissingRunIdClosesWs() throws Exception {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put(RunOwnerHandshakeInterceptor.ATTR_ACTOR, new ActorId(1L));
        // runId 缺失
        WebSocketSession ws = wsWithAttrs(attrs);

        handler.handleTextMessage(ws, new TextMessage(
                "{\"schemaVersion\":1,\"type\":\"CANCEL\"}"));

        verify(ws, times(1)).close(eq(CloseStatus.POLICY_VIOLATION));
    }

    @Test
    void connectionClosedCancelsSubscription() {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put(RunOwnerHandshakeInterceptor.ATTR_ACTOR, new ActorId(1L));
        attrs.put(RunOwnerHandshakeInterceptor.ATTR_RUN_ID, 42L);
        RunProgressBroadcaster.Subscription sub = mock(RunProgressBroadcaster.Subscription.class);
        attrs.put("run.subscription", sub);
        WebSocketSession ws = wsWithAttrs(attrs);

        handler.afterConnectionClosed(ws, CloseStatus.NORMAL);

        verify(broadcaster, times(1)).onClientDisconnect(sub);
    }

    @Test
    void runCoordinatorTerminalExceptionSwallowed() throws Exception {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put(RunOwnerHandshakeInterceptor.ATTR_ACTOR, new ActorId(1L));
        attrs.put(RunOwnerHandshakeInterceptor.ATTR_RUN_ID, 42L);
        WebSocketSession ws = wsWithAttrs(attrs);

        RunProgressBroadcaster.CancelMessage parsed =
                new RunProgressBroadcaster.CancelMessage(1, "CANCEL");
        when(broadcaster.parseClientMessage(any(String.class))).thenReturn(parsed);
        when(broadcaster.canCancel(eq(42L), any(ActorId.class))).thenReturn(true);

        org.mockito.Mockito.doThrow(new RuntimeException(
                new com.visualspider.run.internal.RunNotCancellableException(42L,
                        com.visualspider.run.spi.RunState.SUCCESS)))
                .when(coordinator).cancel(anyLong(), any(ActorId.class));

        // 不应抛
        handler.handleTextMessage(ws, new TextMessage(
                "{\"schemaVersion\":1,\"type\":\"CANCEL\"}"));

        verify(coordinator, times(1)).cancel(anyLong(), any(ActorId.class));
    }
}
