package com.visualspider.shared.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 6 项 micrometer 指标注册（M6-6 / docs/specs/m6.md §D11）。
 *
 * <ul>
 *   <li>{@code runs.active} (gauge): RunLanePool 已借出数</li>
 *   <li>{@code runs.started} (counter): RunCoordinator start 计数</li>
 *   <li>{@code runs.completed} (counter): 终态计数</li>
 *   <li>{@code lane.borrowFailed} (counter): lane 借出失败</li>
 *   <li>{@code lane.crashRebuild} (counter): lane 崩溃重建计数</li>
 *   <li>{@code ssrf.blocked} (counter): SSRF 拦截计数（已由 SsrfRouteGuard 内部使用）</li>
 *   <li>{@code retention.deletedRows} (counter): 清理删除行数</li>
 * </ul>
 *
 * <p>Counter 通过 bean 暴露,业务代码可注入并调用 {@code counter.increment()};
 * Gauge 通过 lambda 读 pool.borrowedCount()。
 */
@Configuration
public class MetricsRegistrar {

    @Bean
    public MetricsHolders metricsHolders(MeterRegistry registry) {
        return new MetricsHolders(registry);
    }

    /** 集中管理所有指标 holder,业务模块通过依赖注入获取。 */
    public static final class MetricsHolders {
        private final Counter runsStarted;
        private final Counter runsCompleted;
        private final Counter laneBorrowFailed;
        private final Counter laneCrashRebuild;
        private final Counter retentionDeletedRows;

        public MetricsHolders(MeterRegistry registry) {
            this.runsStarted = Counter.builder("runs.started")
                    .description("采集运行启动计数").register(registry);
            this.runsCompleted = Counter.builder("runs.completed")
                    .description("采集运行终态计数").register(registry);
            this.laneBorrowFailed = Counter.builder("lane.borrowFailed")
                    .description("lane 借出失败计数").register(registry);
            this.laneCrashRebuild = Counter.builder("lane.crashRebuild")
                    .description("lane 崩溃重建计数").register(registry);
            this.retentionDeletedRows = Counter.builder("retention.deletedRows")
                    .description("retention cleanup 删除行数").register(registry);
        }

        public Counter runsStarted() { return runsStarted; }
        public Counter runsCompleted() { return runsCompleted; }
        public Counter laneBorrowFailed() { return laneBorrowFailed; }
        public Counter laneCrashRebuild() { return laneCrashRebuild; }
        public Counter retentionDeletedRows() { return retentionDeletedRows; }
    }

    /** 注册 runs.active gauge（需要已注入的 pool）。 */
    public static Gauge registerRunsActiveGauge(MeterRegistry registry,
                                                 java.util.function.Supplier<Number> supplier) {
        return Gauge.builder("runs.active", supplier::get)
                .description("运行 lane 已借出数")
                .register(registry);
    }
}
