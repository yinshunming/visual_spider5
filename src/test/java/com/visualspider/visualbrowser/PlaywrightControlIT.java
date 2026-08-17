package com.visualspider.visualbrowser;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 Chromium 集成测试：验证 {@link PlaywrightControl} 能导航、按坐标点击、输入中文、滚动、
 * 前进/后退/刷新、整页截图（存内存）。
 *
 * <p>需要 Chromium 已安装（{@code mvn exec:java -Dexec.args="install chromium"}）。
 * 每个测试独立 BrowserLane + 独立非持久化 BrowserContext，遵守单线程亲和性。
 * 不用 mock 掩盖真实浏览器行为；等待用 Playwright 自动等待，避免固定 sleep 导致 flaky。
 */
class PlaywrightControlIT {

    private static final long TIMEOUT_SECONDS = 15;

    private BrowserLane lane;
    private PlaywrightControl control;
    private String fixtureUrl;

    @BeforeEach
    void setUp() throws Exception {
        URL resource = getClass().getResource("/fixtures/static.html");
        assertThat(resource).as("fixture /fixtures/static.html 必须在 classpath").isNotNull();
        fixtureUrl = resource.toURI().toString();
        lane = new BrowserLane();
        control = new PlaywrightControl(lane);
    }

    @AfterEach
    void tearDown() {
        if (lane != null) {
            lane.close();
        }
    }

    @Test
    void navigatesAndCapturesFullPageScreenshot() throws Exception {
        control.navigate(fixtureUrl).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(control.currentUrl().get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).startsWith("file:");

        byte[] shot = control.screenshotFullPage().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(shot).isNotEmpty();
        assertThat(shot[0]).as("JPEG SOI marker").isEqualTo((byte) 0xFF);
    }

    @Test
    void clicksByCoordinateAndTypesChinese() throws Exception {
        control.navigate(fixtureUrl).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        double[] inputCenter = control.elementCenter("#input").get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        control.click((int) inputCenter[0], (int) inputCenter[1]).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        control.type("你好世界").get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        double[] btnCenter = control.elementCenter("#submit-btn").get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        control.click((int) btnCenter[0], (int) btnCenter[1]).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        control.waitForSelector("#output:not(:empty)").get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        String output = control.textContent("#output").get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(output).contains("你好世界");
    }

    @Test
    void wheelScrollsVertically() throws Exception {
        control.navigate(fixtureUrl).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        long before = control.scrollY().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        control.wheel(0, 600).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        control.waitForScrollPast(before).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        long after = control.scrollY().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(after).isGreaterThan(before);
    }

    @Test
    void backForwardReloadNavigation() throws Exception {
        control.navigate(fixtureUrl).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        control.navigate("about:blank").get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(control.currentUrl().get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).contains("about:blank");

        control.goBack().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(control.currentUrl().get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).startsWith("file:");

        control.goForward().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(control.currentUrl().get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).contains("about:blank");

        control.goBack().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        control.reload().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(control.currentUrl().get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).startsWith("file:");
    }
}
