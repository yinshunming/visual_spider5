package com.visualspider.run.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.microsoft.playwright.Page;
import com.visualspider.run.spi.StopReason;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link PageStopDetector} 单元测试（M5-5 / issue #43 / spec §D10 / T1）。
 *
 * <p>覆盖：429 立即 / 滑动窗口 3 个 403 / 1 个 403 + 2 个 200 不触发 /
 * reCAPTCHA iframe / captcha 文本。
 */
@ExtendWith(MockitoExtension.class)
class PageStopDetectorTest {

    @Mock private Page page;
    private List<StopReason> stops;
    private PageStopDetector detector;

    @BeforeEach
    void setUp() {
        stops = new ArrayList<>();
        detector = new PageStopDetector(stops::add);
    }

    @Test
    @DisplayName("HTTP 429 单次响应 -> 立即触发 HTTP_429")
    void http429TriggersImmediately() {
        detector.recordResponse(429);
        assertThat(stops).containsExactly(StopReason.HTTP_429);
    }

    @Test
    @DisplayName("连续 3 个 403 -> 触发 HTTP_403")
    void slidingWindowThreeForbidden() {
        detector.recordResponse(403);
        detector.recordResponse(403);
        detector.recordResponse(403);
        assertThat(stops).containsExactly(StopReason.HTTP_403);
    }

    @Test
    @DisplayName("1 个 403 + 2 个 200 -> 不触发停止（窗口被 200 清空）")
    void mixed403And200NoTrigger() {
        detector.recordResponse(403);
        detector.recordResponse(200);
        detector.recordResponse(200);
        assertThat(stops).isEmpty();
    }

    @Test
    @DisplayName("2 个 403 + 1 个 200 + 1 个 403 -> 不触发（窗口被重置，未达 3 连击）")
    void interruptedSlidingWindowDoesNotTrigger() {
        detector.recordResponse(403);
        detector.recordResponse(403);
        detector.recordResponse(200);
        detector.recordResponse(403);
        assertThat(stops).isEmpty();
    }

    @Test
    @DisplayName("reCAPTCHA iframe 命中 -> CAPTCHA")
    void recaptchaIframeTriggersCaptcha() {
        when(page.querySelector("iframe[src*='recaptcha']")).thenReturn(mock(com.microsoft.playwright.ElementHandle.class));
        detector.checkCaptcha(page);
        assertThat(stops).containsExactly(StopReason.CAPTCHA);
    }

    @Test
    @DisplayName("body 文本含 '验证码' -> CAPTCHA")
    void captchaChineseTextTriggersCaptcha() {
        when(page.querySelector(org.mockito.ArgumentMatchers.anyString())).thenReturn(null);
        when(page.innerText("body")).thenReturn("这是一个验证码页面");
        detector.checkCaptcha(page);
        assertThat(stops).containsExactly(StopReason.CAPTCHA);
    }

    @Test
    @DisplayName("普通页面 -> 不触发任何停止")
    void normalPageNoTrigger() {
        when(page.querySelector(org.mockito.ArgumentMatchers.anyString())).thenReturn(null);
        when(page.innerText("body")).thenReturn("Just a regular list of products");
        detector.checkCaptcha(page);
        assertThat(stops).isEmpty();
    }

    @Test
    @DisplayName("page=null -> checkCaptcha 不抛异常、不触发停止")
    void nullPageSafe() {
        detector.checkCaptcha(null);
        assertThat(stops).isEmpty();
        verify(page, never()).querySelector(org.mockito.ArgumentMatchers.anyString());
    }
}