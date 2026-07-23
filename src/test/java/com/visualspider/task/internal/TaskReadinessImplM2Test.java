package com.visualspider.task.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.visualspider.shared.api.BusinessErrorCode;
import com.visualspider.task.domain.FieldDefinition;
import com.visualspider.task.domain.FieldSource;
import com.visualspider.task.domain.ReadinessReport;
import com.visualspider.task.domain.TaskDefinition;
import com.visualspider.task.domain.TaskMode.SinglePage;
import com.visualspider.task.domain.TrimPolicy;
import com.visualspider.task.domain.ResultType;
import com.visualspider.task.domain.Viewport;
import java.util.List;
import org.junit.jupiter.api.Test;

class TaskReadinessImplM2Test {

    private final TaskReadinessImpl readiness = new TaskReadinessImpl();

    @Test
    void validDefinitionPasses() {
        TaskDefinition def = definition("http://example.com/",
                List.of(field("title", "h1", null, FieldSource.VISIBLE_TEXT, null, null)));
        assertThat(readiness.validate(def).ready()).isTrue();
    }

    @Test
    void rejectsEmptyFields() {
        TaskDefinition def = definition("http://example.com/", List.of());
        ReadinessReport report = readiness.validate(def);
        assertThat(report.ready()).isFalse();
        assertThat(report.errors()).extracting(e -> e.code())
                .contains(BusinessErrorCode.TASK_NO_FIELDS.code());
    }

    @Test
    void detectsDuplicateFieldCaseInsensitive() {
        TaskDefinition def = definition("http://example.com/",
                List.of(field("title", "h1", null, FieldSource.VISIBLE_TEXT, null, null),
                        field("TITLE", "h1", null, FieldSource.VISIBLE_TEXT, null, null)));
        assertThat(readiness.validate(def).ready()).isFalse();
        assertThat(readiness.validate(def).errors()).extracting(e -> e.code())
                .contains(BusinessErrorCode.TASK_DUPLICATE_FIELD.code());
    }

    @Test
    void rejectsAttributeWithoutName() {
        TaskDefinition def = definition("http://example.com/",
                List.of(field("href", "a", null, FieldSource.ATTRIBUTE, null, null)));
        ReadinessReport report = readiness.validate(def);
        assertThat(report.ready()).isFalse();
        assertThat(report.errors()).extracting(e -> e.code())
                .contains(BusinessErrorCode.TASK_MISSING_ATTRIBUTE_NAME.code());
    }

    @Test
    void rejectsPageUrlWithSelector() {
        TaskDefinition def = definition("http://example.com/",
                List.of(field("now", "div", null, FieldSource.PAGE_URL, null, null)));
        ReadinessReport report = readiness.validate(def);
        assertThat(report.ready()).isFalse();
        assertThat(report.errors()).extracting(e -> e.code())
                .contains(BusinessErrorCode.TASK_INVALID_SELECTOR.code());
    }

    @Test
    void rejectsInvalidCssSyntax() {
        TaskDefinition def = definition("http://example.com/",
                List.of(field("bad", "<<<broken", null, FieldSource.VISIBLE_TEXT, null, null)));
        ReadinessReport report = readiness.validate(def);
        assertThat(report.ready()).isFalse();
        assertThat(report.errors()).extracting(e -> e.code())
                .contains(BusinessErrorCode.TASK_INVALID_SELECTOR.code());
    }

    @Test
    void rejectsInvalidRegex() {
        TaskDefinition def = definition("http://example.com/",
                List.of(field("regex", "h1", null, FieldSource.VISIBLE_TEXT, "(unclosed", null)));
        ReadinessReport report = readiness.validate(def);
        assertThat(report.ready()).isFalse();
        assertThat(report.errors()).extracting(e -> e.code())
                .contains(BusinessErrorCode.TASK_INVALID_SELECTOR.code());
    }

    @Test
    void rejectsBadHostSyntax() {
        TaskDefinition def = definition("http://-bad.example/", List.of(
                field("title", "h1", null, FieldSource.VISIBLE_TEXT, null, null)));
        ReadinessReport report = readiness.validate(def);
        assertThat(report.ready()).isFalse();
        assertThat(report.errors()).extracting(e -> e.code())
                .contains(BusinessErrorCode.TASK_INVALID_URL.code());
    }

    private static TaskDefinition definition(String startUrl, List<FieldDefinition> fields) {
        return new TaskDefinition(1, new SinglePage(), startUrl, Viewport.DEFAULT, fields);
    }

    private static FieldDefinition field(String name, String selector, String attribute,
                                          FieldSource source, String regex, ResultType resultType) {
        return new FieldDefinition(name, source, selector, attribute,
                resultType == null ? ResultType.TEXT : resultType,
                TrimPolicy.TRIM, regex, false);
    }
}
