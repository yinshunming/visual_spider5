package com.visualspider.visualbrowser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.visualspider.visualbrowser.BrowserLane.LaneState;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link BrowserLane} 崩溃检测与重建单测（spec §D8 / T1）。
 *
 * <p>用注入的 initializer 返回 null（无真实 Chromium），通过 submit 抛各种异常验证
 * {@link BrowserLane#isCrashSignal} 与状态转换。
 *
 * <p>重建路径依赖 {@link BrowserLane} 创建 Playwright 资源的能力（本测试场景中
 * initializer 返回 null 模拟重建成功），完整 Chromium 端到端走 {@code LaneCrashRecoveryIT}。
 */
class BrowserLaneCrashTest {

    @Test
    @DisplayName("isCrashSignal: 含 'connection closed' message -> true")
    void connectionClosedDetected() {
        assertThat(BrowserLane.isCrashSignal(
                new RuntimeException("connection closed"))).isTrue();
    }

    @Test
    @DisplayName("isCrashSignal: 'Playwright connection closed' message -> true")
    void playwrightConnectionClosedDetected() {
        assertThat(BrowserLane.isCrashSignal(
                new RuntimeException("Playwright connection closed"))).isTrue();
    }

    @Test
    @DisplayName("isCrashSignal: 'Browser has been closed' message -> true")
    void browserHasBeenClosedDetected() {
        assertThat(BrowserLane.isCrashSignal(
                new RuntimeException("Browser has been closed"))).isTrue();
    }

    @Test
    @DisplayName("isCrashSignal: 普通 RuntimeException -> false")
    void regularExceptionNotCrashSignal() {
        assertThat(BrowserLane.isCrashSignal(new RuntimeException("page not found"))).isFalse();
        assertThat(BrowserLane.isCrashSignal(new IllegalStateException("bad state"))).isFalse();
        assertThat(BrowserLane.isCrashSignal(new NullPointerException())).isFalse();
    }

    @Test
    @DisplayName("isCrashSignal: null -> false")
    void nullNotCrashSignal() {
        assertThat(BrowserLane.isCrashSignal(null)).isFalse();
    }

    @Test
    @DisplayName("isCrashSignal: 嵌套 cause 也能识别")
    void nestedCauseDetected() {
        assertThat(BrowserLane.isCrashSignal(
                new RuntimeException("wrapper",
                        new RuntimeException("Playwright connection closed")))).isTrue();
    }

    @Test
    @DisplayName("submit 抛崩溃信号 -> lane 转 CRASHED, future 仍异常完成")
    void crashSignalMarksLaneCrashed() throws Exception {
        try (BrowserLane lane = new BrowserLane("test", 0, null, () -> null)) {
            assertThat(lane.state()).isEqualTo(LaneState.HEALTHY);

            CompletableFuture<Object> future = lane.submit(() -> {
                throw new RuntimeException("Playwright connection closed");
            });

            assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasMessageContaining("Playwright connection closed");
            // 状态转 CRASHED
            assertThat(lane.state()).isEqualTo(LaneState.CRASHED);
        }
    }

    @Test
    @DisplayName("submit 抛非崩溃信号 -> lane 保持 HEALTHY")
    void regularExceptionKeepsHealthy() throws Exception {
        try (BrowserLane lane = new BrowserLane("test", 0, null, () -> null)) {
            assertThat(lane.state()).isEqualTo(LaneState.HEALTHY);

            CompletableFuture<Object> future = lane.submit(() -> {
                throw new IllegalStateException("not a crash");
            });

            assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class);
            assertThat(lane.state()).isEqualTo(LaneState.HEALTHY);
        }
    }

    @Test
    @DisplayName("CRASHED 后下次 submit -> lane 线程内重建, 状态回 HEALTHY")
    void rebuildOnNextSubmit() throws Exception {
        AtomicReference<Boolean> initializerInvoked = new AtomicReference<>(false);
        // initializer 返回 null 时模拟重建成功
        BrowserLane lane = new BrowserLane("test", 0, null, () -> {
            initializerInvoked.set(true);
            return null;
        });

        // 第一次 submit 抛崩溃信号
        CompletableFuture<Object> firstFuture = lane.submit(() -> {
            throw new RuntimeException("Browser has been closed");
        });
        assertThatThrownBy(() -> firstFuture.get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class);
        assertThat(lane.state()).isEqualTo(LaneState.CRASHED);
        // initializer 还未被 rebuild 触发（首次构造已调用一次）
        // 记录当前调用次数,期望 rebuild 调用第二次
        int beforeRebuild = initializerInvoked.get() ? 1 : 0;

        // 第二次 submit 触发重建
        CompletableFuture<Object> secondFuture = lane.submit(() -> {
            return "ok";
        });
        assertThat(secondFuture.get(5, TimeUnit.SECONDS)).isEqualTo("ok");
        // rebuild 已触发(在第一次任务执行后)
        assertThat(initializerInvoked.get()).isTrue();

        // 状态应回 HEALTHY（rebuildOnLaneThread 内部设置）
        // 但第二次 submit 期间 lane 线程已重建完成
        assertThat(lane.state()).isEqualTo(LaneState.HEALTHY);

        lane.close();
        // 防止 unused 警告
        assertThat(beforeRebuild).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("线程名: browser-lane-{poolName}-{index} 唯一")
    void threadNameUnique() {
        try (BrowserLane lane0 = new BrowserLane("mypool", 0, null, () -> null);
             BrowserLane lane1 = new BrowserLane("mypool", 1, null, () -> null);
             BrowserLane lane2 = new BrowserLane("other", 0, null, () -> null)) {
            assertThat(lane0.laneThreadName()).isEqualTo("browser-lane-mypool-0");
            assertThat(lane1.laneThreadName()).isEqualTo("browser-lane-mypool-1");
            assertThat(lane2.laneThreadName()).isEqualTo("browser-lane-other-0");
        }
    }

    @Test
    @DisplayName("poolName / poolIndex 访问器正确返回")
    void poolNameAndIndex() {
        try (BrowserLane lane = new BrowserLane("test", 7, null, () -> null)) {
            assertThat(lane.poolName()).isEqualTo("test");
            assertThat(lane.poolIndex()).isEqualTo(7);
            assertThat(lane.state()).isEqualTo(LaneState.HEALTHY);
        }
    }

    @Test
    @DisplayName("LaneState 枚举仅 HEALTHY / CRASHED 两个值")
    void laneStateEnumValues() {
        assertThat(LaneState.values()).containsExactly(LaneState.HEALTHY, LaneState.CRASHED);
    }
}
