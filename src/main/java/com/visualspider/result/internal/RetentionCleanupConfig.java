package com.visualspider.result.internal;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 启用 Spring 调度（M3 spec §D14）。
 *
 * <p>{@code @EnableScheduling} 激活 {@code @Scheduled} 注解处理；
 * 单独的 {@code @Configuration} 类便于显式表达"result 模块触发调度"，避免把全局
 * 开关散落在 Application 主类。
 */
@Configuration
@EnableScheduling
public class RetentionCleanupConfig {
}