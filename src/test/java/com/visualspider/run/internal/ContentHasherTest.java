package com.visualspider.run.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.visualspider.extraction.spi.ExtractionPreview.Node;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ContentHasher} 单元测试（M5-3 / issue #41 / spec §D5）。
 *
 * <p>覆盖：规范化（跨行 / 多空白折叠）、顺序敏感、内容区分度（重复页保护依据）。
 */
class ContentHasherTest {

    private final ContentHasher hasher = new ContentHasher();

    @Test
    @DisplayName("空白差异不改变 hash（规范化：折叠空白 + 去首尾）")
    void whitespaceNormalizationIsStable() {
        List<Node> a = List.of(new Node("tr", "", "", "Alpha  Beta", Map.of()),
                new Node("tr", "", "", "  Gamma  ", Map.of()));
        List<Node> b = List.of(new Node("tr", "", "", "Alpha Beta", Map.of()),
                new Node("tr", "", "", "\n Gamma \t", Map.of()));
        assertThat(hasher.hash(a)).isEqualTo(hasher.hash(b));
    }

    @Test
    @DisplayName("内容不同 -> hash 不同；顺序不同 -> hash 不同")
    void contentAndOrderSensitivity() {
        List<Node> page1 = List.of(item("Alpha"), item("Beta"));
        List<Node> page2 = List.of(item("Zeta"), item("Eta"));
        List<Node> reversed = List.of(item("Beta"), item("Alpha"));
        assertThat(hasher.hash(page1)).isNotEqualTo(hasher.hash(page2));
        assertThat(hasher.hash(page1)).isNotEqualTo(hasher.hash(reversed));
    }

    @Test
    @DisplayName("null textContent 与空列表均可计算（不抛异常）")
    void nullsAndEmptyAreSafe() {
        assertThat(hasher.hash(List.of(new Node("tr", "", "", null, Map.of())))).isNotEmpty();
        assertThat(hasher.hash(List.of())).isNotEmpty();
        assertThat(hasher.hash(null)).isNotEmpty();
    }

    private static Node item(String text) {
        return new Node("tr", "", "", text, Map.of());
    }
}
