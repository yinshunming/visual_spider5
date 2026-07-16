package com.visualspider.spike.m0;

import java.util.function.Consumer;

/**
 * 帧生产者：可替换小函数，为 M0-5 的 screenshot 回退预留。
 *
 * <p>优先使用 screencast 实现；若 M0-5 度量发现跨平台/延迟不达标，替换为 screenshot 受控频率实现。
 * 新帧以 JPEG byte[] 通过 frameConsumer 推送（通常推入 {@link FrameBuffer}）。
 */
public interface FrameProducer {

    /** 启动帧生产；返回句柄用于停止。 */
    FrameHandle start(Consumer<byte[]> frameConsumer);

    /** 帧生产句柄，close 停止生产并释放资源。 */
    interface FrameHandle extends AutoCloseable {
        @Override
        void close();
    }
}
