package com.visualspider.spike.m0;

import com.microsoft.playwright.Screencast;
import com.microsoft.playwright.ScreencastFrame;

import java.util.function.Consumer;

/**
 * 基于 Playwright {@code Page.screencast()} 的帧生产者：在 lane 线程上启动 screencast，
 * onFrame 回调把 JPEG bytes（{@link ScreencastFrame#data()}）推给 frameConsumer。
 *
 * <p>onFrame 回调只读取帧数据，不调用 Playwright API，因此可安全地在 Playwright driver
 * 回调线程上执行；frameConsumer 负责线程安全地收纳帧（如 {@link FrameBuffer}）。
 */
public final class ScreencastFrameProducer implements FrameProducer {

    private final BrowserLane lane;

    public ScreencastFrameProducer(BrowserLane lane) {
        this.lane = lane;
    }

    @Override
    public FrameHandle start(Consumer<byte[]> frameConsumer) {
        Screencast screencast = lane.submit(() -> lane.page().screencast()).join();
        lane.submit(() -> screencast.start(
                new Screencast.StartOptions()
                        .setQuality(80)
                        .setOnFrame((ScreencastFrame frame) -> frameConsumer.accept(frame.data()))
        )).join();
        return () -> lane.submit(() -> {
            screencast.stop();
            return null;
        }).join();
    }
}
