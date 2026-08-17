package com.visualspider.visualbrowser;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 直接验证 {@link ScreencastFrameProducer} 在真实 Chromium 上推帧。
 *
 * <p>M0-2 实测：Playwright 1.61 的 {@code Page.screencast()} 在 headless Chromium 上 onFrame
 * 不回调（20s 无帧）。已回退到 {@link ScreenshotFrameProducer}。本测试 @Disabled 保留，
 * 待 M0-5 决策门重新评估 screencast（可能需 headed 模式或不同配置）时启用。
 */
@Disabled("screencast 在 headless Chromium 不推帧；M0-5 决策门重新评估时启用")
class ScreencastFrameProducerIT {

    @Test
    void producesJpegFrames() throws Exception {
        URL resource = getClass().getResource("/fixtures/dynamic.html");
        assertThat(resource).isNotNull();
        String fixtureUrl = resource.toURI().toString();

        try (BrowserLane lane = new BrowserLane()) {
            new PlaywrightControl(lane).navigate(fixtureUrl).join();
            FrameProducer producer = new ScreencastFrameProducer(lane);

            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<byte[]> firstFrame = new AtomicReference<>();
            try (FrameProducer.FrameHandle h = producer.start(frame -> {
                firstFrame.set(frame);
                latch.countDown();
            })) {
                assertThat(latch.await(20, TimeUnit.SECONDS))
                        .as("screencast 应在 20s 内推至少一帧").isTrue();
                assertThat(firstFrame.get()).isNotEmpty();
            }
        }
    }
}
