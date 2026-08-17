package com.visualspider.run.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.visualspider.identity.domain.ActorId;
import com.visualspider.identity.spi.IdentityAccess;
import com.visualspider.result.spi.RunEvent;
import com.visualspider.result.spi.RunEventQuery;
import com.visualspider.run.spi.RunProgress;
import com.visualspider.run.spi.RunState;
import com.visualspider.run.spi.StopReason;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * 运行进度推送器（M3-5 #27 / spec §D16 / D17）。
 *
 * <p>每个 WS 会话持有一个 {@link Subscription}，由 {@link RunProgressWebSocketHandler}
 * 创建。订阅逻辑：握手完成后立刻推送一次 {@code PROGRESS}；之后以固定间隔轮询：
 * <ul>
 *   <li>{@code collection_run.status} / 计数变更 -&gt; {@code PROGRESS}</li>
 *   <li>{@code run_event} 增量（{@code id > lastEventId}）-&gt; 每个事件一个 {@code EVENT}</li>
 *   <li>终态（SUCCESS / FAILED / CANCELLED / INTERRUPTED / PARTIAL_SUCCESS）-&gt;
 *       {@code TERMINAL} + 服务端主动 {@code close()}</li>
 * </ul>
 *
 * <p>轮询由 {@link Subscription#run()} 在独立守护线程上执行；终态或 WS 关闭时退出。
 * 单页 M3 约 5–6 条消息，单 JVM 资源开销极低。
 *
 * <p>本类不依赖运行执行器：进度 / 事件由 DB 轮询拉取，避免与 {@code SinglePageRunExecutor}
 * 双向耦合（M3-5 范围严格限定）。
 */
@Component
public class RunProgressBroadcaster {

    /** M3 spec §D16：WS schemaVersion 固定 1。 */
    public static final int SCHEMA_VERSION = 1;

    /** 终态集合：命中即发 TERMINAL 并 close。 */
    static final List<RunState> TERMINAL_STATES = List.of(
            RunState.SUCCESS, RunState.FAILED, RunState.CANCELLED,
            RunState.INTERRUPTED, RunState.PARTIAL_SUCCESS);

    private static final Logger LOG = LoggerFactory.getLogger(RunProgressBroadcaster.class);

    private final RunRepository repository;
    private final RunEventQuery eventQuery;
    private final IdentityAccess identityAccess;
    private final ObjectMapper objectMapper;
    private final long pollIntervalMs;

    public RunProgressBroadcaster(RunRepository repository,
                                  RunEventQuery eventQuery,
                                  IdentityAccess identityAccess,
                                  ObjectMapper objectMapper,
                                  @org.springframework.beans.factory.annotation.Value(
                                          "${run.ws.poll-interval-ms:200}") long pollIntervalMs) {
        this.repository = repository;
        this.eventQuery = eventQuery;
        this.identityAccess = identityAccess;
        this.objectMapper = objectMapper;
        this.pollIntervalMs = pollIntervalMs > 0 ? pollIntervalMs : 200L;
    }

    /**
     * 创建并启动一个订阅。返回的 {@link Subscription} 由 caller 持有；cancel 即停轮询。
     *
     * @throws IOException 初次发送 PROGRESS 失败
     */
    public Subscription subscribe(long runId, WebSocketSession ws, ActorId actor) throws IOException {
        Subscription sub = new Subscription(runId, ws, actor);
        sub.start();
        return sub;
    }

    /** 写一帧到 WS。 */
    void send(WebSocketSession ws, Object payload) throws IOException {
        String json = objectMapper.writeValueAsString(payload);
        ws.sendMessage(new TextMessage(json));
    }

    /**
     * 服务端 -&gt; 客户端 {@code PROGRESS} 帧（spec §D16）：
     * {@code {schemaVersion:1, type:PROGRESS, status, stopReason, stage, currentUrl,
     * pageCount, recordCountRaw, recordCountFinal, failCount, elapsedMs}}
     */
    public static final class ProgressFrame {
        public final int schemaVersion = SCHEMA_VERSION;
        public final String type = "PROGRESS";
        public final String status;
        public final String stopReason;
        public final String stage;
        public final String currentUrl;
        public final int pageCount;
        public final int recordCountRaw;
        public final int recordCountFinal;
        public final int failCount;
        public final long elapsedMs;

        public ProgressFrame(RunProgress p) {
            this.status = p.status() == null ? null : p.status().name();
            this.stopReason = p.stopReason() == null ? null : p.stopReason().name();
            this.stage = p.stage();
            this.currentUrl = p.currentUrl();
            this.pageCount = p.pageCount();
            this.recordCountRaw = p.recordCountRaw();
            this.recordCountFinal = p.recordCountFinal();
            this.failCount = p.failCount();
            this.elapsedMs = p.elapsedMs();
        }
    }

    /** 服务端 -&gt; 客户端 {@code EVENT} 帧。 */
    public static final class EventFrame {
        public final int schemaVersion = SCHEMA_VERSION;
        public final String type = "EVENT";
        public final long id;
        public final String level;
        public final String stage;
        public final String url;
        public final String errorCode;
        public final String message;
        public final long createdAt;

        public EventFrame(RunEvent e) {
            this.id = e.id();
            this.level = e.level().name();
            this.stage = e.stage();
            this.url = e.url();
            this.errorCode = e.errorCode();
            this.message = e.message();
            InstantOrZero tmp = InstantOrZero.of(e.createdAt());
            this.createdAt = tmp.millis;
        }

        private static final class InstantOrZero {
            final long millis;
            private InstantOrZero(long millis) {
                this.millis = millis;
            }
            static InstantOrZero of(java.time.Instant i) {
                return i == null ? new InstantOrZero(0L) : new InstantOrZero(i.toEpochMilli());
            }
        }
    }

    /** 服务端 -&gt; 客户端 {@code TERMINAL} 帧。 */
    public static final class TerminalFrame {
        public final int schemaVersion = SCHEMA_VERSION;
        public final String type = "TERMINAL";
        public final String status;
        public final String stopReason;
        public final long finishedAt;

        public TerminalFrame(RunState status, StopReason stopReason, OffsetDateTime finishedAt) {
            this.status = status == null ? null : status.name();
            this.stopReason = stopReason == null ? null : stopReason.name();
            this.finishedAt = finishedAt == null ? 0L
                    : finishedAt.toInstant().toEpochMilli();
        }
    }

    /** 客户端 -&gt; 服务端 {@code CANCEL} 消息解析结果。 */
    public static final class CancelMessage {
        public final int schemaVersion;
        public final String type;

        public CancelMessage(int schemaVersion, String type) {
            this.schemaVersion = schemaVersion;
            this.type = type;
        }
    }

    /** 一个 WS 会话的订阅：守护线程跑轮询直到 terminal 或 cancel 或关闭。 */
    public final class Subscription implements Runnable {

        private final long runId;
        private final WebSocketSession ws;
        private final ActorId actor;
        private volatile boolean stopped;
        private Thread worker;
        /** 已发送最后一条事件 ID；0 表示尚未发送任何事件（增量 = id &gt; 0）。 */
        private volatile long lastEventId;
        /** 已发送最后一条 PROGRESS 的状态签名（status + count）。 */
        private volatile String lastProgressSignature = "";

        Subscription(long runId, WebSocketSession ws, ActorId actor) {
            this.runId = runId;
            this.ws = ws;
            this.actor = actor;
        }

        public void start() throws IOException {
            // 1) 立即下发一次 PROGRESS
            sendInitialProgress();
            // 2) 立即补齐该 run 已写入的事件（断线重连场景也可能命中）
            sendPendingEvents();
            // 3) 启动守护线程做后续轮询
            worker = new Thread(this, "run-progress-broadcaster-" + runId);
            worker.setDaemon(true);
            worker.start();
        }

        private void sendInitialProgress() throws IOException {
            RunRepository.RunRecord rec = repository.findById(runId).orElse(null);
            if (rec == null) {
                ws.close(CloseStatus.POLICY_VIOLATION);
                stopped = true;
                return;
            }
            RunProgress progress = repository.loadProgress(runId).orElse(null);
            if (progress == null) {
                ws.close(CloseStatus.POLICY_VIOLATION);
                stopped = true;
                return;
            }
            send(ws, new ProgressFrame(progress));
            lastProgressSignature = signatureOf(progress);
        }

        private void sendPendingEvents() throws IOException {
            try {
                List<RunEvent> pending = eventQuery.after(runId, actor, lastEventId);
                for (RunEvent e : pending) {
                    send(ws, new EventFrame(e));
                    if (e.id() > lastEventId) {
                        lastEventId = e.id();
                    }
                }
            } catch (RuntimeException ex) {
                LOG.warn("event query failed runId={}: {}", runId, ex.getMessage());
            }
        }

        @Override
        public void run() {
            while (!stopped && ws.isOpen()) {
                try {
                    if (tick()) {
                        stopped = true;
                        break;
                    }
                    Thread.sleep(pollIntervalMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (RuntimeException ex) {
                    LOG.warn("broadcaster tick error runId={}: {}", runId, ex.getMessage());
                    try {
                        Thread.sleep(pollIntervalMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }

        /**
         * 单次 tick：拉 PROGRESS + 事件；返回 true 表示已发 TERMINAL 并关闭 WS。
         */
        private boolean tick() {
            try {
                RunRepository.RunRecord rec = repository.findById(runId).orElse(null);
                if (rec == null) {
                    closeQuietly(CloseStatus.POLICY_VIOLATION);
                    stopped = true;
                    return true;
                }
                RunProgress progress = repository.loadProgress(runId).orElse(null);
                if (progress == null) {
                    closeQuietly(CloseStatus.POLICY_VIOLATION);
                    stopped = true;
                    return true;
                }

                String signature = signatureOf(progress);
                if (!signature.equals(lastProgressSignature)) {
                    send(ws, new ProgressFrame(progress));
                    lastProgressSignature = signature;
                }

                sendPendingEvents();

                if (TERMINAL_STATES.contains(rec.status())) {
                    OffsetDateTime finishedAt = rec.finishedAt();
                    if (finishedAt == null) {
                        finishedAt = OffsetDateTime.now(ZoneOffset.UTC);
                    }
                    send(ws, new TerminalFrame(rec.status(), rec.stopReason(), finishedAt));
                    closeQuietly(CloseStatus.NORMAL);
                    stopped = true;
                    return true;
                }
            } catch (IOException io) {
                LOG.debug("ws send failed runId={}: {}", runId, io.getMessage());
                closeQuietly(CloseStatus.SERVER_ERROR);
                stopped = true;
                return true;
            } catch (RuntimeException ex) {
                LOG.warn("broadcaster tick error runId={}: {}", runId, ex.getMessage());
            }
            return false;
        }

        private static String signatureOf(RunProgress p) {
            return (p.status() == null ? "?" : p.status().name()) + "|"
                    + p.pageCount() + "|"
                    + p.recordCountFinal() + "|"
                    + p.failCount();
        }

        private void closeQuietly(CloseStatus status) {
            try {
                if (ws.isOpen()) {
                    ws.close(status);
                }
            } catch (IOException ignored) {
            }
        }

        /** 客户端断开 / 服务端取消订阅：停守护线程。 */
        public void cancel() {
            stopped = true;
            if (worker != null) {
                worker.interrupt();
            }
        }
    }

    /**
     * 校验客户端入站帧：仅 {@code CANCEL} 类型，schemaVersion=1。其余类型一律拒。
     *
     * @return 解析成功返回 {@link CancelMessage}；否则 null
     */
    public CancelMessage parseClientMessage(String payload) throws IOException {
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> map = objectMapper.readValue(payload, java.util.Map.class);
        Object sv = map.get("schemaVersion");
        Object type = map.get("type");
        if (!(sv instanceof Number n) || n.intValue() != SCHEMA_VERSION) {
            return null;
        }
        if (!"CANCEL".equals(type)) {
            return null;
        }
        return new CancelMessage(n.intValue(), "CANCEL");
    }

    /** 客户端断开 -> 停订阅。 */
    public void onClientDisconnect(Subscription sub) {
        if (sub != null) {
            sub.cancel();
        }
    }

    /**
     * CANCEL 重校所有权：admin 通过；其它要求 owner 匹配；run 不存在返回 false。
     */
    public boolean canCancel(long runId, ActorId actor) {
        return repository.findById(runId)
                .map(rec -> identityAccess.isAdmin() || rec.ownerId() == actor.value())
                .orElse(false);
    }

    /** 暴露给测试：默认轮询间隔。 */
    long pollIntervalMs() {
        return pollIntervalMs;
    }
}
