package com.visualspider.shared.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Metrics 注册单测（spec §D11 / T1）。
 *
 * <p>验证 {@link MetricsRegistrar} 注册的 6 项指标名存在于 MeterRegistry 中。
 * 无 PG / 无 Playwright 依赖;pure Spring context 验证。
 */
class MetricsIT {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(
                    MetricsAutoConfiguration.class))
            .withUserConfiguration(TestConfig.class);

    @Configuration
    @Import(MetricsRegistrar.class)
    static class TestConfig {
        @Bean
        public io.micrometer.core.instrument.MeterRegistry meterRegistry() {
            return new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        }
    }

    @Test
    @DisplayName("6 项指标全部注册 (runs.started/completed/lane.borrowFailed/lane.crashRebuild/retention.deletedRows)")
    void allMetricsRegistered() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            var registry = ctx.getBean(io.micrometer.core.instrument.MeterRegistry.class);
            for (String name : new String[]{
                    "runs.started", "runs.completed",
                    "lane.borrowFailed", "lane.crashRebuild",
                    "retention.deletedRows"}) {
                assertThat(registry.find(name).counter())
                        .as("指标 " + name + " 应注册")
                        .isNotNull();
            }
        });
    }

    @Test
    @DisplayName("MetricsHolders.runsStarted().increment() 计数值变化")
    void countersIncrement() {
        runner.run(ctx -> {
            var holders = ctx.getBean(MetricsRegistrar.MetricsHolders.class);
            double before = holders.runsStarted().count();
            holders.runsStarted().increment();
            double after = holders.runsStarted().count();
            assertThat(after - before).isEqualTo(1.0);
        });
    }
}
