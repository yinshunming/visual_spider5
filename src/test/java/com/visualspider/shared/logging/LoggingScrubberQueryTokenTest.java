package com.visualspider.shared.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * LoggingScrubber 在 query string 中 token / csrf 字段脱敏测试。
 *
 * <p>M2-1 (#17)：WebSocket 握手 URL 自带 `?csrf=...&token=...`，
 * Spring/容器日志会打印完整 query；为避免泄露 XSRF-TOKEN，
 * 在 query string 上下文中也脱敏 token 与 csrf 值。
 */
class LoggingScrubberQueryTokenTest {

    @Test
    @DisplayName("query string 中的 csrf= 值被脱敏")
    void queryCsrfScrubbed() {
        String input = "ws handshake uri=/ws/visual?sessionId=s1&csrf=abc123token456";
        String result = LoggingScrubber.scrub(input);
        assertThat(result).doesNotContain("abc123token456");
        assertThat(result.toLowerCase()).contains("csrf=***");
    }

    @Test
    @DisplayName("query string 中的 token= 值被脱敏（大小写不敏感）")
    void queryTokenScrubbed() {
        String input = "request uri=/api/foo?Token=secret-tok-789&other=keep";
        String result = LoggingScrubber.scrub(input);
        assertThat(result).doesNotContain("secret-tok-789");
        assertThat(result.toLowerCase()).contains("token=***");
        assertThat(result).contains("other=keep");
    }

    @Test
    @DisplayName("query string 中的 password= 值被脱敏")
    void queryPasswordScrubbed() {
        String input = "log line: ?password=hunter2hunter2";
        String result = LoggingScrubber.scrub(input);
        assertThat(result).doesNotContain("hunter2hunter2");
        assertThat(result.toLowerCase()).contains("password=***");
    }

    @Test
    @DisplayName("无关 query 参数不会被脱敏")
    void queryUnrelatedKept() {
        String input = "uri=/api/x?id=42&page=1&mode=browse";
        String result = LoggingScrubber.scrub(input);
        assertThat(result).isEqualTo(input);
    }
}
