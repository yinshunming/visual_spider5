package com.visualspider.shared.time;

import java.time.Instant;

/**
 * 时间抽象（spec §D3 / §D10）。
 *
 * <p>所有需要读取"当前时间"的组件（M2 配置会话生命周期、M3+ 任务运行生命周期、
 * 防抖保存等）都通过 {@link Clock} 获取，避免直接调用
 * {@code System.currentTimeMillis()} / {@code Instant.now()} 导致测试不可控。
 *
 * <p>生产实现使用 {@link SystemClock#INSTANCE}；测试通过构造
 * {@link MutableClock} 注入并显式推进时间。
 */
public interface Clock {

    /** 当前时刻（UTC）。 */
    Instant instant();

    /** 当前时刻的毫秒数（UTC epoch ms）；等价于 {@code instant().toEpochMilli()}。 */
    default long millis() {
        return instant().toEpochMilli();
    }
}