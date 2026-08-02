package com.visualspider.task.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.visualspider.shared.api.BusinessErrorCode;
import com.visualspider.task.domain.FieldDefinition;
import com.visualspider.task.domain.FieldSource;
import com.visualspider.task.domain.ReadinessReport;
import com.visualspider.task.domain.ResultType;
import com.visualspider.task.domain.SelectorType;
import com.visualspider.task.domain.TaskDefinition;
import com.visualspider.task.domain.TaskMode;
import com.visualspider.task.domain.TrimPolicy;
import com.visualspider.task.domain.Viewport;
import com.visualspider.task.domain.WaitPolicy;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * TaskReadinessImpl 单元测试。
 *
 * <p>覆盖：合法定义 → ready=true；schemaVersion / startUrl / viewport / field name 的每个错误码路径。
 * M3 扩展：waitPolicy / selectorType 校验（M3 spec §D6）。
 */
class TaskReadinessImplTest {

    private final TaskReadinessImpl readiness = new TaskReadinessImpl();

    @Test
    @DisplayName("合法定义返回 ready=true 且 errors 为空")
    void validDefinition() {
        TaskDefinition def = singlePage();
        ReadinessReport report = readiness.validate(def);
        assertThat(report.ready()).isTrue();
        assertThat(report.errors()).isEmpty();
    }

    @Test
    @DisplayName("schemaVersion != 1 → TASK_UNSUPPORTED_SCHEMA")
    void schemaVersionUnsupported() {
        TaskDefinition def = new TaskDefinition(2, new TaskMode.SinglePage(),
                "https://example.com", Viewport.DEFAULT, null,
                List.of(new FieldDefinition("title", FieldSource.VISIBLE_TEXT, "h1", null,
                        SelectorType.CSS, ResultType.TEXT, TrimPolicy.TRIM, null, true)));
        ReadinessReport report = readiness.validate(def);
        assertThat(report.ready()).isFalse();
        assertThat(report.errors())
                .extracting(ReadinessReport.ReadinessError::code)
                .contains("TASK_UNSUPPORTED_SCHEMA");
    }

    @Test
    @DisplayName("startUrl 非 http(s) → TASK_INVALID_URL")
    void startUrlInvalidScheme() {
        TaskDefinition def = new TaskDefinition(1, new TaskMode.SinglePage(),
                "ftp://example.com", Viewport.DEFAULT, null,
                List.of(new FieldDefinition("title", FieldSource.VISIBLE_TEXT, "h1", null,
                        SelectorType.CSS, ResultType.TEXT, TrimPolicy.TRIM, null, true)));
        ReadinessReport report = readiness.validate(def);
        assertThat(report.ready()).isFalse();
        assertThat(report.errors())
                .extracting(ReadinessReport.ReadinessError::code)
                .contains("TASK_INVALID_URL");
    }

    @Test
    @DisplayName("startUrl 缺 host → TASK_INVALID_URL")
    void startUrlMissingHost() {
        TaskDefinition def = new TaskDefinition(1, new TaskMode.SinglePage(),
                "https://", Viewport.DEFAULT, null,
                List.of(new FieldDefinition("title", FieldSource.VISIBLE_TEXT, "h1", null,
                        SelectorType.CSS, ResultType.TEXT, TrimPolicy.TRIM, null, true)));
        ReadinessReport report = readiness.validate(def);
        assertThat(report.ready()).isFalse();
        assertThat(report.errors())
                .extracting(ReadinessReport.ReadinessError::code)
                .contains("TASK_INVALID_URL");
    }

    @Test
    @DisplayName("startUrl 为空 → TASK_INVALID_URL")
    void startUrlBlank() {
        TaskDefinition def = new TaskDefinition(1, new TaskMode.SinglePage(),
                "", Viewport.DEFAULT, null,
                List.of(new FieldDefinition("title", FieldSource.VISIBLE_TEXT, "h1", null,
                        SelectorType.CSS, ResultType.TEXT, TrimPolicy.TRIM, null, true)));
        ReadinessReport report = readiness.validate(def);
        assertThat(report.ready()).isFalse();
        assertThat(report.errors())
                .extracting(ReadinessReport.ReadinessError::code)
                .contains("TASK_INVALID_URL");
    }

    @Test
    @DisplayName("viewport 非 1280x720 → TASK_INVALID_VIEWPORT")
    void viewportInvalid() {
        TaskDefinition def = new TaskDefinition(1, new TaskMode.SinglePage(),
                "https://example.com", new Viewport(800, 600), null,
                List.of(new FieldDefinition("title", FieldSource.VISIBLE_TEXT, "h1", null,
                        SelectorType.CSS, ResultType.TEXT, TrimPolicy.TRIM, null, true)));
        ReadinessReport report = readiness.validate(def);
        assertThat(report.ready()).isFalse();
        assertThat(report.errors())
                .extracting(ReadinessReport.ReadinessError::code)
                .contains("TASK_INVALID_VIEWPORT");
    }

    @Test
    @DisplayName("field name 重复 → TASK_DUPLICATE_FIELD")
    void fieldNameDuplicate() {
        FieldDefinition f1 = new FieldDefinition("title", FieldSource.VISIBLE_TEXT, "h1", null,
                SelectorType.CSS, ResultType.TEXT, TrimPolicy.TRIM, null, true);
        FieldDefinition f2 = new FieldDefinition("title", FieldSource.VISIBLE_TEXT, ".body", null,
                SelectorType.CSS, ResultType.TEXT, TrimPolicy.TRIM, null, false);
        TaskDefinition def = new TaskDefinition(1, new TaskMode.SinglePage(),
                "https://example.com", Viewport.DEFAULT, null, List.of(f1, f2));
        ReadinessReport report = readiness.validate(def);
        assertThat(report.ready()).isFalse();
        assertThat(report.errors())
                .extracting(ReadinessReport.ReadinessError::code)
                .contains("TASK_DUPLICATE_FIELD");
    }

    @Test
    @DisplayName("field name 为空 → TASK_INVALID_FIELD_NAME")
    void fieldNameBlank() {
        FieldDefinition f = new FieldDefinition("", FieldSource.VISIBLE_TEXT, "h1", null,
                SelectorType.CSS, ResultType.TEXT, TrimPolicy.TRIM, null, true);
        TaskDefinition def = new TaskDefinition(1, new TaskMode.SinglePage(),
                "https://example.com", Viewport.DEFAULT, null, List.of(f));
        ReadinessReport report = readiness.validate(def);
        assertThat(report.ready()).isFalse();
        assertThat(report.errors())
                .extracting(ReadinessReport.ReadinessError::code)
                .contains("TASK_INVALID_FIELD_NAME");
    }

    @Test
    @DisplayName("mode=LIST 允许创建任务；空字段由 TASK_NO_FIELDS 报错（M2）")
    void listModeAcceptable() {
        TaskDefinition def = new TaskDefinition(1, new TaskMode.List(),
                "https://example.com", Viewport.DEFAULT, null,
                List.of(new FieldDefinition("title", FieldSource.VISIBLE_TEXT, "h1", null,
                        SelectorType.CSS, ResultType.TEXT, TrimPolicy.TRIM, null, true)));
        ReadinessReport report = readiness.validate(def);
        assertThat(report.ready()).isTrue();
    }

    @Test
    @DisplayName("validateForRun 当前 stub 返回 success（M3 替换为真实预运行校验）")
    void validateForRunStub() {
        ReadinessReport report = readiness.validateForRun(1L, null);
        assertThat(report.ready()).isTrue();
    }

    // ---------- M3 扩展：waitPolicy / selectorType 校验（spec §D6）----------

    @Test
    @DisplayName("waitPolicy=null 被默认值填充（WaitPolicy(0)）")
    void waitPolicyDefaultsWhenNull() {
        TaskDefinition def = new TaskDefinition(1, new TaskMode.SinglePage(),
                "https://example.com", Viewport.DEFAULT, null,
                List.of(new FieldDefinition("title", FieldSource.VISIBLE_TEXT, "h1", null,
                        SelectorType.CSS, ResultType.TEXT, TrimPolicy.TRIM, null, true)));
        assertThat(def.waitPolicy()).isNotNull();
        assertThat(def.waitPolicy().extraWaitSeconds()).isZero();
    }

    @Test
    @DisplayName("waitPolicy 合法（WaitPolicy(3)）→ ready=true")
    void waitPolicyValid() {
        TaskDefinition def = new TaskDefinition(1, new TaskMode.SinglePage(),
                "https://example.com", Viewport.DEFAULT, new WaitPolicy(3),
                List.of(new FieldDefinition("title", FieldSource.VISIBLE_TEXT, "h1", null,
                        SelectorType.CSS, ResultType.TEXT, TrimPolicy.TRIM, null, true)));
        ReadinessReport report = readiness.validate(def);
        assertThat(report.ready()).isTrue();
    }

    @Test
    @DisplayName("WaitPolicy 构造越界 → 抛 IllegalArgumentException（M3 spec §D6）")
    void waitPolicyRejectsOutOfRangeAtConstruction() {
        assertThatThrownBy(() -> new WaitPolicy(6))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("field.selectorType=XPATH 合法 → ready=true")
    void selectorTypeXpathValid() {
        TaskDefinition def = new TaskDefinition(1, new TaskMode.SinglePage(),
                "https://example.com", Viewport.DEFAULT, null,
                List.of(new FieldDefinition("title", FieldSource.VISIBLE_TEXT, "//h1", null,
                        SelectorType.XPATH, ResultType.TEXT, TrimPolicy.TRIM, null, true)));
        ReadinessReport report = readiness.validate(def);
        assertThat(report.ready()).isTrue();
    }

    @Test
    @DisplayName("PAGE_URL 字段 selectorType 忽略（不影响校验）")
    void pageUrlFieldSelectorTypeIgnored() {
        TaskDefinition def = new TaskDefinition(1, new TaskMode.SinglePage(),
                "https://example.com", Viewport.DEFAULT, null,
                List.of(new FieldDefinition("url", FieldSource.PAGE_URL, null, null,
                        SelectorType.XPATH, ResultType.TEXT, TrimPolicy.TRIM, null, false)));
        // PAGE_URL selector/attributeName 必空（已校验）；selectorType 不报错。
        assertThat(readiness.validate(def).ready()).isTrue();
    }

    @Test
    @DisplayName("BusinessErrorCode.TASK_INVALID_WAIT_POLICY 存在且 400")
    void taskInvalidWaitPolicyCodeExists() {
        assertThat(BusinessErrorCode.TASK_INVALID_WAIT_POLICY.httpStatus()).isEqualTo(400);
        assertThat(BusinessErrorCode.TASK_INVALID_WAIT_POLICY.code())
                .isEqualTo("TASK_INVALID_WAIT_POLICY");
    }

    private static TaskDefinition singlePage() {
        return new TaskDefinition(1, new TaskMode.SinglePage(),
                "https://example.com", Viewport.DEFAULT, null,
                List.of(new FieldDefinition("title", FieldSource.VISIBLE_TEXT, "h1", null,
                        SelectorType.CSS, ResultType.TEXT, TrimPolicy.TRIM, null, true)));
    }
}