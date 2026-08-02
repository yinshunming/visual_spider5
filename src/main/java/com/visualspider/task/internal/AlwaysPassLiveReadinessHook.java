package com.visualspider.task.internal;

import com.visualspider.task.domain.TaskDefinition;
import com.visualspider.task.spi.LiveReadinessHook;
import org.springframework.stereotype.Component;

/**
 * 默认 {@link LiveReadinessHook}：永远返回 ok；M4-6 (#36) Playwright lane
 * 接入真实实现时通过 {@code @Primary} 覆盖本默认。
 */
@Component
public class AlwaysPassLiveReadinessHook implements LiveReadinessHook {

    @Override
    public LiveReadinessOutcome check(TaskDefinition definition, long actorId) {
        return LiveReadinessOutcome.ok();
    }
}
