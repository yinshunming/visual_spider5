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
 *
 * <p>fixture 建模<b>真实 DOM 变化</b>（每个 item 的字段子元素各异、各 item 文本不同），
 * 而非全 identical 签名——后者会让"单元格相似的行"骗过算法（re-baseline 发现的 fake 盲区）。
 * 断言 {@code rule().selector()} 指向<b>列表项</b>（与 {@code ListRunExecutor} 消费语义一致）。
 */
class DomLevelCandidateListItemInferrerTest {

    private final CandidateListItemInferrer inferrer = new DomLevelCandidateListItemInferrer();

    @Test
    @DisplayName("标准列表：tbody 容器胜出 -> rule=tr，matchCount=5，score≥0.6")
    void standardListInferredAsTrItems() {
        DomSnapshot clicked = standardListDom();
        InferredCandidateListItem result = inferrer.infer(clicked);
        assertThat(result.matchCount()).isEqualTo(5);
        assertThat(result.score()).isGreaterThanOrEqualTo(0.6);
        assertThat(result.rule().selector()).isEqualToIgnoringCase("tr");
    }

    @Test
    @DisplayName("卡片网格：grid 容器胜出 -> rule=div.card，matchCount=6，score≥0.6")
    void cardGridInferredAsCardItems() {
        DomSnapshot clicked = cardGridDom();
        InferredCandidateListItem result = inferrer.infer(clicked);
        assertThat(result.matchCount()).isEqualTo(6);
        assertThat(result.score()).isGreaterThanOrEqualTo(0.6);
        assertThat(result.rule().selector()).isEqualToIgnoringCase("div.card");
    }

    @Test
    @DisplayName("嵌套列表：内层 ul.lead-items 胜出 -> rule=li.item，matchCount=4，score≥0.6")
    void nestedListInferredAsInnerItems() {
        DomSnapshot clicked = nestedListDom();
        InferredCandidateListItem result = inferrer.infer(clicked);
        assertThat(result.matchCount()).isEqualTo(4);
        assertThat(result.score()).isGreaterThanOrEqualTo(0.6);
        assertThat(result.rule().selector()).isEqualToIgnoringCase("li.item");
    }

    @Test
    @DisplayName("嵌套列表：外层 ul.menu 子元素数=2 -> score<0.6 不入选（不误选 li.category）")
    void nestedListOuterMenuBelowThreshold() {
        DomSnapshot clicked = nestedListDom();
        InferredCandidateListItem result = inferrer.infer(clicked);
        // 唯一 ≥0.6 候选是内层 ul.lead-items；若误选外层 ul.menu 则 selector 会是 li.category
        assertThat(result.rule().selector().toLowerCase()).isNotEqualTo("li.category");
    }

    @Test
    @DisplayName("非重复结构：无有效候选（matchCount < 2 不入选）")
    void noRepeatStructureEmpty() {
        DomSnapshot clicked = new DomSnapshot(
                "p", null, Map.of(),
                "Only one paragraph here",
                List.of(
                        new AncestorSnapshot("p", null, Map.of(), "Only one", 0, List.of()),
                        new AncestorSnapshot("body", null, Map.of(), "", 1, List.of(
                                new ElementSignature("h1", null, Set.of(), Set.of("title"), Map.of()))),
                        new AncestorSnapshot("html", null, Map.of(), "", 1, List.of(
                                new ElementSignature("body", null, Set.of(), Set.of("body"), Map.of())))));
        InferredCandidateListItem result = inferrer.infer(clicked);
        assertThat(result.lowConfidence()).isTrue();
        assertThat(result.matchCount()).isZero();
        assertThat(result.score()).isZero();
        assertThat(result.ancestorPath()).isEmpty();
        assertThat(result.components()).isEmpty();
        assertThat(result.alternatives()).isEmpty();
    }

    @Test
    @DisplayName("adjustAncestor DOWN：从 tbody 下移到 tr 层，返回不同候选（即使 score<0.6）")
    void adjustAncestorDownMovesToFinerContainer() {
        DomSnapshot clicked = standardListDom();
        InferredCandidateListItem initial = inferrer.infer(clicked);
        assertThat(initial.rule().selector()).isEqualToIgnoringCase("tr");

        InferredCandidateListItem down = inferrer.adjustAncestor(clicked,
                CandidateListItemInferrer.Direction.DOWN);
        // 下移一层到 tr 容器：其多数派子元素是 td（单元格），selector 应不再是 tr
        assertThat(down.ancestorPath()).isNotEmpty();
        assertThat(down.rule().selector().toLowerCase()).isNotEqualTo("tr");
    }

    @Test
    @DisplayName("adjustAncestor UP：从 ul.lead-items 上移到 li.category 容器层")
    void adjustAncestorUpMovesToCoarserContainer() {
        DomSnapshot clicked = nestedListDom();
        InferredCandidateListItem initial = inferrer.infer(clicked);
        assertThat(initial.rule().selector()).isEqualToIgnoringCase("li.item");

        InferredCandidateListItem up = inferrer.adjustAncestor(clicked,
                CandidateListItemInferrer.Direction.UP);
        assertThat(up.ancestorPath()).isNotEmpty();
        // 上移到 li.category 容器层：其子元素是 [span.title, ul.lead-items]，无 ≥2 同类 -> 空 或 不同
        assertThat(up.rule().selector().toLowerCase()).isNotEqualTo("li.item");
    }

    // ---------- fixture builders ----------

    /**
     * 标准 table 列表：table > tbody > tr × 5，每行 3 个 td（a.title / span.date / 文本 count）。
     * clicked = a.title（第一个单元格里的链接）。
     */
    static DomSnapshot standardListDom() {
        // 3 个 td 字段子元素，childTagCounts 各异 -> tr 容器 structRaw 低，不误选
        ElementSignature tdTitle = new ElementSignature("td", null, Set.of(),
                Set.of("alpha"), Map.of("a", 1));
        ElementSignature tdDate = new ElementSignature("td", null, Set.of(),
                Set.of("2024-01-01"), Map.of("span", 1));
        ElementSignature tdCount = new ElementSignature("td", "count", Set.of(),
                Set.of("10"), Map.of());
        // 5 行 tr，childTagCounts 都是 {td:3}（结构一致），文本各异 -> tbody structRaw=1, textRaw=0
        ElementSignature tr1 = new ElementSignature("tr", null, Set.of(),
                Set.of("alpha", "2024-01-01", "10"), Map.of("td", 3));
        ElementSignature tr2 = new ElementSignature("tr", null, Set.of(),
                Set.of("beta", "2024-01-02", "20"), Map.of("td", 3));
        ElementSignature tr3 = new ElementSignature("tr", null, Set.of(),
                Set.of("gamma", "2024-01-03", "30"), Map.of("td", 3));
        ElementSignature tr4 = new ElementSignature("tr", null, Set.of(),
                Set.of("delta", "2024-01-04", "40"), Map.of("td", 3));
        ElementSignature tr5 = new ElementSignature("tr", null, Set.of(),
                Set.of("epsilon", "2024-01-05", "50"), Map.of("td", 3));
        List<ElementSignature> tbodyChildren = List.of(tr1, tr2, tr3, tr4, tr5);

        AncestorSnapshot clickedAnc = new AncestorSnapshot(
                "a", "title", Map.of("href", "/items/1"), "Alpha", 0, List.of());
        AncestorSnapshot tdAnc = new AncestorSnapshot(
                "td", null, Map.of(), "Alpha", 1, List.of(
                        new ElementSignature("a", "title", Set.of("href"), Set.of("alpha"), Map.of())));
        AncestorSnapshot trAnc = new AncestorSnapshot(
                "tr", null, Map.of(), "Alpha 2024-01-01 10", 3, List.of(tdTitle, tdDate, tdCount));
        AncestorSnapshot tbodyAnc = new AncestorSnapshot(
                "tbody", null, Map.of(), "", 5, tbodyChildren);
        AncestorSnapshot tableAnc = new AncestorSnapshot(
                "table", null, Map.of(), "", 1, List.of(
                        new ElementSignature("tbody", null, Set.of(), Set.of(), Map.of("tr", 5))));
        AncestorSnapshot bodyAnc = new AncestorSnapshot(
                "body", null, Map.of(), "", 1, List.of(
                        new ElementSignature("table", null, Set.of(), Set.of(), Map.of("tbody", 1))));
        AncestorSnapshot htmlAnc = new AncestorSnapshot(
                "html", null, Map.of(), "", 1, List.of(
                        new ElementSignature("body", null, Set.of(), Set.of(), Map.of("table", 1))));
        return new DomSnapshot("a", "title", Map.of("href", "/items/1"), "Alpha",
                List.of(clickedAnc, tdAnc, trAnc, tbodyAnc, tableAnc, bodyAnc, htmlAnc));
    }

    /**
     * 卡片网格：div.cards > div.card × 6，每卡片 span.text + a.title。
     * clicked = span.text（第一个卡片里的文本）。
     */
    static DomSnapshot cardGridDom() {
        ElementSignature spanInCard = new ElementSignature("span", "text", Set.of(),
                Set.of("card", "1", "body"), Map.of());
        ElementSignature aInCard = new ElementSignature("a", "title", Set.of("href"),
                Set.of("card", "1", "title"), Map.of());
        // 6 张卡片，childTagCounts 都是 {span:1, a:1}，文本各异
        ElementSignature card1 = new ElementSignature("div", "card", Set.of("id"),
                Set.of("card", "1", "body", "title"), Map.of("span", 1, "a", 1));
        ElementSignature card2 = new ElementSignature("div", "card", Set.of("id"),
                Set.of("card", "2", "body", "title"), Map.of("span", 1, "a", 1));
        ElementSignature card3 = new ElementSignature("div", "card", Set.of("id"),
                Set.of("card", "3", "body", "title"), Map.of("span", 1, "a", 1));
        ElementSignature card4 = new ElementSignature("div", "card", Set.of("id"),
                Set.of("card", "4", "body", "title"), Map.of("span", 1, "a", 1));
        ElementSignature card5 = new ElementSignature("div", "card", Set.of("id"),
                Set.of("card", "5", "body", "title"), Map.of("span", 1, "a", 1));
        ElementSignature card6 = new ElementSignature("div", "card", Set.of("id"),
                Set.of("card", "6", "body", "title"), Map.of("span", 1, "a", 1));
        List<ElementSignature> gridChildren = List.of(card1, card2, card3, card4, card5, card6);

        AncestorSnapshot clickedAnc = new AncestorSnapshot(
                "span", "text", Map.of(), "Card 1 body", 0, List.of());
        AncestorSnapshot cardAnc = new AncestorSnapshot(
                "div", "card", Map.of("id", "c1"), "Card 1 body Card 1 title", 2,
                List.of(spanInCard, aInCard));
        AncestorSnapshot gridAnc = new AncestorSnapshot(
                "div", "cards", Map.of(), "grid", 6, gridChildren);
        AncestorSnapshot bodyAnc = new AncestorSnapshot(
                "body", null, Map.of(), "", 1, List.of(
                        new ElementSignature("div", "cards", Set.of(), Set.of("grid"), Map.of("div", 6))));
        AncestorSnapshot htmlAnc = new AncestorSnapshot(
                "html", null, Map.of(), "", 1, List.of(
                        new ElementSignature("body", null, Set.of(), Set.of(), Map.of("div", 1))));
        return new DomSnapshot("span", "text", Map.of(), "Card 1 body",
                List.of(clickedAnc, cardAnc, gridAnc, bodyAnc, htmlAnc));
    }

    /**
     * 嵌套 ul/li：ul.menu > li.category × 2 > ul.lead-items > li.item × 4/2。
     * clicked = a（第一个 li.item 里的链接）。
     */
    static DomSnapshot nestedListDom() {
        ElementSignature aInItem = new ElementSignature("a", null, Set.of("href"),
                Set.of("alpha"), Map.of());
        // 4 个 li.item，childTagCounts 都是 {a:1}，文本各异
        ElementSignature item1 = new ElementSignature("li", "item", Set.of("data-id"),
                Set.of("alpha"), Map.of("a", 1));
        ElementSignature item2 = new ElementSignature("li", "item", Set.of("data-id"),
                Set.of("beta"), Map.of("a", 1));
        ElementSignature item3 = new ElementSignature("li", "item", Set.of("data-id"),
                Set.of("gamma"), Map.of("a", 1));
        ElementSignature item4 = new ElementSignature("li", "item", Set.of("data-id"),
                Set.of("delta"), Map.of("a", 1));
        List<ElementSignature> leadChildren = List.of(item1, item2, item3, item4);

        AncestorSnapshot clickedAnc = new AncestorSnapshot(
                "a", null, Map.of("href", "/a/1"), "Alpha", 0, List.of());
        AncestorSnapshot itemAnc = new AncestorSnapshot(
                "li", "item", Map.of("data-id", "a1"), "Alpha", 1, List.of(aInItem));
        AncestorSnapshot leadItemsAnc = new AncestorSnapshot(
                "ul", "lead-items", Map.of(), "lead list", 4, leadChildren);
        AncestorSnapshot categoryAnc = new AncestorSnapshot(
                "li", "category", Map.of(), "Category A", 2, List.of(
                        new ElementSignature("span", "title", Set.of(), Set.of("category", "a"), Map.of()),
                        new ElementSignature("ul", "lead-items", Set.of(), Set.of(), Map.of("li", 4))));
        AncestorSnapshot menuAnc = new AncestorSnapshot(
                "ul", "menu", Map.of(), "menu", 2, List.of(
                        new ElementSignature("li", "category", Set.of(), Set.of("cat", "a"), Map.of("span", 1, "ul", 1)),
                        new ElementSignature("li", "category", Set.of(), Set.of("cat", "b"), Map.of("span", 1, "ul", 1))));
        AncestorSnapshot bodyAnc = new AncestorSnapshot(
                "body", null, Map.of(), "", 1, List.of(
                        new ElementSignature("ul", "menu", Set.of(), Set.of("nav"), Map.of("li", 2))));
        AncestorSnapshot htmlAnc = new AncestorSnapshot(
                "html", null, Map.of(), "", 1, List.of(
                        new ElementSignature("body", null, Set.of(), Set.of(), Map.of("ul", 1))));
        return new DomSnapshot("a", null, Map.of("href", "/a/1"), "Alpha",
                List.of(clickedAnc, itemAnc, leadItemsAnc, categoryAnc, menuAnc, bodyAnc, htmlAnc));
    }
}
