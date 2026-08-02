package com.visualspider.task.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * WaitPolicy record 契约（M3 spec §D6）：{@code extraWaitSeconds} 必为 0-5。
 */
class WaitPolicyTest {

    @Test
    @DisplayName("0 / 5 边界值合法")
    void boundaryValuesAccepted() {
        assertThat(new WaitPolicy(0).extraWaitSeconds()).isZero();
        assertThat(new WaitPolicy(5).extraWaitSeconds()).isEqualTo(5);
    }

    @Test
    @DisplayName("中间值合法")
    void middleValueAccepted() {
        assertThat(new WaitPolicy(3).extraWaitSeconds()).isEqualTo(3);
    }

    @Test
    @DisplayName("负数被拒绝")
    void negativeRejected() {
        assertThatThrownBy(() -> new WaitPolicy(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("0-5");
    }

    @Test
    @DisplayName("超过 5 被拒绝")
    void overFiveRejected() {
        assertThatThrownBy(() -> new WaitPolicy(6))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("0-5");
        assertThatThrownBy(() -> new WaitPolicy(99))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("0-5");
    }
}