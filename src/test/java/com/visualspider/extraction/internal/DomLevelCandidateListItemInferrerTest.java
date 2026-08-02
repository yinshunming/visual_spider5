package com.visualspider.extraction.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.visualspider.extraction.spi.CandidateListItemInferrer;
import com.visualspider.extraction.spi.DomSnapshot;
import com.visualspider.extraction.spi.DomSnapshot.AncestorSnapshot;
import com.visualspider.extraction.spi.DomSnapshot.ElementSignature;
import com.visualspider.extraction.spi.InferredCandidateListItem;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link DomLevelCandidateListItemInferrer} 单元测试（M4 spec §D3 / §T1）。
 */
class DomLevelCandidateListItemInferrerTest {

    private final CandidateListItemInferrer inferrer = new DomLevelCandidateListItemInferrer();

    @Test
    @DisplayName("标准列表：tbody 容器被识别，matchCount=5，score≥0.6")
    void standardListTbodyInferred() {
        DomSnapshot clicked = standardListDom();
        InferredCandidateListItem result = inferrer.infer(clicked);
        assertThat(result.matchCount()).isEqualTo(5);
        assertThat(result.score()).isGreaterThanOrEqualTo(0.6);
        assertThat(result.rule().selector()).isEqualToIgnoringCase("tr");
    }

    @Test
    @DisplayName("卡片网格：div.cards 容器被识别，matchCount=6")
    void cardGridInferred() {
        DomSnapshot clicked = cardGridDom();
        InferredCandidateListItem result = inferrer.infer(clicked);
        assertThat(result.matchCount()).isEqualTo(6);
        assertThat(result.score()).isGreaterThanOrEqualTo(0.6);
        assertThat(result.rule().selector()).isEqualToIgnoringCase("div.cards");
    }

    @Test
    @DisplayName("嵌套列表：内层 ul.lead-items 被识别，matchCount=4")
    void nestedListInnerUlInferred() {
        DomSnapshot clicked = nestedListDom();
        InferredCandidateListItem result = inferrer.infer(clicked);
        assertThat(result.matchCount()).isEqualTo(4);
        assertThat(result.score()).isGreaterThanOrEqualTo(0.6);
        assertThat(result.rule().selector()).isEqualToIgnoringCase("ul.lead-items");
    }

    @Test
    @DisplayName("非重复结构：无有效候选（matchCount < 2 不入选）")
    void noRepeatStructureEmpty() {
        DomSnapshot clicked = new DomSnapshot(
                "p", null, Map.of(),
                "Only one paragraph here",
                List.of(
                        new AncestorSnapshot("p", null, Map.of(), "Only one", 1, List.of()),
                        new AncestorSnapshot("body", null, Map.of(), "", 1, List.of(
                                new ElementSignature("h1", null, Set.of(), Set.of("title"))))));
        InferredCandidateListItem result = inferrer.infer(clicked);
        assertThat(result.matchCount()).isZero();
        assertThat(result.rule().selector()).isEqualTo("__no_match__");
    }

    @Test
    @DisplayName("adjustAncestor UP 不崩溃，路径/规则可能不同")
    void adjustAncestorUpPicksDifferent() {
        DomSnapshot clicked = standardListDom();
        InferredCandidateListItem initial = inferrer.infer(clicked);
        InferredCandidateListItem up = inferrer.adjustAncestor(clicked,
                CandidateListItemInferrer.Direction.UP);
        assertThat(up.score()).isGreaterThanOrEqualTo(0.0);
        assertThat(up.ancestorPath().size()).isGreaterThanOrEqualTo(1);
    }

    // ---------- fixture builders ----------

    /**
     * 标准 table 列表：table > tbody > tr × 5。
     */
    static DomSnapshot standardListDom() {
        // 注意：Set.of 拒绝重复；用 distinct token
        ElementSignature cellTd = new ElementSignature("td", null, Set.of(),
                Set.of("title", "link", "date", "count", "score"));
        ElementSignature rowTr = new ElementSignature("tr", null, Set.of(),
                Set.of("cell-0", "cell-1", "cell-2", "cell-3", "cell-4"));
        // tbody 的 5 行子元素均为同类
        List<ElementSignature> tbodyChildren = List.of(rowTr, rowTr, rowTr, rowTr, rowTr);
        // clicked 元素：td
        AncestorSnapshot clickedAnc = new AncestorSnapshot(
                "td", null, Map.of(), "row text", 0, List.of());
        AncestorSnapshot trAnc = new AncestorSnapshot(
                "tr", null, Map.of(), "row", 5, List.of(cellTd, cellTd, cellTd, cellTd, cellTd));
        AncestorSnapshot tbodyAnc = new AncestorSnapshot(
                "tbody", null, Map.of(), "", 5, tbodyChildren);
        AncestorSnapshot tableAnc = new AncestorSnapshot(
                "table", null, Map.of(), "", 1,
                List.of(new ElementSignature("tbody", null, Set.of(),
                        Set.of("row-0", "row-1", "row-2", "row-3", "row-4"))));
        AncestorSnapshot bodyAnc = new AncestorSnapshot(
                "body", null, Map.of(), "", 1,
                List.of(new ElementSignature("table", null, Set.of(),
                        Set.of("table-0"))));
        AncestorSnapshot htmlAnc = new AncestorSnapshot(
                "html", null, Map.of(), "", 1, List.of());
        return new DomSnapshot("td", null, Map.of(), "row text",
                List.of(clickedAnc, trAnc, tbodyAnc, tableAnc, bodyAnc, htmlAnc));
    }

    /**
     * 卡片网格：div.cards > div.card × 6。
     */
    static DomSnapshot cardGridDom() {
        ElementSignature cardChild = new ElementSignature("div", "card",
                Set.of("id"), Set.of("card", "preview"));
        AncestorSnapshot clickedAnc = new AncestorSnapshot(
                "span", "text", Map.of(), "card body", 0, List.of());
        AncestorSnapshot cardAnc = new AncestorSnapshot(
                "div", "card", Map.of("id", "c1"), "card preview", 2,
                List.of(new ElementSignature("span", "text", Set.of(), Set.of("body")),
                        new ElementSignature("a", "title", Set.of("href"), Set.of("click"))));
        AncestorSnapshot gridAnc = new AncestorSnapshot(
                "div", "cards", Map.of(), "grid", 6,
                List.of(cardChild, cardChild, cardChild, cardChild, cardChild, cardChild));
        AncestorSnapshot bodyAnc = new AncestorSnapshot(
                "body", null, Map.of(), "", 1,
                List.of(new ElementSignature("div", "cards", Set.of(), Set.of("grid"))));
        AncestorSnapshot htmlAnc = new AncestorSnapshot(
                "html", null, Map.of(), "", 1, List.of());
        return new DomSnapshot("span", "text", Map.of(), "card body",
                List.of(clickedAnc, cardAnc, gridAnc, bodyAnc, htmlAnc));
    }

    /**
     * 嵌套 ul/li：外层 ul.menu > li.category > ul.lead-items > li.item × 4。
     */
    static DomSnapshot nestedListDom() {
        ElementSignature itemSig = new ElementSignature("li", "item",
                Set.of("data-id"), Set.of("alpha", "beta", "gamma"));
        AncestorSnapshot clickedAnc = new AncestorSnapshot(
                "li", "item", Map.of("data-id", "a1"), "alpha", 0, List.of());
        AncestorSnapshot leadItemsAnc = new AncestorSnapshot(
                "ul", "lead-items", Map.of(), "lead list", 4,
                List.of(itemSig, itemSig, itemSig, itemSig));
        AncestorSnapshot categoryAnc = new AncestorSnapshot(
                "li", "category", Map.of(), "Category A", 1,
                List.of(new ElementSignature("ul", "lead-items", Set.of(),
                        Set.of("lead-0", "lead-1", "lead-2", "lead-3"))));
        AncestorSnapshot menuAnc = new AncestorSnapshot(
                "ul", "menu", Map.of(), "menu", 1,
                List.of(new ElementSignature("li", "category", Set.of(),
                        Set.of("cat-0"))));
        AncestorSnapshot bodyAnc = new AncestorSnapshot(
                "body", null, Map.of(), "", 1,
                List.of(new ElementSignature("ul", "menu", Set.of(), Set.of("nav-0"))));
        AncestorSnapshot htmlAnc = new AncestorSnapshot(
                "html", null, Map.of(), "", 1, List.of());
        return new DomSnapshot("li", "item", Map.of("data-id", "a1"), "alpha",
                List.of(clickedAnc, leadItemsAnc, categoryAnc, menuAnc, bodyAnc, htmlAnc));
    }
}
