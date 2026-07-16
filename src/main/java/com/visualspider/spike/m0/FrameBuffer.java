package com.visualspider.spike.m0;

/**
 * 帧缓冲：只保留最新待发送帧，新帧覆盖旧帧（丢旧），禁止无界排队。
 * 线程安全：screencast 回调线程 push，WebSocket 发送线程 drain。
 */
public final class FrameBuffer {
    private volatile byte[] latest;

    /** 推入新帧，覆盖之前的未发送帧（丢旧）。null 帧被忽略。 */
    public void push(byte[] frame) {
        if (frame != null) {
            latest = frame;
        }
    }

    /** 取最新帧并清空缓冲；返回 null 若无帧或已被取走。 */
    public byte[] drain() {
        byte[] f = latest;
        latest = null;
        return f;
    }

    /** 是否有未发送帧。 */
    public boolean hasFrame() {
        return latest != null;
    }
}
