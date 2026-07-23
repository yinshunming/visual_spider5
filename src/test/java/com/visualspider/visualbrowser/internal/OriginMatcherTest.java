package com.visualspider.visualbrowser.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OriginMatcherTest {

    @Test
    void exactSameOriginMatches() {
        assertThat(OriginMatcher.matches("http://example.com", "http://example.com/app")).isTrue();
    }

    @Test
    void differentHostIsRejected() {
        assertThat(OriginMatcher.matches("http://evil.com", "http://example.com")).isFalse();
    }

    @Test
    void differentPortIsRejected() {
        assertThat(OriginMatcher.matches("http://example.com:8081", "http://example.com")).isFalse();
    }

    @Test
    void differentSchemeIsRejected() {
        assertThat(OriginMatcher.matches("https://example.com", "http://example.com")).isFalse();
    }

    @Test
    void missingOriginIsRejected() {
        assertThat(OriginMatcher.matches(null, "http://example.com")).isFalse();
        assertThat(OriginMatcher.matches("", "http://example.com")).isFalse();
    }

    @Test
    void defaultPortsAreEquivalent() {
        assertThat(OriginMatcher.matches("ws://example.com", "ws://example.com:80/path")).isTrue();
        assertThat(OriginMatcher.matches("wss://example.com", "wss://example.com:443/path")).isTrue();
    }
}
