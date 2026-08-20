package com.visualspider.visualbrowser.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.visualspider.visualbrowser.spi.PacingPolicy;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link SingleDomainPacingPolicy} 单元测试（M5-5 / issue #43 / spec §D9 / T1）。
 *
 * <p>覆盖：同域连续调用实际测量间隔 ≥ 1s；跨域不互锁。
 */
class SingleDomainPacingPolicyTest {

    private final PacingPolicy policy = new SingleDomainPacingPolicy();

    @Test
    @DisplayName("同域连续 2 次调用间隔 ≥ 1s（实测）")
    void sameDomainRespectsInterval() throws MalformedURLException {
        URL url = URI.create("http://example.com/path").toURL();
        long t0 = System.currentTimeMillis();
        policy.beforeNavigate(url);
        policy.beforeNavigate(url);
        long elapsed = System.currentTimeMillis() - t0;
        // 第二次调用需阻塞至距第一次 ≥ 1s；实测 ≥ 900ms 容忍时钟抖动
        assertThat(elapsed).isGreaterThanOrEqualTo(900L);
        assertThat(elapsed).isLessThan(Duration.ofSeconds(2).toMillis());
    }

    @Test
    @DisplayName("不同域名不互锁")
    void differentDomainsAreIndependent() throws MalformedURLException {
        long t0 = System.currentTimeMillis();
        policy.beforeNavigate(URI.create("http://a.example.com/").toURL());
        policy.beforeNavigate(URI.create("http://b.example.com/").toURL());
        long elapsed = System.currentTimeMillis() - t0;
        // 不同 host 不互相阻塞，第二次几乎立即返回
        assertThat(elapsed).isLessThan(200L);
    }

    @Test
    @DisplayName("null / blank / 非法 URL 不抛异常")
    void nullAndInvalidUrlAreSafe() throws MalformedURLException {
        policy.beforeNavigate((URL) null);
        policy.beforeNavigate((String) null);
        policy.beforeNavigate("");
        policy.beforeNavigate("not a url");
        // 不抛即通过
    }
}