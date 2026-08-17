package com.visualspider.task.internal;

import com.visualspider.task.spi.TaskRepository;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * task 模块 Spring 装配（M4-1 #31）。
 *
 * <p>注册 {@link TaskSchemaUpgrader} 为 {@code ApplicationRunner}，
 * 启动期静默将 V1 SP 任务升级到 V2、V1 LIST 任务缺 {@code listItemRule}
 * 降为 DRAFT（spec §D2）。
 *
 * <p>{@code @Order(HIGHEST_PRECEDENCE + 20)}：晚于
 * {@code run.RunRecoveryImpl}（{@code @Order(HIGHEST + 10)}），早于
 * {@code run.RunDispatcher.onContextRefreshed}（{@code ContextRefreshedEvent}）。
 *
 * <p>{@code @ConditionalOnProperty(name = "task.upgrader.enabled", havingValue = "true", matchIfMissing = true)}：
 * 测试可用 {@code -Dtask.upgrader.enabled=false} 关闭（例如无 PG 单测）。
 */
@Configuration
public class TaskModuleConfig {

    @Bean
    public TaskSchemaUpgrader taskSchemaUpgrader(NamedParameterJdbcTemplate jdbc,
                                                 TaskRepository repository) {
        return new TaskSchemaUpgrader(jdbc, repository);
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 20)
    @ConditionalOnProperty(name = "task.upgrader.enabled", havingValue = "true", matchIfMissing = true)
    public ApplicationRunner taskSchemaUpgraderRunner(TaskSchemaUpgrader upgrader) {
        return args -> upgrader.run(args);
    }
}
