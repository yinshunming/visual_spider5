package com.visualspider.extraction.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.visualspider.extraction.spi.ExtractionDiagnostic.DiagnosticCode;
import com.visualspider.extraction.spi.ExtractionPreview;
import com.visualspider.extraction.spi.PreviewResult;
import com.visualspider.task.domain.FieldDefinition;
import com.visualspider.task.domain.FieldSource;
import com.visualspider.task.domain.ResultType;
import com.visualspider.task.domain.TaskDefinition;
import com.visualspider.task.domain.TaskMode.SinglePage;
import com.visualspider.task.domain.TrimPolicy;
import com.visualspider.task.domain.Viewport;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExtractionPreviewImplTest {

    private final CleaningPipeline cleaner = new CleaningPipeline();
    private final ExtractionPreviewImpl preview = new ExtractionPreviewImpl(cleaner);

    @Test
    void visibleTextFieldProducesCleanedValue() {
        TaskDefinition def = def(List.of(field("title", "h1", FieldSource.VISIBLE_TEXT, null, ResultType.TEXT)));
        ExtractionPreview.DomState dom = new FakeDom("http://example.com/",
                Map.of("h1", List.of(node("h1", "main", "", "  Hello  ", Map.of()))));
        PreviewResult result = preview.preview(def, dom);
        assertThat(result.fieldOutcomes()).hasSize(1);
        PreviewResult.FieldOutcome o = result.fieldOutcomes().get(0);
        assertThat(o.fieldName()).isEqualTo("title");
        assertThat(o.rawValue()).isEqualTo("  Hello  ");
        assertThat(o.cleanedValue()).isEqualTo("Hello"); // TRIM
        assertThat(o.isEmpty()).isFalse();
    }

    @Test
    void zeroMatchEmitsDiagnostic() {
        TaskDefinition def = def(List.of(field("x", ".missing", FieldSource.VISIBLE_TEXT, null, ResultType.TEXT)));
        ExtractionPreview.DomState dom = new FakeDom("http://example.com/", Map.of());
        PreviewResult result = preview.preview(def, dom);
        assertThat(result.diagnostics()).extracting(d -> d.code()).contains(DiagnosticCode.ZERO_MATCH);
        assertThat(result.fieldOutcomes().get(0).isEmpty()).isTrue();
    }

    @Test
    void multipleMatchEmitsDiagnosticAndTakesFirst() {
        TaskDefinition def = def(List.of(field("x", "li", FieldSource.VISIBLE_TEXT, null, ResultType.TEXT)));
        ExtractionPreview.DomState dom = new FakeDom("http://example.com/",
                Map.of("li", List.of(node("li", "", "", "first", Map.of()), node("li", "", "", "second", Map.of()))));
        PreviewResult result = preview.preview(def, dom);
        assertThat(result.diagnostics()).extracting(d -> d.code()).contains(DiagnosticCode.MULTIPLE_MATCH);
        assertThat(result.fieldOutcomes().get(0).rawValue()).isEqualTo("first");
    }

    @Test
    void attributeMissingEmitsDiagnostic() {
        TaskDefinition def = def(List.of(field("a", "a", FieldSource.ATTRIBUTE, "href", ResultType.TEXT)));
        ExtractionPreview.DomState dom = new FakeDom("http://example.com/",
                Map.of("a", List.of(node("a", "", "", "link", Map.of())))); // 无 href
        PreviewResult result = preview.preview(def, dom);
        assertThat(result.diagnostics()).extracting(d -> d.code()).contains(DiagnosticCode.ATTRIBUTE_MISSING);
    }

    @Test
    void pageUrlFieldUsesDomUrl() {
        TaskDefinition def = def(List.of(field("url", null, FieldSource.PAGE_URL, null, ResultType.TEXT)));
        ExtractionPreview.DomState dom = new FakeDom("http://example.com/page", Map.of());
        PreviewResult result = preview.preview(def, dom);
        assertThat(result.fieldOutcomes().get(0).rawValue()).isEqualTo("http://example.com/page");
    }

    private static TaskDefinition def(List<FieldDefinition> fields) {
        return new TaskDefinition(1, new SinglePage(), "http://example.com/", Viewport.DEFAULT, fields);
    }

    private static FieldDefinition field(String name, String selector, FieldSource source,
                                         String attribute, ResultType type) {
        return new FieldDefinition(name, source, selector, attribute, type, TrimPolicy.TRIM, null, false);
    }

    private static ExtractionPreview.Node node(String tag, String id, String cls,
                                                        String text, Map<String, String> attrs) {
        return new ExtractionPreview.Node(tag, id, cls, text, attrs);
    }

    private static final class FakeDom implements ExtractionPreview.DomState {
        private final String url;
        private final Map<String, List<ExtractionPreview.Node>> bySelector;

        FakeDom(String url, Map<String, List<ExtractionPreview.Node>> bySelector) {
            this.url = url;
            this.bySelector = bySelector;
        }

        @Override
        public String url() {
            return url;
        }

        @Override
        public List<ExtractionPreview.Node> querySelectorAll(String selector) {
            return bySelector.getOrDefault(selector, List.of());
        }
    }
}
