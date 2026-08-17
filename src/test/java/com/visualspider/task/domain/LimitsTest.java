package com.visualspider.task.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.visualspider.run.spi.RunLimits;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link Limits} 单元测试（M4 spec §D1）。
 */
class LimitsTest {

    @Test
    @DisplayName("合法范围构造通过")
    void validConstruction() {
        Limits l = new Limits(50, 1_000, Duration.ofMinutes(10));
        assertThat(l.pageLimit()).isEqualTo(50);
        assertThat(l.recordLimit()).isEqualTo(1_000);
        assertThat(l.durationLimit()).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    @DisplayName("globalDefault = RunLimits 硬上限")
    void globalDefaultMatchesRunLimits() {
        Limits def = Limits.globalDefault();
        assertThat(def.pageLimit()).isEqualTo(RunLimits.MAX_PAGES);
        assertThat(def.recordLimit()).isEqualTo(RunLimits.MAX_RECORDS);
        assertThat(def.durationLimit()).isEqualTo(RunLimits.MAX_DURATION);
    }

    @Test
    @DisplayName("pageLimit <= 0 → IllegalArgumentException")
    void pageLimitBelowZero() {
        assertThatThrownBy(() -> new Limits(0, 100, Duration.ofMinutes(10)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("pageLimit > MAX_PAGES → IllegalArgumentException")
    void pageLimitAboveHardLimit() {
        assertThatThrownBy(() -> new Limits(RunLimits.MAX_PAGES + 1, 100, Duration.ofMinutes(10)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("recordLimit > MAX_RECORDS → IllegalArgumentException")
    void recordLimitAboveHardLimit() {
        assertThatThrownBy(() -> new Limits(10, RunLimits.MAX_RECORDS + 1, Duration.ofMinutes(10)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("durationLimit 负值 → IllegalArgumentException")
    void durationLimitNegative() {
        assertThatThrownBy(() -> new Limits(10, 100, Duration.ofMinutes(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("durationLimit > MAX_DURATION → IllegalArgumentException")
    void durationLimitAboveHardLimit() {
        assertThatThrownBy(() -> new Limits(10, 100, RunLimits.MAX_DURATION.plusMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("durationLimit=null → IllegalArgumentException")
    void durationLimitNull() {
        assertThatThrownBy(() -> new Limits(10, 100, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
