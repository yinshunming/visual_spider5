package com.visualspider.run.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * {@link RunOwnerHandshakeInterceptor} 静态解析 / 工具方法测试（#27 / spec §D16）。
 *
 * <p>只测纯函数：path -&gt; runId 解析 + query 参数解析。
 * 实际握手流程（HTTP / cookie / SecurityContext / Origin）由 {@link
 * RunOwnerHandshakeInterceptorBeanTest} 覆盖。
 */
class RunOwnerHandshakeInterceptorParseTest {

    @Test
    void extractRunIdFromPath() {
        assertThat(RunOwnerHandshakeInterceptor.extractRunIdFromPath("/ws/runs/123"))
                .isEqualTo(123L);
        assertThat(RunOwnerHandshakeInterceptor.extractRunIdFromPath("/ws/runs/")).isNull();
        assertThat(RunOwnerHandshakeInterceptor.extractRunIdFromPath("/ws/runs/abc")).isNull();
        assertThat(RunOwnerHandshakeInterceptor.extractRunIdFromPath("/ws/runs/0")).isNull();
        assertThat(RunOwnerHandshakeInterceptor.extractRunIdFromPath("/ws/runs/-1")).isNull();
        assertThat(RunOwnerHandshakeInterceptor.extractRunIdFromPath(null)).isNull();
    }

    @Test
    void extractRunIdFromPath_rejectsTraversal() {
        // 末尾是 ".."：segment=".." 直接拒（路径穿越）
        assertThat(RunOwnerHandshakeInterceptor.extractRunIdFromPath("/ws/runs/..")).isNull();
        // 路径中含 ".."（如 "/ws/runs/../etc"）：lastIndexOf('/') 拿到末尾的 "etc"；
        // segment="etc" 不是 long，返回 null
        assertThat(RunOwnerHandshakeInterceptor.extractRunIdFromPath("/ws/runs/../etc")).isNull();
        // 含正斜杠的 segment：拒
        assertThat(RunOwnerHandshakeInterceptor.extractRunIdFromPath("/ws/runs/a/b")).isNull();
        // 含反斜杠的 segment：拒
        assertThat(RunOwnerHandshakeInterceptor.extractRunIdFromPath("/ws/runs/1\\2")).isNull();
    }

    @Test
    void extractSingleQueryParam() {
        assertThat(RunOwnerHandshakeInterceptor.extractSingleQueryParam(
                "csrfToken=abc%2F%3D&other=x", "csrfToken"))
                .isEqualTo("abc/=");
        assertThat(RunOwnerHandshakeInterceptor.extractSingleQueryParam(
                "csrfToken=abc", "csrfToken"))
                .isEqualTo("abc");
        // 重复且不同值 -> null
        assertThat(RunOwnerHandshakeInterceptor.extractSingleQueryParam(
                "csrfToken=abc&csrfToken=def", "csrfToken"))
                .isNull();
        // 缺少
        assertThat(RunOwnerHandshakeInterceptor.extractSingleQueryParam("", "csrfToken")).isNull();
        assertThat(RunOwnerHandshakeInterceptor.extractSingleQueryParam(null, "csrfToken")).isNull();
    }
}
