package com.visualspider.task.internal;

import com.visualspider.task.domain.TaskDefinition;
import com.visualspider.task.spi.LiveReadinessHook;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * 测试 / 无 Playwright 环境占位 {@link LiveReadinessHook}：永远返回 ok。
 *
 * <p>{@code @ConditionalOnMissingBean(LiveReadinessHook.class)} 让真实 lane
 * 实现（visualbrowser 装配）自动覆盖本占位。
 */
@Component
@ConditionalOnMissingBean(LiveReadinessHook.class)
public class AlwaysPassLiveReadinessHook implements LiveReadinessHook {

    @Override
    public LiveReadinessOutcome check(TaskDefinition definition, long actorId) {
        return LiveReadinessOutcome.ok();
    }
}
