package com.visualspider.visualbrowser.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.visualspider.extraction.spi.DomSnapshot;
import com.visualspider.extraction.spi.DomSnapshot.AncestorSnapshot;
import com.visualspider.extraction.spi.DomSnapshot.ElementSignature;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link DomSnapshotExtractor} 纯单测（不依赖 Playwright / Chromium）。
 *
 * <p>手构 {@code Map} 模拟 JS {@code evaluate} 返回结构，验证防御性类型转换与字段映射契约。
 * 这是 JS ↔ Java 的合同测试；真 Playwright 行为差异由 {@code ListCandidiateInferenceIT} 覆盖。
 */
class DomSnapshotExtractorTest {

    @Test
    @DisplayName("标准结构：clicked + 祖先链 + childSignatures + childTagCounts 全部映射")
    void extractsStandardStructure() {
        Map<String, Object> raw = standardRaw();
        DomSnapshot snap = DomSnapshotExtractor.extract(raw);

        assertThat(snap.tagName()).isEqualTo("a");
        assertThat(snap.className()).isEqualTo("title");
        assertThat(snap.attributes()).containsEntry("href", "/items/1");
        assertThat(snap.innerTextSnippet()).isEqualTo("Alpha");
        assertThat(snap.ancestors()).hasSize(5);

        AncestorSnapshot clicked = snap.ancestors().get(0);
        assertThat(clicked.tagName()).isEqualTo("a");
        assertThat(clicked.childSignatures()).isEmpty();

        AncestorSnapshot tbody = snap.ancestors().get(3);
        assertThat(tbody.tagName()).isEqualToIgnoringCase("tbody");
        assertThat(tbody.childCount()).isEqualTo(5);
        assertThat(tbody.childSignatures()).hasSize(5);

        ElementSignature firstRow = tbody.childSignatures().get(0);
        assertThat(firstRow.tagName()).isEqualToIgnoringCase("tr");
        assertThat(firstRow.attributeKeys()).isEmpty();
        assertThat(firstRow.textTokens()).containsExactly("alpha", "2024-01-01", "10");
        assertThat(firstRow.childTagCounts()).containsEntry("td", 3);
    }

    @Test
    @DisplayName("ancestors 为空 -> IllegalArgumentException（与 DomSnapshot 紧凑构造一致）")
    void emptyAncestorsRejected() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("tagName", "p");
        raw.put("className", null);
        raw.put("attributes", Map.of());
        raw.put("innerTextSnippet", "");
        raw.put("ancestors", List.of());
        assertThatThrownBy(() -> DomSnapshotExtractor.extract(raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ancestors");
    }

    @Test
    @DisplayName("raw 为 null -> IllegalArgumentException")
    void nullRawRejected() {
        assertThatThrownBy(() -> DomSnapshotExtractor.extract(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("childSignatures 缺失 -> 空列表（防御性，DomSnapshot 默认）")
    void missingChildSignaturesDefaultsToEmpty() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("tagName", "p");
        raw.put("className", "");
        raw.put("attributes", Map.of());
        raw.put("innerTextSnippet", "");
        raw.put("ancestors", List.of(Map.of(
                "tagName", "body", "className", "",
                "attributes", Map.of(), "innerTextSnippet", "",
                "childCount", 0
                // 故意缺 childSignatures
        )));
        DomSnapshot snap = DomSnapshotExtractor.extract(raw);
        assertThat(snap.ancestors().get(0).childSignatures()).isEmpty();
    }

    @Test
    @DisplayName("attributeKeys / textTokens 是 List（JS 数组） -> Set<String>")
    void listFieldsBecomeSets() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("tagName", "tr");
        raw.put("className", "");
        raw.put("attributes", Map.of());
        raw.put("innerTextSnippet", "");
        raw.put("ancestors", List.of(Map.of(
                "tagName", "tbody", "className", "",
                "attributes", Map.of(), "innerTextSnippet", "",
                "childCount", 1,
                "childSignatures", List.of(Map.of(
                        "tagName", "tr", "className", "",
                        "attributeKeys", List.of("data-id"),
                        "textTokens", List.of("alpha", "beta"),
                        "childTagCounts", Map.of("td", 2)
                ))
        )));
        DomSnapshot snap = DomSnapshotExtractor.extract(raw);
        ElementSignature sig = snap.ancestors().get(0).childSignatures().get(0);
        assertThat(sig.attributeKeys()).isInstanceOf(Set.class).containsExactly("data-id");
        assertThat(sig.textTokens()).isInstanceOf(Set.class).containsExactlyInAnyOrder("alpha", "beta");
    }

    @Test
    @DisplayName("childTagCounts 数值（Long/Integer/Double） -> Map<String,Integer>")
    void childTagCountsNumericCoercion() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("tagName", "table");
        raw.put("className", "");
        raw.put("attributes", Map.of());
        raw.put("innerTextSnippet", "");
        raw.put("ancestors", List.of(Map.of(
                "tagName", "table", "className", "",
                "attributes", Map.of(), "innerTextSnippet", "",
                "childCount", 1,
                "childSignatures", List.of(Map.of(
                        "tagName", "tbody", "className", "",
                        "attributeKeys", List.of(),
                        "textTokens", List.of(),
                        "childTagCounts", Map.of("tr", 5)  // JS evaluate 返 Number
                ))
        )));
        DomSnapshot snap = DomSnapshotExtractor.extract(raw);
        ElementSignature sig = snap.ancestors().get(0).childSignatures().get(0);
        assertThat(sig.childTagCounts()).containsExactly(Map.entry("tr", 5));
    }

    // ---------- helpers ----------

    private static Map<String, Object> standardRaw() {
        Map<String, Object> clickedSig = Map.of(
                "tagName", "a", "className", "title",
                "attributeKeys", List.of("href"),
                "textTokens", List.of("alpha"),
                "childTagCounts", Map.of());
        // ancestors[0] = clicked 自身（JS 的 snap(el)）
        Map<String, Object> clickedAncestor = new LinkedHashMap<>();
        clickedAncestor.put("tagName", "a");
        clickedAncestor.put("className", "title");
        clickedAncestor.put("attributes", Map.of("href", "/items/1"));
        clickedAncestor.put("innerTextSnippet", "Alpha");
        clickedAncestor.put("childCount", 0);
        clickedAncestor.put("childSignatures", List.of());

        Map<String, Object> tdAncestor = new LinkedHashMap<>();
        tdAncestor.put("tagName", "td");
        tdAncestor.put("className", "");
        tdAncestor.put("attributes", Map.of());
        tdAncestor.put("innerTextSnippet", "Alpha");
        tdAncestor.put("childCount", 1);
        tdAncestor.put("childSignatures", List.of(clickedSig));

        Map<String, Object> trAncestor = new LinkedHashMap<>();
        trAncestor.put("tagName", "tr");
        trAncestor.put("className", "");
        trAncestor.put("attributes", Map.of());
        trAncestor.put("innerTextSnippet", "row");
        trAncestor.put("childCount", 3);
        trAncestor.put("childSignatures", List.of(
                Map.of("tagName", "td", "className", "",
                        "attributeKeys", List.of(),
                        "textTokens", List.of("alpha"),
                        "childTagCounts", Map.of("a", 1)),
                Map.of("tagName", "td", "className", "",
                        "attributeKeys", List.of(),
                        "textTokens", List.of("2024-01-01"),
                        "childTagCounts", Map.of("span", 1)),
                Map.of("tagName", "td", "className", "",
                        "attributeKeys", List.of(),
                        "textTokens", List.of("10"),
                        "childTagCounts", Map.of())));

        Map<String, Object> trSig = Map.of(
                "tagName", "tr", "className", "",
                "attributeKeys", List.of(),
                "textTokens", List.of("alpha", "2024-01-01", "10"),
                "childTagCounts", Map.of("td", 3));
        Map<String, Object> tbodyAncestor = new LinkedHashMap<>();
        tbodyAncestor.put("tagName", "tbody");
        tbodyAncestor.put("className", "");
        tbodyAncestor.put("attributes", Map.of());
        tbodyAncestor.put("innerTextSnippet", "");
        tbodyAncestor.put("childCount", 5);
        tbodyAncestor.put("childSignatures", List.of(trSig, trSig, trSig, trSig, trSig));

        Map<String, Object> bodyAncestor = new LinkedHashMap<>();
        bodyAncestor.put("tagName", "body");
        bodyAncestor.put("className", "");
        bodyAncestor.put("attributes", Map.of());
        bodyAncestor.put("innerTextSnippet", "");
        bodyAncestor.put("childCount", 1);
        bodyAncestor.put("childSignatures", List.of(Map.of(
                "tagName", "table", "className", "",
                "attributeKeys", List.of(),
                "textTokens", List.of(),
                "childTagCounts", Map.of("tbody", 1))));

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("tagName", "a");
        root.put("className", "title");
        root.put("attributes", Map.of("href", "/items/1"));
        root.put("innerTextSnippet", "Alpha");
        root.put("ancestors", List.of(clickedAncestor, tdAncestor, trAncestor, tbodyAncestor, bodyAncestor));
        return root;
    }
}