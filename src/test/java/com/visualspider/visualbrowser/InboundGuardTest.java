package com.visualspider.visualbrowser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;

/**
 * {@link InboundGuard} 单元测试（spec §D7 / T1）。
 *
 * <p>覆盖：
 * <ul>
 *   <li>二进制入站 -&gt; 断连 + delegate 不收到消息</li>
 *   <li>文本 &gt; 8KB -&gt; 断连</li>
 *   <li>文本 &lt;= 8KB 正常速率 -&gt; delegate 收到消息</li>
 *   <li>单 1s 桶内 21 条 -&gt; 桶标记超限但不立即断连（需 3 桶连续）</li>
 *   <li>连续 3 个 1s 桶超限 -&gt; 断连</li>
 *   <li>非相邻 1s 桶超限 -&gt; 计数重置为 1</li>
 *   <li>中间有未超限桶 -&gt; 连续计数重置为 0</li>
 * </ul>
 */
class InboundGuardTest {

    private WebSocketHandler delegate;
    private WebSocketSession session;
    private Map<String, Object> attrs;
    private InboundGuard guard;

    @BeforeEach
    void setUp() {
        delegate = mock(WebSocketHandler.class);
        session = mock(WebSocketSession.class);
        attrs = new HashMap<>();
        when(session.getAttributes()).thenReturn(attrs);
        when(session.getId()).thenReturn("test-session");
        guard = new InboundGuard(delegate);
    }

    // ========== handleMessage: 二进制 / 超大 ==========

    @Test
    @DisplayName("二进制入站 -> 断连, delegate 不收到消息")
    void binaryRejected() throws Exception {
        BinaryMessage msg = new BinaryMessage(new byte[]{1, 2, 3});

        guard.handleMessage(session, msg);

        verify(session, times(1)).close(CloseStatus.POLICY_VIOLATION);
        verify(delegate, never()).handleMessage(session, msg);
    }

    @Test
    @DisplayName("文本 > 8KB -> 断连")
    void oversizedRejected() throws Exception {
        byte[] big = new byte[InboundGuard.MAX_TEXT_BYTES + 1];
        TextMessage msg = new TextMessage(big);

        guard.handleMessage(session, msg);

        verify(session, times(1)).close(CloseStatus.POLICY_VIOLATION);
        verify(delegate, never()).handleMessage(session, msg);
    }

    @Test
    @DisplayName("文本 <= 8KB 正常速率 -> delegate 收到消息, 不断连")
    void normalPassesThrough() throws Exception {
        TextMessage msg = new TextMessage("{\"type\":\"CLICK\",\"x\":10,\"y\":20}");

        guard.handleMessage(session, msg);

        verify(session, never()).close(org.mockito.ArgumentMatchers.any(CloseStatus.class));
        verify(delegate, times(1)).handleMessage(session, msg);
    }

    // ========== checkRateLimit: 注入时间 ==========

    @Test
    @DisplayName("单 1s 桶内 21 条: 桶标记 violated=1, 但不立即断连")
    void singleBucketViolation() throws Exception {
        // 21 条全部在桶 1 (t=1000..1020)
        for (int i = 0; i < 21; i++) {
            assertThat(guard.checkRateLimit(session, 1000L + i)).isFalse();
        }
        assertThat(((AtomicLong) attrs.get(InboundGuard.ATTR_LAST_BUCKET_VIOLATED)).get())
                .isEqualTo(1L);
        // 不立即断连
        verify(session, never()).close(org.mockito.ArgumentMatchers.any(CloseStatus.class));
    }

    @Test
    @DisplayName("连续 3 个 1s 桶各超限 -> 第 3 桶越阈值时断连")
    void threeConsecutiveBucketsDisconnect() throws Exception {
        // 桶 1: 21 条
        for (int i = 0; i < 21; i++) {
            assertThat(guard.checkRateLimit(session, 1000L + i)).isFalse();
        }
        // 桶 2: 21 条
        for (int i = 0; i < 21; i++) {
            assertThat(guard.checkRateLimit(session, 2000L + i)).isFalse();
        }
        // 桶 3: 前 20 条不超限,第 21 条触发 violations=3 -> 返回 true (handleMessage 会断连)
        boolean disconnected = false;
        for (int i = 0; i < 21; i++) {
            disconnected = guard.checkRateLimit(session, 3000L + i);
        }
        assertThat(disconnected).isTrue();
    }

    @Test
    @DisplayName("非相邻 1s 桶超限 -> 计数重置为 1, 不断连")
    void nonAdjacentBucketsReset() throws Exception {
        // 桶 1: 21 条 -> violations=1
        for (int i = 0; i < 21; i++) {
            guard.checkRateLimit(session, 1000L + i);
        }
        assertThat(((AtomicLong) attrs.get(InboundGuard.ATTR_VIOLATIONS)).get()).isEqualTo(1L);

        // 桶 3 (跳过桶 2): 21 条
        // 与上次违规桶 1 间隔 2 -> 不相邻 -> 重置 violations=1
        for (int i = 0; i < 21; i++) {
            assertThat(guard.checkRateLimit(session, 3000L + i)).isFalse();
        }
        assertThat(((AtomicLong) attrs.get(InboundGuard.ATTR_VIOLATIONS)).get()).isEqualTo(1L);
        verify(session, never()).close(org.mockito.ArgumentMatchers.any(CloseStatus.class));
    }

    @Test
    @DisplayName("中间有未超限桶 -> 桶 3 越阈值时与上次违规桶 1 不相邻, 重置为 1")
    void quietBucketResetsCounter() throws Exception {
        // 桶 1: 21 条
        for (int i = 0; i < 21; i++) {
            guard.checkRateLimit(session, 1000L + i);
        }

        // 桶 2: 仅 5 条 (< 20) -> 不超限
        for (int i = 0; i < 5; i++) {
            guard.checkRateLimit(session, 2000L + i);
        }

        // 桶 3: 21 条 -> 上次违规桶=1, 间隔 2 -> 重置 violations=1
        boolean disconnected = false;
        for (int i = 0; i < 21; i++) {
            disconnected = guard.checkRateLimit(session, 3000L + i);
        }
        assertThat(disconnected).isFalse();
        assertThat(((AtomicLong) attrs.get(InboundGuard.ATTR_VIOLATIONS)).get()).isEqualTo(1L);
        verify(session, never()).close(org.mockito.ArgumentMatchers.any(CloseStatus.class));
    }

    @Test
    @DisplayName("跨越大间隔 (>2s) 触发 violation 重置")
    void largeGapResetsCounter() throws Exception {
        // 桶 1 + 桶 2 各超限 -> violations=2
        for (int i = 0; i < 21; i++) guard.checkRateLimit(session, 1000L + i);
        for (int i = 0; i < 21; i++) guard.checkRateLimit(session, 2000L + i);
        assertThat(((AtomicLong) attrs.get(InboundGuard.ATTR_VIOLATIONS)).get()).isEqualTo(2L);

        // 桶 4 (跳 2): 与上次违规桶 2 间隔 2 -> 重置 violations=1
        for (int i = 0; i < 21; i++) {
            assertThat(guard.checkRateLimit(session, 4000L + i)).isFalse();
        }
        assertThat(((AtomicLong) attrs.get(InboundGuard.ATTR_VIOLATIONS)).get()).isEqualTo(1L);
    }

    @Test
    @DisplayName("同桶内多次超限消息 -> violations 不累加")
    void sameBucketNoRecount() throws Exception {
        // 桶 1: 25 条;只有第 21 条触发 violation 记录,violations=1
        for (int i = 0; i < 25; i++) {
            assertThat(guard.checkRateLimit(session, 1000L + i)).isFalse();
        }
        assertThat(((AtomicLong) attrs.get(InboundGuard.ATTR_VIOLATIONS)).get()).isEqualTo(1L);

        // 桶 2: 第 21 条触发 violation,与上次违规桶 1 相邻 -> violations=2
        boolean firstOfBucket2 = false;
        for (int i = 0; i < 21; i++) {
            firstOfBucket2 = guard.checkRateLimit(session, 2000L + i);
        }
        assertThat(firstOfBucket2).isFalse();
        assertThat(((AtomicLong) attrs.get(InboundGuard.ATTR_VIOLATIONS)).get()).isEqualTo(2L);

        // 桶 3: 第 21 条触发 violation,相邻 -> violations=3 -> 返回 true
        boolean disconnected = false;
        for (int i = 0; i < 21; i++) {
            disconnected = guard.checkRateLimit(session, 3000L + i);
        }
        assertThat(disconnected).isTrue();
    }

    @Test
    @DisplayName("不同会话的状态独立")
    void independentSessions() throws Exception {
        WebSocketSession session2 = mock(WebSocketSession.class);
        Map<String, Object> attrs2 = new HashMap<>();
        when(session2.getAttributes()).thenReturn(attrs2);

        // session1: 桶 1 满 -> violations=1
        for (int i = 0; i < 21; i++) {
            guard.checkRateLimit(session, 1000L + i);
        }
        assertThat(((AtomicLong) attrs.get(InboundGuard.ATTR_VIOLATIONS)).get()).isEqualTo(1L);
        // session2: 桶 1 仅有 5 条 (未超限)
        for (int i = 0; i < 5; i++) {
            guard.checkRateLimit(session2, 1000L + i);
        }
        // session2 独立状态:violations 初始 0
        assertThat(((AtomicLong) attrs2.get(InboundGuard.ATTR_VIOLATIONS)).get()).isEqualTo(0L);
        // session2 桶 1 计数应为 5
        assertThat(((AtomicLong) attrs2.get(InboundGuard.ATTR_LAST_BUCKET_COUNT)).get())
                .isEqualTo(5L);
    }

    @Test
    @DisplayName("同桶内 20 条 -> 不断连 (阈值=20, 21 才超限)")
    void exactlyAtThresholdNoViolation() throws Exception {
        for (int i = 0; i < 20; i++) {
            assertThat(guard.checkRateLimit(session, 1000L + i)).isFalse();
        }
        assertThat(((AtomicLong) attrs.get(InboundGuard.ATTR_LAST_BUCKET_VIOLATED)).get())
                .isEqualTo(0L);
    }

    @Test
    @DisplayName("正常 20 msg/s 持续 5s -> 不断连")
    void sustainedNormalRate() throws Exception {
        for (int s = 0; s < 5; s++) {
            for (int i = 0; i < 20; i++) {
                boolean disconnect = guard.checkRateLimit(session, 1000L * s + i * 50L);
                assertThat(disconnect).isFalse();
            }
        }
        verify(session, never()).close(org.mockito.ArgumentMatchers.any(CloseStatus.class));
    }
}
