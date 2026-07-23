package com.visualspider.extraction.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.visualspider.task.domain.FieldDefinition;
import com.visualspider.task.domain.FieldSource;
import com.visualspider.task.domain.ResultType;
import com.visualspider.task.domain.TrimPolicy;
import org.junit.jupiter.api.Test;

class CleaningPipelineTest {

    private final CleaningPipeline pipeline = new CleaningPipeline();

    @Test
    void trimThenPreserveRawOnRegexNoMatch() {
        FieldDefinition field = textField("title", "  hello  ", false, ".+");
        CleaningPipeline.ResultCollector collector = new CleaningPipeline.ResultCollector();
        CleaningPipeline.Result r = pipeline.clean("  hello  ", field, collector);
        assertThat(r.cleanedValue).isEqualTo("hello");
        assertThat(collector.immutable()).extracting(d -> d.code().name())
                .doesNotContain("REGEX_NO_MATCH");
    }

    @Test
    void regexGroupOneSelected() {
        FieldDefinition field = textField("k", null, false, "price:(\\d+)");
        CleaningPipeline.ResultCollector collector = new CleaningPipeline.ResultCollector();
        CleaningPipeline.Result r = pipeline.clean("price:42", field, collector);
        assertThat(r.cleanedValue).isEqualTo("42");
    }

    @Test
    void regexNoMatchEmitsDiagnosticAndKeepsRaw() {
        FieldDefinition field = textField("k", null, false, "X");
        CleaningPipeline.ResultCollector collector = new CleaningPipeline.ResultCollector();
        CleaningPipeline.Result r = pipeline.clean("abc", field, collector);
        // 全部不匹配 → cleanedValue=null 但 rawValue 保留
        assertThat(r.rawValue).isEqualTo("abc");
        assertThat(r.cleanedValue).isNull();
        assertThat(r.isEmpty).isTrue();
        assertThat(collector.immutable()).extracting(d -> d.code().name())
                .contains("REGEX_NO_MATCH");
    }

    @Test
    void numberConversionFailureKeepsRaw() {
        FieldDefinition field = numberField();
        CleaningPipeline.ResultCollector collector = new CleaningPipeline.ResultCollector();
        CleaningPipeline.Result r = pipeline.clean("not-a-number", field, collector);
        assertThat(r.cleanedValue).isEqualTo("not-a-number");
        assertThat(collector.immutable()).extracting(d -> d.code().name())
                .contains("TYPE_CONVERSION_FAILED");
    }

    @Test
    void urlOnlyAcceptsHttpHttps() {
        FieldDefinition field = urlField();
        CleaningPipeline.ResultCollector collector = new CleaningPipeline.ResultCollector();
        CleaningPipeline.Result r = pipeline.clean("ftp://example.com", field, collector);
        assertThat(collector.immutable()).extracting(d -> d.code().name())
                .contains("TYPE_CONVERSION_FAILED");
        assertThat(r.cleanedValue).isEqualTo("ftp://example.com");
    }

    @Test
    void emptyValueEmitsFieldEmpty() {
        FieldDefinition field = textField("k", null, false, null);
        CleaningPipeline.ResultCollector collector = new CleaningPipeline.ResultCollector();
        CleaningPipeline.Result r = pipeline.clean("", field, collector);
        assertThat(r.isEmpty).isTrue();
        assertThat(r.cleanedValue).isNull();
        assertThat(collector.immutable()).extracting(d -> d.code().name())
                .contains("FIELD_EMPTY");
    }

    @Test
    void preserveTrimKeepsWhitespace() {
        FieldDefinition field = new FieldDefinition("k", FieldSource.VISIBLE_TEXT, null, null,
                ResultType.TEXT, TrimPolicy.PRESERVE, null, false);
        CleaningPipeline.ResultCollector collector = new CleaningPipeline.ResultCollector();
        CleaningPipeline.Result r = pipeline.clean("  hello  ", field, collector);
        assertThat(r.cleanedValue).isEqualTo("  hello  ");
    }

    private static FieldDefinition textField(String name, String ignoreSelector, boolean ignoreRequired, String regex) {
        return new FieldDefinition(name, FieldSource.VISIBLE_TEXT, "div", null,
                ResultType.TEXT, TrimPolicy.TRIM, regex, false);
    }

    private static FieldDefinition numberField() {
        return new FieldDefinition("price", FieldSource.VISIBLE_TEXT, "div", null,
                ResultType.NUMBER, TrimPolicy.TRIM, null, false);
    }

    private static FieldDefinition urlField() {
        return new FieldDefinition("href", FieldSource.LINK_URL, "a", null,
                ResultType.URL, TrimPolicy.TRIM, null, false);
    }
}
