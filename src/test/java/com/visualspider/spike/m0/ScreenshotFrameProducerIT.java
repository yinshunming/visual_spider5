package com.visualspider.spike.m0;

import org.junit.jupiter.api.Test;

import java.net.URL;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 {@link ScreenshotFrameProducer} 在真实 Chromium 上按受控频率持续推帧。
 * 需要 Chromium 已安装。
 */
class ScreenshotFrameProducerIT {

    @Test
    void producesJpegFrames() throws Exception {
        URL resource = getClass().getResource("/fixtures/dynamic.html");
        assertThat(resource).isNotNull();
        String fixtureUrl = resource.toURI().toString();

        try (BrowserLane lane = new BrowserLane()) {
            PlaywrightControl control = new PlaywrightControl(lane);
            control.navigate(fixtureUrl).join();
            FrameProducer producer = new ScreenshotFrameProducer(control, 100);

            CountDownLatch latch = new CountDownLatch(2);
            AtomicReference<byte[]> firstFrame = new AtomicReference<>();
            try (FrameProducer.FrameHandle h = producer.start(frame -> {
                firstFrame.compareAndSet(null, frame);
                latch.countDown();
            })) {
                assertThat(latch.await(15, TimeUnit.SECONDS))
                        .as("screenshot 帧生产应在 15s 内推至少 2 帧").isTrue();
                assertThat(firstFrame.get()).isNotEmpty();
            }
        }
    }
}
