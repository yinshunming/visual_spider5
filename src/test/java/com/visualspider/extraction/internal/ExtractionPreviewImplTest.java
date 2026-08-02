package com.visualspider.extraction.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.visualspider.extraction.spi.ExtractionDiagnostic.DiagnosticCode;
import com.visualspider.extraction.spi.ExtractionPreview;
import com.visualspider.extraction.spi.PreviewResult;
import com.visualspider.task.domain.FieldDefinition;
import com.visualspider.task.domain.FieldSource;
import com.visualspider.task.domain.ResultType;
import com.visualspider.task.domain.SelectorType;
import com.visualspider.task.domain.TaskDefinition;
import com.visualspider.task.domain.TaskMode.SinglePage;
import com.visualspider.task.domain.TrimPolicy;
import com.visualspider.task.domain.Viewport;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExtractionPreviewImplTest {

    private final CleaningPipeline cleaner = new CleaningPipeline();
    private final ExtractionPreviewImpl preview = new ExtractionPreviewImpl(cleaner);

    @Test
    void visibleTextFieldProducesCleanedValue() {
        TaskDefinition def = def(List.of(field("title", "h1", FieldSource.VISIBLE_TEXT, null, ResultType.TEXT, SelectorType.CSS)));
        ExtractionPreview.DomState dom = new FakeDom("http://example.com/",
                cssMap("h1", List.of(node("h1", "main", "", "  Hello  ", Map.of()))));
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
        TaskDefinition def = def(List.of(field("x", ".missing", FieldSource.VISIBLE_TEXT, null, ResultType.TEXT, SelectorType.CSS)));
        ExtractionPreview.DomState dom = new FakeDom("http://example.com/", cssMap("", List.of()));
        PreviewResult result = preview.preview(def, dom);
        assertThat(result.diagnostics()).extracting(d -> d.code()).contains(DiagnosticCode.ZERO_MATCH);
        assertThat(result.fieldOutcomes().get(0).isEmpty()).isTrue();
    }

    @Test
    void multipleMatchEmitsDiagnosticAndTakesFirst() {
        TaskDefinition def = def(List.of(field("x", "li", FieldSource.VISIBLE_TEXT, null, ResultType.TEXT, SelectorType.CSS)));
        ExtractionPreview.DomState dom = new FakeDom("http://example.com/",
                cssMap("li", List.of(node("li", "", "", "first", Map.of()), node("li", "", "", "second", Map.of()))));
        PreviewResult result = preview.preview(def, dom);
        assertThat(result.diagnostics()).extracting(d -> d.code()).contains(DiagnosticCode.MULTIPLE_MATCH);
        assertThat(result.fieldOutcomes().get(0).rawValue()).isEqualTo("first");
    }

    @Test
    void attributeMissingEmitsDiagnostic() {
        TaskDefinition def = def(List.of(field("a", "a", FieldSource.ATTRIBUTE, "href", ResultType.TEXT, SelectorType.CSS)));
        ExtractionPreview.DomState dom = new FakeDom("http://example.com/",
                cssMap("a", List.of(node("a", "", "", "link", Map.of())))); // 无 href
        PreviewResult result = preview.preview(def, dom);
        assertThat(result.diagnostics()).extracting(d -> d.code()).contains(DiagnosticCode.ATTRIBUTE_MISSING);
    }

    @Test
    void pageUrlFieldUsesDomUrl() {
        TaskDefinition def = def(List.of(field("url", null, FieldSource.PAGE_URL, null, ResultType.TEXT, SelectorType.CSS)));
        ExtractionPreview.DomState dom = new FakeDom("http://example.com/page", cssMap("", List.of()));
        PreviewResult result = preview.preview(def, dom);
        assertThat(result.fieldOutcomes().get(0).rawValue()).isEqualTo("http://example.com/page");
    }

    // ---------- M3 扩展：XPath 分发（spec §D7）----------

    @Test
    void xpathFieldDispatchesToXpathQuery() {
        TaskDefinition def = def(List.of(field("title", "//h1", FieldSource.VISIBLE_TEXT, null, ResultType.TEXT, SelectorType.XPATH)));
        Map<String, List<ExtractionPreview.Node>> xpathBySelector = Map.of(
                "//h1", List.of(node("h1", "", "", "XPath Got It", Map.of())));
        ExtractionPreview.DomState dom = new FakeDom("http://example.com/", cssMap("", List.of()), xpathBySelector);
        PreviewResult result = preview.preview(def, dom);
        assertThat(result.fieldOutcomes()).hasSize(1);
        assertThat(result.fieldOutcomes().get(0).rawValue()).isEqualTo("XPath Got It");
        // CSS 不应被调用；M2 preview 误报 SELECTOR_SYNTAX_INVALID 已被修复
        assertThat(result.diagnostics())
                .extracting(d -> d.code())
                .doesNotContain(DiagnosticCode.SELECTOR_SYNTAX_INVALID);
    }

    @Test
    void cssFieldStillWorksAfterXpathAdded() {
        // 回归：M2 既有 CSS 字段路径不受影响
        TaskDefinition def = def(List.of(
                field("css", ".css", FieldSource.VISIBLE_TEXT, null, ResultType.TEXT, SelectorType.CSS),
                field("xpath", "//h1", FieldSource.VISIBLE_TEXT, null, ResultType.TEXT, SelectorType.XPATH)));
        ExtractionPreview.DomState dom = new FakeDom("http://example.com/",
                cssMap(".css", List.of(node("div", "css", "", "css-val", Map.of()))),
                Map.of("//h1", List.of(node("h1", "", "", "xpath-val", Map.of()))));
        PreviewResult result = preview.preview(def, dom);
        assertThat(result.fieldOutcomes()).hasSize(2);
        assertThat(result.fieldOutcomes().get(0).rawValue()).isEqualTo("css-val");
        assertThat(result.fieldOutcomes().get(1).rawValue()).isEqualTo("xpath-val");
    }

    @Test
    void xpathZeroMatchEmitsDiagnostic() {
        TaskDefinition def = def(List.of(field("x", "//missing", FieldSource.VISIBLE_TEXT, null, ResultType.TEXT, SelectorType.XPATH)));
        ExtractionPreview.DomState dom = new FakeDom("http://example.com/", cssMap("", List.of()), Map.of());
        PreviewResult result = preview.preview(def, dom);
        assertThat(result.diagnostics()).extracting(d -> d.code()).contains(DiagnosticCode.ZERO_MATCH);
    }

    @Test
    void xpathInvalidSyntaxDoesNotTriggerFakeSelectorSyntaxInvalid() {
        // M2 隐患回归：旧实现无视 selectorType，CSS 解析器把 "//div" 当成 CSS -> 抛错 → SELECTOR_SYNTAX_INVALID。
        // M3 修复：按 selectorType 分发；XPath 用 XPath 解析，无效 XPath 应 ZERO_MATCH 而非 SELECTOR_SYNTAX_INVALID。
        TaskDefinition def = def(List.of(field("x", "//", FieldSource.VISIBLE_TEXT, null, ResultType.TEXT, SelectorType.XPATH)));
        // 假 XPath 引擎：把 "//" 视为 0 匹配
        ExtractionPreview.DomState dom = new FakeDom("http://example.com/", cssMap("", List.of()),
                Map.of("//", List.of()));
        PreviewResult result = preview.preview(def, dom);
        assertThat(result.diagnostics())
                .extracting(d -> d.code())
                .doesNotContain(DiagnosticCode.SELECTOR_SYNTAX_INVALID);
    }

    private static TaskDefinition def(List<FieldDefinition> fields) {
        return new TaskDefinition(1, new SinglePage(), "http://example.com/", Viewport.DEFAULT, null, fields);
    }

    private static FieldDefinition field(String name, String selector, FieldSource source,
                                         String attribute, ResultType type, SelectorType selectorType) {
        return new FieldDefinition(name, source, selector, attribute,
                selectorType, type, TrimPolicy.TRIM, null, false);
    }

    private static ExtractionPreview.Node node(String tag, String id, String cls,
                                                        String text, Map<String, String> attrs) {
        return new ExtractionPreview.Node(tag, id, cls, text, attrs);
    }

    private static Map<String, List<ExtractionPreview.Node>> cssMap(String css, List<ExtractionPreview.Node> nodes) {
        Map<String, List<ExtractionPreview.Node>> m = new java.util.HashMap<>();
        if (!css.isEmpty()) {
            m.put(css, nodes);
        }
        return m;
    }

    private static final class FakeDom implements ExtractionPreview.DomState {
        private final String url;
        private final Map<String, List<ExtractionPreview.Node>> cssBySelector;
        private final Map<String, List<ExtractionPreview.Node>> xpathBySelector;

        FakeDom(String url, Map<String, List<ExtractionPreview.Node>> cssBySelector) {
            this(url, cssBySelector, Map.of());
        }

        FakeDom(String url,
                Map<String, List<ExtractionPreview.Node>> cssBySelector,
                Map<String, List<ExtractionPreview.Node>> xpathBySelector) {
            this.url = url;
            this.cssBySelector = cssBySelector;
            this.xpathBySelector = xpathBySelector;
        }

        @Override
        public String url() {
            return url;
        }

        @Override
        public List<ExtractionPreview.Node> query(String selector, SelectorType type) {
            return switch (type) {
                case CSS -> cssBySelector.getOrDefault(selector, List.of());
                case XPATH -> xpathBySelector.getOrDefault(selector, List.of());
            };
        }

        @Override
        public List<ExtractionPreview.Node> querySelectorAll(String selector) {
            // 兼容默认实现：委托给 query(selector, CSS)。
            return query(selector, SelectorType.CSS);
        }
    }
}