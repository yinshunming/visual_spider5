package com.visualspider.spike.m0;

import java.util.function.Consumer;

/**
 * 基于 Playwright {@code Page.screenshot()} 受控频率轮询的帧生产者。
 *
 * <p>M0-2 实测 {@code Page.screencast()} 在 headless Chromium 上不推帧（onFrame 不回调），
 * 故回退为 screenshot 受控频率轮询；帧率由 intervalMs 控制（M0-2 用 100ms 约 10fps）。
 * M0-5 决策门将重新评估 screencast vs screenshot。
 */
public final class ScreenshotFrameProducer implements FrameProducer {
    private final PlaywrightControl control;
    private final long intervalMs;

    public ScreenshotFrameProducer(PlaywrightControl control, long intervalMs) {
        this.control = control;
        this.intervalMs = intervalMs;
    }

    @Override
    public FrameHandle start(Consumer<byte[]> frameConsumer) {
        Thread thread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    byte[] frame = control.screenshotViewport().join();
                    if (frame != null && frame.length > 0) {
                        frameConsumer.accept(frame);
                    }
                } catch (Exception e) {
                    // 单帧截图失败：跳过，下一轮重试
                }
                try {
                    Thread.sleep(intervalMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "screenshot-frame-producer");
        thread.setDaemon(true);
        thread.start();
        return () -> thread.interrupt();
    }
}
