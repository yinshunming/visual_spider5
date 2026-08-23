package com.visualspider.run.internal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * {@link SingleInstanceGuard} 的 Spring 装配（M6-2 / spec §D6 / ADR-0007）。
 *
 * <p>从 {@code spring.datasource.*} 读取连接信息，构造专用 JDBC 连接（不进
 * HikariCP 池）。测试可通过 {@code run.single-instance.enabled=false} 关闭，
 * 避免 IT 在不需要 advisory lock 语义的场景下被 guard 强制约束。
 */
@Configuration
public class SingleInstanceGuardConfig {

    @Bean
    @ConditionalOnProperty(
            name = "run.single-instance.enabled",
            havingValue = "true",
            matchIfMissing = true)
    public SingleInstanceGuard singleInstanceGuard(
            @Value("${spring.datasource.url}") String jdbcUrl,
            @Value("${spring.datasource.username:}") String username,
            @Value("${spring.datasource.password:}") String password) {
        return new SingleInstanceGuard(jdbcUrl, username, password);
    }
}
