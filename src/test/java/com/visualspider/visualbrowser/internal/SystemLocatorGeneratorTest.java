package com.visualspider.visualbrowser.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.visualspider.visualbrowser.spi.AdvancedSelectorEditor.ElementSnapshot;
import com.visualspider.visualbrowser.internal.SystemLocatorGenerator.GenerationContext;
import com.visualspider.visualbrowser.internal.SystemLocatorGenerator.LocatorCandidate;
import java.util.List;
import org.junit.jupiter.api.Test;

class SystemLocatorGeneratorTest {

    private final SystemLocatorGenerator generator = new SystemLocatorGenerator();

    @Test
    void idUniqueRanksFirst() {
        ElementSnapshot snapshot = new ElementSnapshot("button", "submit", "primary");
        List<LocatorCandidate> candidates = generator.generate(snapshot,
                new GenerationContext(true, false));

        assertThat(candidates).isNotEmpty();
        LocatorCandidate first = candidates.get(0);
        assertThat(first.css()).isEqualTo("button#submit");
        assertThat(first.xpath()).isEqualTo("//button[@id='submit']");
        assertThat(first.specificityScore()).isEqualTo(100);
    }

    @Test
    void classCombinationUniqueRanksBeforeNth() {
        ElementSnapshot snapshot = new ElementSnapshot("div", null, "header wrap");
        List<LocatorCandidate> candidates = generator.generate(snapshot,
                new GenerationContext(false, true));

        // 第一个是 class 评分 80
        assertThat(candidates.get(0).css()).isEqualTo("div.header");
        assertThat(candidates.get(1).css()).contains(":nth-of-type");
    }

    @Test
    void tagOnlyFallsThrough() {
        ElementSnapshot snapshot = new ElementSnapshot("span", null, null);
        List<LocatorCandidate> candidates = generator.generate(snapshot,
                new GenerationContext(false, false));

        // 不返回 id/class 候选；最后是 tag (40)
        assertThat(candidates).hasSize(2);
        assertThat(candidates.get(candidates.size() - 1).stabilityScore()).isEqualTo(40);
    }

    @Test
    void nullElementReturnsEmpty() {
        assertThat(generator.generate(null, null)).isEmpty();
    }

    @Test
    void cssAndXpathAlwaysPaired() {
        ElementSnapshot snapshot = new ElementSnapshot("a", "go", null);
        List<LocatorCandidate> candidates = generator.generate(snapshot,
                new GenerationContext(true, false));

        assertThat(candidates).allSatisfy(c -> {
            assertThat(c.css()).isNotBlank();
            assertThat(c.xpath()).isNotBlank();
        });
    }
}
