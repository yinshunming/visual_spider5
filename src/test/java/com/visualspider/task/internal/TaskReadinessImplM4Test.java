package com.visualspider.task.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.visualspider.shared.api.BusinessErrorCode;
import com.visualspider.task.domain.FieldDefinition;
import com.visualspider.task.domain.FieldSource;
import com.visualspider.task.domain.Limits;
import com.visualspider.task.domain.ListItemRule;
import com.visualspider.task.domain.ReadinessReport;
import com.visualspider.task.domain.ReadinessReport.ReadinessError;
import com.visualspider.task.domain.ResultType;
import com.visualspider.task.domain.SelectorType;
import com.visualspider.task.domain.TaskDefinition;
import com.visualspider.task.domain.TaskMode;
import com.visualspider.task.domain.TrimPolicy;
import com.visualspider.task.domain.Viewport;
import com.visualspider.task.domain.WaitPolicy;
import com.visualspider.task.spi.LiveReadinessHook;
import com.visualspider.task.spi.LiveReadinessHook.LiveReadinessOutcome;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * M4 列表模式实匹配校验单元测试（#33 / spec §D4 / §D10）。
 *
 * <p>用 fake {@link LiveReadinessHook} 注入 {@link TaskReadinessImpl}，断言
 * {@code LIST_ITEM_RULE_NO_MATCH} 与 {@code MULTIPLE_MATCH} 阻塞码被映射到
 * {@link BusinessErrorCode}，且其它诊断可并存。
 */
class TaskReadinessImplM4Test {

    @Test
    @DisplayName("LIST 模式：LiveReadinessHook 返 LIST_ITEM_RULE_NO_MATCH -> LIST_ITEM_RULE_NO_MATCH 错误码")
    void listItemRuleNoMatchBlocksReadiness() {
        TaskReadinessImpl readiness = new TaskReadinessImpl(null,
                new StubHook(new LiveReadinessOutcome(false,
                        List.of("LIST_ITEM_RULE_NO_MATCH"),
                        List.of("列表项规则匹配数少于 2"))));
        TaskDefinition def = listTaskWithFields("tbody > tr", List.of(textField("title", "a.title")));

        ReadinessReport report = readiness.validate(def);

        assertThat(report.ready()).isFalse();
        assertThat(report.errors())
                .extracting(ReadinessError::code)
                .contains(BusinessErrorCode.LIST_ITEM_RULE_NO_MATCH.code());
    }

    @Test
    @DisplayName("LIST 模式：LiveReadinessHook 返 MULTIPLE_MATCH -> MULTIPLE_MATCH 错误码 + fieldPath 提示")
    void multipleMatchBlocksReadiness() {
        TaskReadinessImpl readiness = new TaskReadinessImpl(null,
                new StubHook(new LiveReadinessOutcome(false,
                        List.of("MULTIPLE_MATCH"),
                        List.of("fields[0].selector 匹配 3 个元素"))));
        TaskDefinition def = listTaskWithFields("tbody > tr", List.of(textField("title", "a.title")));

        ReadinessReport report = readiness.validate(def);

        assertThat(report.ready()).isFalse();
        assertThat(report.errors())
                .extracting(ReadinessError::code)
                .contains(BusinessErrorCode.MULTIPLE_MATCH.code());
    }

    @Test
    @DisplayName("LIST 模式：LiveReadinessHook ok（无重复结构 / 无多匹配） -> READY 不被 hook 阻塞")
    void liveHookOkKeepsReadiness() {
        TaskReadinessImpl readiness = new TaskReadinessImpl(null,
                new StubHook(LiveReadinessOutcome.ok()));
        TaskDefinition def = listTaskWithFields("tbody > tr", List.of(textField("title", "a.title")));

        ReadinessReport report = readiness.validate(def);

        assertThat(report.ready()).isTrue();
    }

    @Test
    @DisplayName("LIST 模式：两个阻塞码并存 -> errors 同时含 LIST_ITEM_RULE_NO_MATCH + MULTIPLE_MATCH")
    void bothBlockingCodesAccumulate() {
        TaskReadinessImpl readiness = new TaskReadinessImpl(null,
                new StubHook(new LiveReadinessOutcome(false,
                        List.of("LIST_ITEM_RULE_NO_MATCH", "MULTIPLE_MATCH"),
                        List.of("列表项规则匹配数少于 2", "fields[0].selector 匹配 2 个元素"))));
        TaskDefinition def = listTaskWithFields("tbody > tr", List.of(textField("title", "a.title")));

        ReadinessReport report = readiness.validate(def);

        assertThat(report.ready()).isFalse();
        assertThat(report.errors())
                .extracting(ReadinessError::code)
                .contains(BusinessErrorCode.LIST_ITEM_RULE_NO_MATCH.code(),
                        BusinessErrorCode.MULTIPLE_MATCH.code());
    }

    @Test
    @DisplayName("SINGLE_PAGE 模式：LiveReadinessHook 即使返阻塞码 -> 也被忽略（只 LIST 模式触发 live hook）")
    void singlePageIgnoresLiveHook() {
        TaskReadinessImpl readiness = new TaskReadinessImpl(null,
                new StubHook(new LiveReadinessOutcome(false,
                        List.of("LIST_ITEM_RULE_NO_MATCH"),
                        List.of("ignored"))));
        TaskDefinition def = singlePageTask(List.of(textField("title", "a.title")));

        ReadinessReport report = readiness.validate(def);

        assertThat(report.ready()).isTrue();
    }

    // ---------- helpers ----------

    private static FieldDefinition textField(String name, String selector) {
        return new FieldDefinition(name, FieldSource.VISIBLE_TEXT,
                selector, null, SelectorType.CSS, ResultType.TEXT,
                TrimPolicy.TRIM, null, true);
    }

    private static TaskDefinition listTaskWithFields(String listSelector, List<FieldDefinition> fields) {
        return new TaskDefinition(
                3, new TaskMode.List(), "https://example.com/list",
                Viewport.DEFAULT, new WaitPolicy(0),
                new Limits(200, 10_000, Duration.ofMinutes(30)),
                new ListItemRule(listSelector, SelectorType.CSS),
                List.of(), null,
                fields);
    }

    private static TaskDefinition singlePageTask(List<FieldDefinition> fields) {
        return new TaskDefinition(
                3, new TaskMode.SinglePage(), "https://example.com",
                Viewport.DEFAULT, new WaitPolicy(0),
                new Limits(200, 10_000, Duration.ofMinutes(30)),
                null, null, null,
                fields);
    }

    /** 固定返回指定 outcome 的测试 stub。 */
    private static final class StubHook implements LiveReadinessHook {
        private final LiveReadinessOutcome outcome;
        StubHook(LiveReadinessOutcome outcome) { this.outcome = outcome; }
        @Override
        public LiveReadinessOutcome check(TaskDefinition definition, long actorId) {
            return outcome;
        }
    }
}