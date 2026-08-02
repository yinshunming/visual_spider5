package com.visualspider.task.spi;

import com.visualspider.task.domain.TaskDefinition;
import java.util.List;

/**
 * Live 实匹配校验 hook（M4 spec §D10）。
 *
 * <p>在 {@code TaskReadiness.validateForRun} 阶段调用一次：实际打开预览态 DOM，
 * 检查：
 * <ul>
 *   <li>{@code listItemRule.selector} 命中数 ≥ 2</li>
 *   <li>每个字段在第一个 item 内匹配数 ≤ 1</li>
 * </ul>
 *
 * <p>默认实现 {@link AlwaysPassLiveReadinessHook}（测试用）— 不弹错；
 * 生产 {@code visualbrowser} 模块的 Playwright lane 在 M4-6 提供真实实现。
 *
 * <p>该 hook 由 Spring 自动注入；无 bean 时 fallback 到 {@link AlwaysPassLiveReadinessHook}。
 */
public interface LiveReadinessHook {

    LiveReadinessOutcome check(TaskDefinition definition, long actorId);

    record LiveReadinessOutcome(boolean passed, List<String> blockingCodes, List<String> messages) {
        public static LiveReadinessOutcome ok() {
            return new LiveReadinessOutcome(true, List.of(), List.of());
        }
        public static LiveReadinessOutcome block(List<String> codes, List<String> messages) {
            return new LiveReadinessOutcome(false, List.copyOf(codes), List.copyOf(messages));
        }
    }
}
