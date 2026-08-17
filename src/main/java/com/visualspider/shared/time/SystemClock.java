package com.visualspider.shared.time;

import java.time.Instant;

/**
 * 生产环境默认 {@link Clock}：直接读取系统 UTC 时间。
 *
 * <p>整个 JVM 共享一个实例 {@link #INSTANCE}，无内部状态。
 */
public final class SystemClock implements Clock {

    /** JVM 全局共享实例。 */
    public static final SystemClock INSTANCE = new SystemClock();

    @Override
    public Instant instant() {
        return Instant.now();
    }
}