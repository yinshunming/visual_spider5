package com.visualspider.visualbrowser.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SessionOwnerHandshakeInterceptorParseTest {

    @Test
    void extractSessionIdFromPath() {
        assertThat(SessionOwnerHandshakeInterceptor.extractSessionIdFromPath("/ws/visual-sessions/abc123"))
                .isEqualTo("abc123");
        assertThat(SessionOwnerHandshakeInterceptor.extractSessionIdFromPath("/ws/visual-sessions/")).isNull();
        assertThat(SessionOwnerHandshakeInterceptor.extractSessionIdFromPath("/ws/visual-sessions/abc/extra"))
                .isEqualTo("extra");
        assertThat(SessionOwnerHandshakeInterceptor.extractSessionIdFromPath("/ws/visual-sessions/..")).isNull();
        assertThat(SessionOwnerHandshakeInterceptor.extractSessionIdFromPath(null)).isNull();
    }

    @Test
    void extractSingleQueryParam() {
        assertThat(SessionOwnerHandshakeInterceptor.extractSingleQueryParam(
                "csrfToken=abc%2F%3D&other=x", "csrfToken"))
                .isEqualTo("abc/=");
        assertThat(SessionOwnerHandshakeInterceptor.extractSingleQueryParam(
                "csrfToken=abc", "csrfToken"))
                .isEqualTo("abc");
        // 重复且不同值 → null
        assertThat(SessionOwnerHandshakeInterceptor.extractSingleQueryParam(
                "csrfToken=abc&csrfToken=def", "csrfToken"))
                .isNull();
        // 缺少
        assertThat(SessionOwnerHandshakeInterceptor.extractSingleQueryParam("", "csrfToken")).isNull();
        assertThat(SessionOwnerHandshakeInterceptor.extractSingleQueryParam(null, "csrfToken")).isNull();
    }
}
