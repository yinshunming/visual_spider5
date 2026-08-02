package com.visualspider.task.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * SelectorType enum 契约（M3 spec §D6）：CSS / XPATH 两值。
 */
class SelectorTypeTest {

    @Test
    @DisplayName("SelectorType 含 CSS / XPATH 两个值")
    void containsCssAndXpath() {
        assertThat(SelectorType.values()).hasSize(2);
        assertThat(SelectorType.CSS.name()).isEqualTo("CSS");
        assertThat(SelectorType.XPATH.name()).isEqualTo("XPATH");
    }

    @Test
    @DisplayName("valueOf 字符串往返")
    void valueOfRoundTrip() {
        assertThat(SelectorType.valueOf("CSS")).isEqualTo(SelectorType.CSS);
        assertThat(SelectorType.valueOf("XPATH")).isEqualTo(SelectorType.XPATH);
    }
}