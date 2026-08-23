package com.visualspider.visualbrowser;

import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;

/**
 * WebSocket 入站守卫（M6-3 / docs/specs/m6.md §D7）。
 *
 * <p>挂到全部 WS handler（{@code /ws/visual-sessions/{id}} +
 * {@code /ws/runs/{id}}）统一覆盖入站限制：
 * <ul>
 *   <li>二进制消息一律拒绝并断连：出站 JPEG 帧才是二进制，入站二进制无合法用途</li>
 *   <li>文本消息字节 &gt; 8KB 拒绝并断连：合法命令（点击 / 滚动 / 键入 / CANCEL / 心跳）
 *       均 &lt; 1KB，8KB 是 8 倍余量</li>
 *   <li>每连接 20 msg/s（非重叠 1s 桶）；连续 3 个 1s 桶超限断连记 WARN</li>
 * </ul>
 *
 * <p>实现为 {@link WebSocketHandlerDecorator} 装饰器，对 delegate 透明；handler
 * 自身零改动。
 *
 * <p>断连日志只含会话标识与原因，不含消息内容（脱敏约定）。
 */
public class InboundGuard extends WebSocketHandlerDecorator {

    private static final Logger LOG = LoggerFactory.getLogger(InboundGuard.class);

    /** 文本入站最大字节数（8KB）。 */
    static final int MAX_TEXT_BYTES = 8 * 1024;
    /** 1s 桶内允许的消息数。 */
    static final int RATE_LIMIT_PER_SECOND = 20;
    /** 连续多少个 1s 桶超限后断连。 */
    static final int RATE_LIMIT_VIOLATION_THRESHOLD = 3;
    /** 1s 桶长度（ms）。 */
    private static final long BUCKET_MS = 1000L;

    /** 会话属性 key：上一处理的 1s 桶 id（{@code nowMs/BUCKET_MS}），初始 -1。 */
    static final String ATTR_LAST_BUCKET_ID = "ws.guard.lastBucketId";
    /** 会话属性 key：上一 1s 桶内的消息计数。 */
    static final String ATTR_LAST_BUCKET_COUNT = "ws.guard.lastBucketCount";
    /** 会话属性 key：上一 1s 桶是否超限（1=是, 0=否）。 */
    static final String ATTR_LAST_BUCKET_VIOLATED = "ws.guard.lastBucketViolated";
    /** 会话属性 key：上次记录的 1s 超限桶 id（{@code nowMs/BUCKET_MS}），初始 -1。 */
    static final String ATTR_LAST_VIOLATION_BUCKET = "ws.guard.lastViolationBucket";
    /** 会话属性 key：连续超限桶计数。 */
    static final String ATTR_VIOLATIONS = "ws.guard.violations";

    public InboundGuard(WebSocketHandler delegate) {
        super(delegate);
    }

    @Override
    public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) throws Exception {
        if (message instanceof BinaryMessage) {
            LOG.info("ws inbound binary rejected sessionId={}", session.getId());
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        if (message instanceof TextMessage text) {
            int len = text.getPayloadLength();
            if (len > MAX_TEXT_BYTES) {
                LOG.warn("ws inbound oversized rejected sessionId={} bytes={}",
                        session.getId(), len);
                session.close(CloseStatus.POLICY_VIOLATION);
                return;
            }
            if (checkRateLimit(session)) {
                LOG.warn("ws inbound rate limited sessionId={}", session.getId());
                session.close(CloseStatus.POLICY_VIOLATION);
                return;
            }
        }
        super.handleMessage(session, message);
    }

    /** 生产路径：注入系统时间。 */
    private boolean checkRateLimit(WebSocketSession session) {
        return checkRateLimit(session, System.currentTimeMillis());
    }

    /**
     * 速率检查：每条消息落到 {@code now/BUCKET_MS} 桶内；桶内消息数 &gt; 20 即视为本桶超限。
     *
     * <p>违规检测在桶内 count 首次越过阈值时进行：当前桶与上次违规桶相邻则 streak 累加，
     * 否则 streak 重置为 1。streak 达到 {@link #RATE_LIMIT_VIOLATION_THRESHOLD}（3）即断连。
     *
     * <p>语义：连续 3 个 1s 桶（即"3 秒内持续超限"）达阈值即断。
     *
     * <p>{@code nowMs} 由调用方注入（生产 = {@link System#currentTimeMillis()}，
     * 测试可指定任意时间）。
     *
     * <p>WebSocket 容器按 session 串行投递消息，无外部并发；session 属性本会话独占。
     */
    boolean checkRateLimit(WebSocketSession session, long nowMs) {
        long bucketId = nowMs / BUCKET_MS;

        AtomicLong lastBucketId = (AtomicLong) session.getAttributes()
                .computeIfAbsent(ATTR_LAST_BUCKET_ID, k -> new AtomicLong(-1L));
        AtomicLong lastBucketCount = (AtomicLong) session.getAttributes()
                .computeIfAbsent(ATTR_LAST_BUCKET_COUNT, k -> new AtomicLong(0L));
        AtomicLong lastBucketViolated = (AtomicLong) session.getAttributes()
                .computeIfAbsent(ATTR_LAST_BUCKET_VIOLATED, k -> new AtomicLong(0L));
        AtomicLong lastViolationBucket = (AtomicLong) session.getAttributes()
                .computeIfAbsent(ATTR_LAST_VIOLATION_BUCKET, k -> new AtomicLong(-1L));
        AtomicLong violations = (AtomicLong) session.getAttributes()
                .computeIfAbsent(ATTR_VIOLATIONS, k -> new AtomicLong(0L));

        if (lastBucketId.get() != bucketId) {
            // 进入新桶
            lastBucketId.set(bucketId);
            lastBucketCount.set(0L);
            lastBucketViolated.set(0L);
        }

        long count = lastBucketCount.incrementAndGet();
        if (count <= RATE_LIMIT_PER_SECOND) {
            return false;
        }

        // 本桶首次越阈值（lastBucketViolated 仍为 0）
        if (lastBucketViolated.getAndSet(1L) == 1L) {
            return false;  // 本桶已记录过
        }

        // 结算违规 streak
        long prevLast = lastViolationBucket.get();
        if (prevLast == -1L || bucketId - prevLast != 1) {
            // 第一次违规 / 与上次违规桶不相邻 -> streak 重置为 1
            violations.set(1L);
        } else {
            // 相邻违规 -> streak 累加
            long v = violations.incrementAndGet();
            if (v >= RATE_LIMIT_VIOLATION_THRESHOLD) {
                return true;
            }
        }
        lastViolationBucket.set(bucketId);
        return false;
    }
}
