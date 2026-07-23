package com.visualspider.visualbrowser.spi;

/**
 * lane 池 SPI（M2-1 #17 / ADR-0004）。
 *
 * <p>配置 lane 与运行 lane 各持独立池；M2 仅启用配置池，运行池由 M3 实现。
 * 池容量固定，不做配置化（M6）。
 */
public interface LanePool {

    /** 池容量（同时可借出的 lease 数）。 */
    int capacity();

    /** 当前已借出 lease 数。 */
    int borrowedCount();

    /**
     * 借出一个 lease；池满时抛 {@link com.visualspider.visualbrowser.internal.ConfigLaneFullException}。
     */
    Lease acquire(String sessionId);

    /** 归还 lease；重复归还 no-op。 */
    void release(Lease lease);
}
