package com.visualspider.task.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.visualspider.shared.api.BusinessErrorCode;
import com.visualspider.task.domain.FieldDefinition;
import com.visualspider.task.domain.FieldSource;
import com.visualspider.task.domain.ListItemRule;
import com.visualspider.task.domain.ReadinessReport;
import com.visualspider.task.domain.ResultType;
import com.visualspider.task.domain.SelectorType;
import com.visualspider.task.domain.TaskDefinition;
import com.visualspider.task.domain.TaskMode;
import com.visualspider.task.domain.TrimPolicy;
import com.visualspider.task.domain.Viewport;
import com.visualspider.task.spi.LiveReadinessHook;
import com.visualspider.task.spi.LiveReadinessHook.LiveReadinessOutcome;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Live 实匹配校验 hook 集成（M4 spec §D10）。
 */
class TaskReadinessLiveHookTest {

    @Test
    @DisplayName("live hook 返回 LIST_ITEM_RULE_NO_MATCH → 阻止就绪")
    void liveNoMatchBlock() {
        LiveReadinessHook stub = (def, actorId) ->
                LiveReadinessOutcome.block(
                        List.of("LIST_ITEM_RULE_NO_MATCH"),
                        List.of("列表项规则匹配数 1，少于 2"));
        TaskReadinessImpl readiness = new TaskReadinessImpl(null, stub);
        TaskDefinition def = validListTask();
        ReadinessReport report = readiness.validate(def);
        assertThat(report.ready()).isFalse();
        assertThat(report.errors())
                .extracting(ReadinessReport.ReadinessError::code)
                .contains("LIST_ITEM_RULE_NO_MATCH");
    }

    @Test
    @DisplayName("live hook 返回 MULTIPLE_MATCH → 阻止就绪")
    void liveMultipleMatchBlock() {
        LiveReadinessHook stub = (def, actorId) ->
                LiveReadinessOutcome.block(
                        List.of("MULTIPLE_MATCH"),
                        List.of("字段 title 匹配 3 个元素"));
        TaskReadinessImpl readiness = new TaskReadinessImpl(null, stub);
        TaskDefinition def = validListTask();
        ReadinessReport report = readiness.validate(def);
        assertThat(report.ready()).isFalse();
        assertThat(report.errors())
                .extracting(ReadinessReport.ReadinessError::code)
                .contains("MULTIPLE_MATCH");
    }

    @Test
    @DisplayName("live hook ok → 不影响其他静态校验")
    void livePassThrough() {
        LiveReadinessHook stub = (def, actorId) -> LiveReadinessOutcome.ok();
        TaskReadinessImpl readiness = new TaskReadinessImpl(null, stub);
        TaskDefinition def = validListTask();
        ReadinessReport report = readiness.validate(def);
        assertThat(report.ready()).isTrue();
    }

    @Test
    @DisplayName("AlwaysPass 占位 hook 永 ok（SINGLE_PAGE 路径无需 live 检查）")
    void alwaysPassSinglePage() {
        TaskReadinessImpl readiness = new TaskReadinessImpl(null, new AlwaysPassLiveReadinessHook());
        TaskDefinition def = new TaskDefinition(2, new TaskMode.SinglePage(),
                "https://example.com", Viewport.DEFAULT, null, null, null, null,
                List.of(new FieldDefinition("title", FieldSource.VISIBLE_TEXT, "h1", null,
                        SelectorType.CSS, ResultType.TEXT, TrimPolicy.TRIM, null, true)));
        ReadinessReport report = readiness.validate(def);
        assertThat(report.ready()).isTrue();
    }

    @Test
    @DisplayName("MULTIPLE_MATCH 错误码常量在 BusinessErrorCode 中存在且 400")
    void multipleMatchCodeExists() {
        assertThat(BusinessErrorCode.MULTIPLE_MATCH.httpStatus()).isEqualTo(400);
    }

    @Test
    @DisplayName("LIST_ITEM_RULE_NO_MATCH 错误码常量在 BusinessErrorCode 中存在且 400")
    void listItemRuleNoMatchCodeExists() {
        assertThat(BusinessErrorCode.LIST_ITEM_RULE_NO_MATCH.httpStatus()).isEqualTo(400);
    }

    static TaskDefinition validListTask() {
        return new TaskDefinition(2, new TaskMode.List(),
                "https://example.com", Viewport.DEFAULT, null,
                new com.visualspider.task.domain.Limits(100, 500, Duration.ofMinutes(15)),
                new ListItemRule("ul > li", SelectorType.CSS),
                List.of(new com.visualspider.task.domain.UniqueKeyField("title")),
                List.of(new FieldDefinition("title", FieldSource.VISIBLE_TEXT, "h1", null,
                        SelectorType.CSS, ResultType.TEXT, TrimPolicy.TRIM, null, true)));
    }
}
