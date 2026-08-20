package com.visualspider.task.spi;

import com.visualspider.task.domain.TaskDefinition;
import java.util.List;

/**
 * Live 实匹配校验 hook（M4 spec §D10；M5 spec §D11 / §D12）。
 *
 * <p>在 {@code TaskReadiness.validateForRun} 阶段调用一次：实际打开预览态 DOM，
 * 检查：
 * <ul>
 *   <li>{@code listItemRule.selector} 命中数 ≥ 2</li>
 *   <li>每个字段在第一个 item 内匹配数 ≤ 1</li>
 *   <li>M5：{@code paginationRule.selector} 命中 ≥ 1（{@code PAGINATION_RULE_INVALID}）</li>
 *   <li>M5：{@code fieldKind=LIST_CONTENT_LINK} 字段在第一个 item 内命中 ≥ 1
 *       （{@code CONTENT_LINK_NO_MATCH}）</li>
 *   <li>M5：{@code fieldKind=CONTENT_VALUE} 字段在前 maxContent 条内容页内命中 ≥ 1
 *       （{@code CONTENT_FIELD_NO_MATCH}）</li>
 * </ul>
 *
 * <p>默认实现 {@link AlwaysPassLiveReadinessHook}（测试用）— 不弹错；
 * 生产 {@code visualbrowser} 模块的 Playwright lane 在 M4-6 提供真实实现。
 *
 * <p>该 hook 由 Spring 自动注入；无 bean 时 fallback 到 {@link AlwaysPassLiveReadinessHook}。
 */
public interface LiveReadinessHook {

    LiveReadinessOutcome check(TaskDefinition definition, long actorId);

    /**
     * M5 / spec §D12：扩展 check 路径 — 额外预览前 {@code maxContent} 条内容页
     * （默认 3），验证 {@code fieldKind=CONTENT_VALUE} 字段在内容页 DOM 上命中 ≥ 1。
     * 同 lane 串行 navigate，超时默认 60s（M6/M7 收口可能调整，慢站超时是已知未覆盖）。
     *
     * <p>默认实现退化为 {@link #check}（仅检查 list 阶段，不实际拉内容页）。
     */
    default LiveReadinessOutcome previewWithContent(TaskDefinition definition,
                                                   long actorId,
                                                   int maxContent) {
        return check(definition, actorId);
    }

    record LiveReadinessOutcome(boolean passed, List<String> blockingCodes, List<String> messages) {
        public static LiveReadinessOutcome ok() {
            return new LiveReadinessOutcome(true, List.of(), List.of());
        }
        public static LiveReadinessOutcome block(List<String> codes, List<String> messages) {
            return new LiveReadinessOutcome(false, List.copyOf(codes), List.copyOf(messages));
        }
    }
}
