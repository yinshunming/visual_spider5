package com.visualspider.shared.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * LoggingScrubber 单元测试：覆盖 password/Cookie/Authorization/XSRF/JSON 内 password|token。
 */
class LoggingScrubberTest {

    @Test
    @DisplayName("password= 形式被脱敏")
    void passwordAssignScrubbed() {
        String result = LoggingScrubber.scrub("login attempt: password=secret12345");
        assertThat(result).doesNotContain("secret12345");
        assertThat(result).contains("password=***");
    }

    @Test
    @DisplayName("password: 形式被脱敏（大小写不敏感）")
    void passwordColonScrubbed() {
        String result = LoggingScrubber.scrub("PASSWORD: topsecret-value");
        assertThat(result).doesNotContain("topsecret-value");
    }

    @Test
    @DisplayName("Authorization header 被脱敏")
    void authorizationHeaderScrubbed() {
        String result = LoggingScrubber.scrub("request header: Authorization: Bearer eyJhbGc.eyJzdWI.signature");
        assertThat(result).doesNotContain("eyJhbGc");
        assertThat(result).contains("Authorization: ***");
    }

    @Test
    @DisplayName("Set-Cookie header 被脱敏")
    void setCookieScrubbed() {
        String result = LoggingScrubber.scrub("response: Set-Cookie: JSESSIONID=abcd1234; Path=/; HttpOnly");
        assertThat(result).doesNotContain("abcd1234");
        assertThat(result).contains("Set-Cookie: ***");
    }

    @Test
    @DisplayName("XSRF-TOKEN cookie 被脱敏")
    void xsrfTokenScrubbed() {
        String result = LoggingScrubber.scrub("cookie: XSRF-TOKEN=verylongtokenvalue1234567890");
        assertThat(result).doesNotContain("verylongtokenvalue");
        assertThat(result).contains("XSRF-TOKEN=***");
    }

    @Test
    @DisplayName("JSON 内 password 字段被脱敏")
    void jsonPasswordScrubbed() {
        String result = LoggingScrubber.scrub("body: {\"username\":\"alice\",\"password\":\"hunter2-hunter2\"}");
        assertThat(result).doesNotContain("hunter2-hunter2");
        assertThat(result).contains("\"password\":\"***\"");
    }

    @Test
    @DisplayName("JSON 内 token 字段被脱敏")
    void jsonTokenScrubbed() {
        String result = LoggingScrubber.scrub("body: {\"token\":\"abc.def.ghi\",\"other\":\"keep\"}");
        assertThat(result).doesNotContain("abc.def.ghi");
        assertThat(result).contains("\"token\":\"***\"");
    }

    @Test
    @DisplayName("无关字段不被脱敏")
    void unrelatedFieldsKept() {
        String input = "user info: name=alice role=ADMIN visitCount=42";
        String result = LoggingScrubber.scrub(input);
        assertThat(result).isEqualTo(input);
    }

    @Test
    @DisplayName("null 与空字符串原样返回")
    void nullAndEmptyPassthrough() {
        assertThat(LoggingScrubber.scrub(null)).isNull();
        assertThat(LoggingScrubber.scrub("")).isEmpty();
    }
}
