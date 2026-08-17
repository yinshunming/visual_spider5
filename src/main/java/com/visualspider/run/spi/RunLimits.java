package com.visualspider.run.spi;

import java.time.Duration;

/**
 * 采集运行硬限制常量（M4 spec §D1 / §D14）。
 *
 * <p>M3 写死；M6 入 {@code system_setting}（仅替换读路径）。
 * 当前在 M3 的 {@code RunDispatcher} 与 M4 的 {@code task.domain.Limits.globalDefault()}
 * 两处使用，作为单一事实源（single source of truth）。
 *
 * <p>模块定位：{@code run.spi} 是稳定的 SPI 包；{@code task} 模块
 * 可依赖本类而不会形成循环（{@code run} → {@code task} 单向）。
 */
public final class RunLimits {

    /** 单次运行最多页数（roadmap §9：200）。 */
    public static final int MAX_PAGES = 200;

    /** 单次运行最多原始记录数（roadmap §9：10,000）。 */
    public static final int MAX_RECORDS = 10_000;

    /** 单次运行最长时间（roadmap §9：30 分钟）。 */
    public static final Duration MAX_DURATION = Duration.ofMinutes(30);

    private RunLimits() {
        // 工具类，禁止实例化。
    }
}
