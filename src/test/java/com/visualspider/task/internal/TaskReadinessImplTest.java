package com.visualspider.task.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.visualspider.task.domain.FieldDefinition;
import com.visualspider.task.domain.FieldSource;
import com.visualspider.task.domain.ReadinessReport;
import com.visualspider.task.domain.ResultType;
import com.visualspider.task.domain.TaskDefinition;
import com.visualspider.task.domain.TaskMode;
import com.visualspider.task.domain.TrimPolicy;
import com.visualspider.task.domain.Viewport;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * TaskReadinessImpl 单元测试。
 *
 * <p>覆盖：合法定义 → ready=true；schemaVersion / startUrl / viewport / field name 的每个错误码路径。
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
                "https://example.com", Viewport.DEFAULT, List.of());
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
                "ftp://example.com", Viewport.DEFAULT, List.of());
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
                "https://", Viewport.DEFAULT, List.of());
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
                "", Viewport.DEFAULT, List.of());
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
                "https://example.com", new Viewport(800, 600), List.of());
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
                ResultType.TEXT, TrimPolicy.TRIM, null, true);
        FieldDefinition f2 = new FieldDefinition("title", FieldSource.VISIBLE_TEXT, ".body", null,
                ResultType.TEXT, TrimPolicy.TRIM, null, false);
        TaskDefinition def = new TaskDefinition(1, new TaskMode.SinglePage(),
                "https://example.com", Viewport.DEFAULT, List.of(f1, f2));
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
                ResultType.TEXT, TrimPolicy.TRIM, null, true);
        TaskDefinition def = new TaskDefinition(1, new TaskMode.SinglePage(),
                "https://example.com", Viewport.DEFAULT, List.of(f));
        ReadinessReport report = readiness.validate(def);
        assertThat(report.ready()).isFalse();
        assertThat(report.errors())
                .extracting(ReadinessReport.ReadinessError::code)
                .contains("TASK_INVALID_FIELD_NAME");
    }

    @Test
    @DisplayName("mode=LIST 暂合法（M1 可创建 DRAFT）；M4 启用 validateForRun")
    void listModeAcceptable() {
        TaskDefinition def = new TaskDefinition(1, new TaskMode.List(),
                "https://example.com", Viewport.DEFAULT, List.of());
        ReadinessReport report = readiness.validate(def);
        assertThat(report.ready()).isTrue();
    }

    @Test
    @DisplayName("validateForRun 抛 UnsupportedOperationException")
    void validateForRunUnsupported() {
        org.junit.jupiter.api.Assertions.assertThrows(
                UnsupportedOperationException.class,
                () -> readiness.validateForRun(1L, null));
    }

    private static TaskDefinition singlePage() {
        return new TaskDefinition(1, new TaskMode.SinglePage(),
                "https://example.com", Viewport.DEFAULT, List.of());
    }
}
