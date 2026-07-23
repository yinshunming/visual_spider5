package com.visualspider.visualbrowser.internal;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BasicTargetUrlPolicyTest {

    private final BasicTargetUrlPolicy policy = new BasicTargetUrlPolicy();

    @ParameterizedTest
    @ValueSource(strings = {
        "http://localhost:8080/fixture",
        "http://example.com",
        "https://sub.example.com/path?q=test",
        "https://xn--fiqs8s.example/"
    })
    void acceptsHttpUrlsWithValidHostSyntax(String url) {
        assertThatCode(() -> policy.validate(url)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        " ",
        "/relative",
        "file:///tmp/test.html",
        "about:blank",
        "ftp://example.com",
        "http:///missing-host",
        "http://bad_host.example",
        "http://-bad.example",
        "http://bad-.example"
    })
    void rejectsUnsupportedOrMalformedUrls(String url) {
        assertThatThrownBy(() -> policy.validate(url))
                .isInstanceOf(InvalidTargetUrlException.class)
                .hasMessage("目标 URL 必须是包含合法主机名的 http(s) URL");
    }
}
