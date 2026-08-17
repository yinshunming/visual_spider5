package com.visualspider.run.spi;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * RunState 状态枚举契约（M3 spec §D10）。
 *
 * <p>7 态全部建齐；{@code PARTIAL_SUCCESS} M3 不产生，留位 M4 多页混合成功/失败。
 */
class RunStateTest {

    @Test
    @DisplayName("RunState 含 7 个状态值")
    void hasSevenStates() {
        assertThat(RunState.values()).hasSize(7);
    }

    @Test
    @DisplayName("RunState 含全部契约状态")
    void containsAllContractedStates() {
        List<RunState> expected = Arrays.asList(
                RunState.WAITING,
                RunState.RUNNING,
                RunState.SUCCESS,
                RunState.PARTIAL_SUCCESS,
                RunState.FAILED,
                RunState.CANCELLED,
                RunState.INTERRUPTED);
        assertThat(Arrays.asList(RunState.values())).containsExactlyInAnyOrderElementsOf(expected);
    }
}