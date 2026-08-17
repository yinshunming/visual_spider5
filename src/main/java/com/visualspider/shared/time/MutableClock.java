package com.visualspider.shared.time;

import java.time.Duration;
import java.time.Instant;

/**
 * 测试用 {@link Clock}：可显式推进时间。
 *
 * <p>不在生产装配，仅供单测和 fixture 验证 idle / max 边界与防抖保存。
 */
public final class MutableClock implements Clock {

    private Instant now;

    public MutableClock(Instant initial) {
        this.now = initial;
    }

    public MutableClock() {
        this(Instant.parse("2026-01-01T00:00:00Z"));
    }

    @Override
    public Instant instant() {
        return now;
    }

    /** 将时钟推进 {@code duration}，允许负值回退时间。 */
    public void advance(Duration duration) {
        now = now.plus(duration);
    }

    /** 推进 N 秒的便捷方法。 */
    public void advanceSeconds(long seconds) {
        advance(Duration.ofSeconds(seconds));
    }

    /** 直接设置当前时刻。 */
    public void set(Instant instant) {
        now = instant;
    }
}
