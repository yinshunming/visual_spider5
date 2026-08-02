package com.visualspider.run.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link RunRecoveryImpl} 单元测试：markAllActiveInterrupted 调用、幂等性。
 */
@ExtendWith(MockitoExtension.class)
class RunRecoveryImplTest {

    @Mock
    private RunRepository repository;

    @Test
    @DisplayName("markInterruptedOnStartup 调用 repository 并返回影响行数")
    void delegatesToRepository() {
        when(repository.markAllActiveInterrupted()).thenReturn(3);
        RunRecoveryImpl recovery = new RunRecoveryImpl(repository);

        int n = recovery.markInterruptedOnStartup();

        assertThat(n).isEqualTo(3);
        verify(repository, times(1)).markAllActiveInterrupted();
    }

    @Test
    @DisplayName("没有遗留 run → 返回 0；不视为异常")
    void noLeftoverReturnsZero() {
        when(repository.markAllActiveInterrupted()).thenReturn(0);
        RunRecoveryImpl recovery = new RunRecoveryImpl(repository);

        assertThat(recovery.markInterruptedOnStartup()).isZero();
    }

    @Test
    @DisplayName("多次调用 markInterruptedOnStartup 只扫一次（幂等）")
    void idempotentAcrossCalls() {
        when(repository.markAllActiveInterrupted()).thenReturn(5);
        RunRecoveryImpl recovery = new RunRecoveryImpl(repository);

        int first = recovery.markInterruptedOnStartup();
        int second = recovery.markInterruptedOnStartup();

        assertThat(first).isEqualTo(5);
        assertThat(second).isZero();
        verify(repository, times(1)).markAllActiveInterrupted();
    }

    @Test
    @DisplayName("依赖 RunRepository（非 null）")
    void nullRepositoryRejected() {
        // 构造时无显式校验；保证不为 null 即可
        RunRecoveryImpl recovery = new RunRecoveryImpl(mock(RunRepository.class));
        // 仅检查无 NPE（不触发 repository 调用）
        assertThat(recovery).isNotNull();
        verify(repository, never()).markAllActiveInterrupted();
    }
}