package com.visualspider.run.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.visualspider.identity.domain.ActorId;
import com.visualspider.identity.spi.IdentityAccess;
import com.visualspider.result.spi.RunEvent;
import com.visualspider.result.spi.RunEventLevel;
import com.visualspider.result.spi.RunEventQuery;
import com.visualspider.run.spi.RunProgress;
import com.visualspider.run.spi.RunState;
import com.visualspider.run.spi.StopReason;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * {@link RunProgressBroadcaster} 单元测试（#27 / spec §D16 / D17）。
 *
 * <p>覆盖：
 * <ul>
 *   <li>PROGRESS / EVENT / TERMINAL 帧 schemaVersion=1 + 字段名一致</li>
 *   <li>握手先发 PROGRESS 快照 + 已写入事件</li>
 *   <li>守护 tick：state/count 变 -&gt; PROGRESS；event 增量 -&gt; EVENT</li>
 *   <li>终态 -&gt; TERMINAL + 服务端 close</li>
 *   <li>parseClientMessage：仅 schemaVersion=1 + type=CANCEL 通过</li>
 *   <li>canCancel：admin 通过；owner 通过；他人拒</li>
 * </ul>
 */
class RunProgressBroadcasterTest {

    private RunRepository repository;
    private RunEventQuery eventQuery;
    private IdentityAccess identityAccess;
    private ObjectMapper objectMapper;
    private RunProgressBroadcaster broadcaster;

    @BeforeEach
    void setUp() {
        repository = mock(RunRepository.class);
        eventQuery = mock(RunEventQuery.class);
        identityAccess = mock(IdentityAccess.class);
        objectMapper = new ObjectMapper();
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        broadcaster = new RunProgressBroadcaster(repository, eventQuery, identityAccess,
                objectMapper, 10L);
    }

    // ---------- helpers ----------

    private RunRepository.RunRecord record(RunState state, int page, int finalCount) {
        return new RunRepository.RunRecord(
                42L, 1L, 99L, state,
                state == RunState.SUCCESS ? StopReason.COMPLETED : null,
                false, page, finalCount, 0, null,
                OffsetDateTime.now(), OffsetDateTime.now(),
                state == RunState.SUCCESS ? OffsetDateTime.now() : null);
    }

    private RunProgress progress(RunState state, int page, int finalCount) {
        return new RunProgress(state,
                state == RunState.SUCCESS ? StopReason.COMPLETED : null,
                "navigate", "https://example.com/",
                page, finalCount, finalCount, 0,
                null, 100L);
    }

    private static RunEvent event(long id, RunEventLevel level, String message) {
        return new RunEvent(id, 42L, level, "navigate", "https://example.com/",
                null, message, Instant.now());
    }

    private WebSocketSession sessionRecording(List<TextMessage> sink, List<CloseStatus> closes) {
        WebSocketSession ws = mock(WebSocketSession.class);
        when(ws.isOpen()).thenReturn(true);
        try {
            org.mockito.Mockito.doAnswer(inv -> {
                TextMessage tm = inv.getArgument(0);
                sink.add(tm);
                return null;
            }).when(ws).sendMessage(any(TextMessage.class));
            org.mockito.Mockito.doAnswer(inv -> {
                closes.add(inv.getArgument(0));
                return null;
            }).when(ws).close(any(CloseStatus.class));
        } catch (IOException io) {
            throw new RuntimeException(io);
        }
        return ws;
    }

    private List<RunEvent> emptyEvents() {
        return new ArrayList<>();
    }

    // ---------- tests ----------

    @Test
    void initialSubscribeSendsProgressSnapshotAndRunsWatcher() throws Exception {
        when(repository.findById(42L)).thenReturn(Optional.of(record(RunState.RUNNING, 0, 0)));
        when(repository.loadProgress(42L)).thenReturn(Optional.of(progress(RunState.RUNNING, 0, 0)));
        when(eventQuery.after(eq(42L), any(ActorId.class), eq(0L))).thenReturn(emptyEvents());

        List<TextMessage> sent = new ArrayList<>();
        List<CloseStatus> closes = new ArrayList<>();
        WebSocketSession ws = sessionRecording(sent, closes);

        RunProgressBroadcaster.Subscription sub = broadcaster.subscribe(42L, ws, new ActorId(99L));

        // 第一个 send 必须是 PROGRESS
        assertThat(sent).isNotEmpty();
        TextMessage firstFrame = sent.get(0);
        assertThat(firstFrame.getPayload()).startsWith("{\"schemaVersion\":1,\"type\":\"PROGRESS\"");
        // 验证字段
        assertThat(firstFrame.getPayload()).contains("\"status\":\"RUNNING\"");
        assertThat(firstFrame.getPayload()).contains("\"recordCountRaw\":0");

        // 等守护线程跑几个 tick（无变更）：不应发更多帧（progress 签名不变）
        // 不阻塞；但 cancel() 必须立即停
        sub.cancel();
        // 等守护线程退出
        Thread.sleep(50);
        // 终态未达：不会 close
        assertThat(closes).isEmpty();
    }

    @Test
    void terminalStateTriggersTerminalFrameAndClose() throws Exception {
        // initial = RUNNING
        when(repository.findById(42L))
                .thenReturn(Optional.of(record(RunState.RUNNING, 0, 0)));
        when(repository.loadProgress(42L))
                .thenReturn(Optional.of(progress(RunState.RUNNING, 0, 0)));
        // tick 后：state -> SUCCESS
        when(repository.findById(42L))
                .thenReturn(Optional.of(record(RunState.SUCCESS, 1, 1)));
        when(repository.loadProgress(42L))
                .thenReturn(Optional.of(progress(RunState.SUCCESS, 1, 1)));

        when(eventQuery.after(eq(42L), any(ActorId.class), anyLong()))
                .thenReturn(emptyEvents());

        List<TextMessage> sent = new ArrayList<>();
        List<CloseStatus> closes = new ArrayList<>();
        WebSocketSession ws = sessionRecording(sent, closes);

        RunProgressBroadcaster.Subscription sub = broadcaster.subscribe(42L, ws, new ActorId(99L));

        // 等守护线程跑到 tick（轮询间隔 10ms）
        Thread.sleep(120);

        assertThat(closes).as("终端态须 close").isNotEmpty();
        assertThat(closes.get(0)).isEqualTo(CloseStatus.NORMAL);
        // 必须出现 TERMINAL 帧
        boolean terminalSeen = sent.stream()
                .anyMatch(tm -> tm.getPayload().startsWith(
                        "{\"schemaVersion\":1,\"type\":\"TERMINAL\""));
        assertThat(terminalSeen).as("需发 TERMINAL").isTrue();

        sub.cancel();
    }

    @Test
    void pendingEventsAreForwardedAtStart() throws Exception {
        when(repository.findById(42L)).thenReturn(Optional.of(record(RunState.RUNNING, 0, 0)));
        when(repository.loadProgress(42L)).thenReturn(Optional.of(progress(RunState.RUNNING, 0, 0)));
        // 初始库内已有 2 条事件
        RunEvent ev1 = event(1001L, RunEventLevel.INFO, "entry-start");
        RunEvent ev2 = event(1002L, RunEventLevel.WARN, "navigate retry");
        when(eventQuery.after(eq(42L), any(ActorId.class), eq(0L)))
                .thenReturn(List.of(ev1, ev2));

        List<TextMessage> sent = new ArrayList<>();
        List<CloseStatus> closes = new ArrayList<>();
        WebSocketSession ws = sessionRecording(sent, closes);

        RunProgressBroadcaster.Subscription sub = broadcaster.subscribe(42L, ws, new ActorId(99L));

        // 必须包含两条 EVENT 帧
        long eventFrames = sent.stream()
                .filter(tm -> tm.getPayload().contains("\"type\":\"EVENT\""))
                .count();
        assertThat(eventFrames).as("初始事件补齐").isEqualTo(2);

        sub.cancel();
        Thread.sleep(50);
    }

    @Test
    void cancelOwnershipAdminAllowed() {
        when(repository.findById(42L)).thenReturn(Optional.of(record(RunState.RUNNING, 0, 0)));
        when(identityAccess.isAdmin()).thenReturn(true);
        assertThat(broadcaster.canCancel(42L, new ActorId(1L))).isTrue();
    }

    @Test
    void cancelOwnershipOwnerAllowed() {
        when(repository.findById(42L)).thenReturn(Optional.of(record(RunState.RUNNING, 0, 0)));
        when(identityAccess.isAdmin()).thenReturn(false);
        assertThat(broadcaster.canCancel(42L, new ActorId(99L))).isTrue(); // owner=99
    }

    @Test
    void cancelOwnershipNonOwnerDenied() {
        when(repository.findById(42L)).thenReturn(Optional.of(record(RunState.RUNNING, 0, 0)));
        when(identityAccess.isAdmin()).thenReturn(false);
        assertThat(broadcaster.canCancel(42L, new ActorId(2L))).isFalse();
    }

    @Test
    void cancelOwnershipMissingRunDenied() {
        when(repository.findById(42L)).thenReturn(Optional.empty());
        assertThat(broadcaster.canCancel(42L, new ActorId(99L))).isFalse();
    }

    @Test
    void parseClientMessageAcceptsCancelV1() throws Exception {
        String json = "{\"schemaVersion\":1,\"type\":\"CANCEL\"}";
        RunProgressBroadcaster.CancelMessage msg = broadcaster.parseClientMessage(json);
        assertThat(msg).isNotNull();
        assertThat(msg.schemaVersion).isEqualTo(1);
        assertThat(msg.type).isEqualTo("CANCEL");
    }

    @Test
    void parseClientMessageRejectsWrongType() throws Exception {
        assertThat(broadcaster.parseClientMessage("{\"schemaVersion\":1,\"type\":\"OTHER\"}"))
                .isNull();
    }

    @Test
    void parseClientMessageRejectsWrongSchemaVersion() throws Exception {
        assertThat(broadcaster.parseClientMessage("{\"schemaVersion\":2,\"type\":\"CANCEL\"}"))
                .isNull();
        assertThat(broadcaster.parseClientMessage("{\"type\":\"CANCEL\"}"))
                .isNull();
    }

    @Test
    void progressFrameSchemaAndFields() {
        RunProgress p = progress(RunState.RUNNING, 1, 1);
        RunProgressBroadcaster.ProgressFrame frame = new RunProgressBroadcaster.ProgressFrame(p);
        assertThat(frame.schemaVersion).isEqualTo(RunProgressBroadcaster.SCHEMA_VERSION);
        assertThat(frame.type).isEqualTo("PROGRESS");
        assertThat(frame.status).isEqualTo("RUNNING");
        assertThat(frame.recordCountFinal).isEqualTo(1);
        assertThat(frame.recordCountRaw).isEqualTo(1);
    }

    @Test
    void terminalFrameSchemaAndFields() {
        RunProgressBroadcaster.TerminalFrame frame = new RunProgressBroadcaster.TerminalFrame(
                RunState.SUCCESS, StopReason.COMPLETED, OffsetDateTime.now());
        assertThat(frame.schemaVersion).isEqualTo(RunProgressBroadcaster.SCHEMA_VERSION);
        assertThat(frame.type).isEqualTo("TERMINAL");
        assertThat(frame.status).isEqualTo("SUCCESS");
        assertThat(frame.stopReason).isEqualTo("COMPLETED");
    }

    @Test
    void eventFrameShape() {
        RunProgressBroadcaster.EventFrame frame = new RunProgressBroadcaster.EventFrame(
                event(7L, RunEventLevel.ERROR, "boom"));
        assertThat(frame.schemaVersion).isEqualTo(RunProgressBroadcaster.SCHEMA_VERSION);
        assertThat(frame.type).isEqualTo("EVENT");
        assertThat(frame.id).isEqualTo(7L);
        assertThat(frame.level).isEqualTo("ERROR");
        assertThat(frame.message).isEqualTo("boom");
    }

    /** 通过反射访问 package-private 状态字段 / 工具方法（白盒覆盖）。 */
    @SuppressWarnings("unused")
    private static void reflectionProbe() throws Exception {
        Field cfgField = RunProgressBroadcaster.class.getDeclaredField("SCHEMA_VERSION");
        Method signatureMethod = RunProgressBroadcaster.Subscription.class
                .getDeclaredMethod("signatureOf", RunProgress.class);
        // 仅触发编译器加载（保证 symbol 不丢）
        assertThat(cfgField.getModifiers()).isNotZero();
        assertThat(signatureMethod.getModifiers()).isNotZero();
    }
}
