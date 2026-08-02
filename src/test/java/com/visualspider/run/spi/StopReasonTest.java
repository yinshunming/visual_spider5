package com.visualspider.run.spi;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * StopReason 停止原因枚举契约（M3 spec §D11）。
 *
 * <p>前向兼容全集；M3 单页只发部分；其余（M4/M5 多页触发）枚举已占位。
 */
class StopReasonTest {

    @Test
    @DisplayName("StopReason 含 12 个停止原因（全集前向兼容）")
    void hasTwelveReasons() {
        assertThat(StopReason.values()).hasSize(12);
    }

    @Test
    @DisplayName("StopReason 含全部契约原因")
    void containsAllContractedReasons() {
        List<StopReason> expected = Arrays.asList(
                StopReason.COMPLETED,
                StopReason.USER_CANCEL,
                StopReason.ENTRY_FAILED,
                StopReason.BROWSER_START_FAILED,
                StopReason.PAGE_RETRY_EXHAUSTED,
                StopReason.PAGE_LIMIT,
                StopReason.RECORD_LIMIT,
                StopReason.TIME_LIMIT,
                StopReason.HTTP_429,
                StopReason.HTTP_403,
                StopReason.CAPTCHA,
                StopReason.APP_INTERRUPTED);
        assertThat(Arrays.asList(StopReason.values())).containsExactlyInAnyOrderElementsOf(expected);
    }
}