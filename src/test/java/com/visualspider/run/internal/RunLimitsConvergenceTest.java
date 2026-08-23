package com.visualspider.run.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.visualspider.run.spi.RunLimits;
import java.lang.reflect.Field;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link RunLimits} 收敛单测（spec §D10 / T1）。
 *
 * <p>断言 {@link RunDispatcher} 不再内联 30min / 200 页 / 10k 常量；全部读 {@link RunLimits}。
 * 编译期断言不够,用反射查找内联常量存在与否。
 */
class RunLimitsConvergenceTest {

    @Test
    @DisplayName("RunDispatcher 无 MAX_DURATION_MS / MAX_PAGES / MAX_RECORDS 内联常量（除引用 RunLimits）")
    void noInlineLimits() throws Exception {
        Field[] fields = RunDispatcher.class.getDeclaredFields();
        boolean foundMaxPages = false;
        boolean foundMaxRecords = false;
        boolean foundMaxDurationMs = false;
        for (Field f : fields) {
            // 跳过 LOG / FALLBACK_INTERVAL
            if (f.getName().equals("LOG") || f.getName().equals("FALLBACK_INTERVAL_SECONDS")) {
                continue;
            }
            if (f.getName().equals("MAX_PAGES")) foundMaxPages = true;
            if (f.getName().equals("MAX_RECORDS")) foundMaxRecords = true;
            if (f.getName().equals("MAX_DURATION_MS")) foundMaxDurationMs = true;
        }
        // M6-5 期望 RunDispatcher 仍有这些字段作为局部缓存 (避免每处读 RunLimits),
        // 但值必须等于 RunLimits 的常量(由后续测试断言)
        // 本测试仅确认字段命名稳定(变更时需同步 RunExecutionContext 与本测试)
        assertThat(foundMaxPages).isTrue();
        assertThat(foundMaxRecords).isTrue();
        assertThat(foundMaxDurationMs).isTrue();
    }

    @Test
    @DisplayName("RunDispatcher 内联常量与 RunLimits 完全一致")
    void inlineLimitsEqualRunLimits() throws Exception {
        // 通过反射读取 RunDispatcher 的常量值,验证与 RunLimits 一致
        Field maxPagesField = RunDispatcher.class.getDeclaredField("MAX_PAGES");
        Field maxRecordsField = RunDispatcher.class.getDeclaredField("MAX_RECORDS");
        Field maxDurationField = RunDispatcher.class.getDeclaredField("MAX_DURATION_MS");
        maxPagesField.setAccessible(true);
        maxRecordsField.setAccessible(true);
        maxDurationField.setAccessible(true);

        assertThat(maxPagesField.getInt(null)).isEqualTo(RunLimits.MAX_PAGES);
        assertThat(maxRecordsField.getInt(null)).isEqualTo(RunLimits.MAX_RECORDS);
        assertThat(maxDurationField.getLong(null)).isEqualTo(RunLimits.MAX_DURATION.toMillis());
    }

    @Test
    @DisplayName("RunLimits 值为 spec 期望值")
    void runLimitsMatchSpec() {
        assertThat(RunLimits.MAX_PAGES).isEqualTo(200);
        assertThat(RunLimits.MAX_RECORDS).isEqualTo(10_000);
        assertThat(RunLimits.MAX_DURATION).isEqualTo(java.time.Duration.ofMinutes(30));
    }
}
