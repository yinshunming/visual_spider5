package com.visualspider.run.spi;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * StopReason 停止原因枚举契约（M3 spec §D11 + M5-3 / spec §D16）。
 *
 * <p>前向兼容全集；M3 单页只发部分；其余（M4/M5 多页触发）枚举已占位；
 * M5-3 新增 4 项翻页主动停止原因（PAGINATION_* + DUPLICATE_PAGE）。
 */
class StopReasonTest {

    @Test
    @DisplayName("StopReason 含 16 个停止原因（M3 12 + M5-3 4）")
    void hasSixteenReasons() {
        assertThat(StopReason.values()).hasSize(16);
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
                StopReason.APP_INTERRUPTED,
                // M5-3 / spec §D16：翻页主动停止 + 重复页保护
                StopReason.PAGINATION_DISAPPEARED,
                StopReason.PAGINATION_DISABLED,
                StopReason.PAGINATION_NO_NEW_ITEMS,
                StopReason.DUPLICATE_PAGE);
        assertThat(Arrays.asList(StopReason.values())).containsExactlyInAnyOrderElementsOf(expected);
    }
}