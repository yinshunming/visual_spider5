package com.visualspider.spike.m0;

/**
 * 输入命令单调序号守卫：每个会话维护已处理的最大序号，拒绝过期或重复序号。
 * 线程安全（WebSocket 线程与潜在重入均可能调用 accept）。
 */
public final class InputSequencer {
    private long lastSequence = 0;

    /**
     * 尝试接受序号。
     *
     * @return true 若 sequence 严格大于上次接受的序号（非过期/非重复）并更新；false 则拒绝
     */
    public synchronized boolean accept(long sequence) {
        if (sequence <= lastSequence) {
            return false;
        }
        lastSequence = sequence;
        return true;
    }

    public synchronized long lastSequence() {
        return lastSequence;
    }
}
