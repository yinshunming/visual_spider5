package com.visualspider.extraction.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.visualspider.extraction.spi.ExtractionPreview;
import com.visualspider.extraction.spi.ExtractionPreview.DomState;
import com.visualspider.extraction.spi.ExtractionPreview.ListPreviewResult;
import com.visualspider.extraction.spi.ExtractionPreview.Node;
import com.visualspider.extraction.spi.PreviewResult;
import com.visualspider.task.domain.FieldDefinition;
import com.visualspider.task.domain.FieldSource;
import com.visualspider.task.domain.ListItemRule;
import com.visualspider.task.domain.ResultType;
import com.visualspider.task.domain.SelectorType;
import com.visualspider.task.domain.TaskDefinition;
import com.visualspider.task.domain.TaskMode;
import com.visualspider.task.domain.TrimPolicy;
import com.visualspider.task.domain.Viewport;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ExtractionPreviewImpl#previewList} 单元测试（M4 spec §D9 / §T3）。
 *
 * <p>用合成 {@link DomState} 模拟 list 模式：100 个 item 上限 20 / 字段在每个 item scope 内。
 */
class ExtractionPreviewListTest {

    private final ExtractionPreviewImpl impl = new ExtractionPreviewImpl(new CleaningPipeline());

    @Test
    @DisplayName("100 个 item 调用 maxItems=20 → 只预览前 20 条；totalMatchCount=100")
    void capToMaxItems() {
        DomState state = fakeListState(100, "title-prefix-", "title");
        TaskDefinition def = singleFieldListTask("title-prefix-*", "title", "h1");
        ListPreviewResult result = impl.previewList(def, state, 20);
        assertThat(result.totalMatchCount()).isEqualTo(100);
        assertThat(result.previews()).hasSize(20);
    }

    @Test
    @DisplayName("maxItems > 20 → 强制 cap 至 20（spec §T3）")
    void hardCapTwenty() {
        DomState state = fakeListState(50, "x-", "title");
        TaskDefinition def = singleFieldListTask("x-*", "title", "h1");
        ListPreviewResult result = impl.previewList(def, state, 100);
        assertThat(result.totalMatchCount()).isEqualTo(50);
        assertThat(result.previews()).hasSize(20);
    }

    @Test
    @DisplayName("maxItems < 总数 → 只预览前 maxItems 条")
    void respectsCallerMaxItems() {
        DomState state = fakeListState(30, "y-", "title");
        TaskDefinition def = singleFieldListTask("y-*", "title", "h1");
        ListPreviewResult result = impl.previewList(def, state, 5);
        assertThat(result.totalMatchCount()).isEqualTo(30);
        assertThat(result.previews()).hasSize(5);
    }

    @Test
    @DisplayName("0 个 item → previews 空 + totalMatchCount=0")
    void noItems() {
        DomState state = fakeListState(0, "z-", "title");
        TaskDefinition def = singleFieldListTask("z-*", "title", "h1");
        ListPreviewResult result = impl.previewList(def, state, 20);
        assertThat(result.previews()).isEmpty();
        assertThat(result.totalMatchCount()).isZero();
    }

    @Test
    @DisplayName("listItemRule=null → 空结果（不抛）")
    void listItemRuleNullSafe() {
        DomState state = fakeListState(5, "q-", "title");
        TaskDefinition def = singleFieldListTask(null, "title", "h1");
        ListPreviewResult result = impl.previewList(def, state, 20);
        assertThat(result.previews()).isEmpty();
        assertThat(result.totalMatchCount()).isZero();
    }

    // ---------- helpers ----------

    /**
     * 构造 DomState：query 命中 N 个 item Nodes；scopeToNode 返回新 DomState，
     * 其 query 仅返回该 item 下的字段 Node。
     */
    static DomState fakeListState(int itemCount, String itemIdPrefix, String fieldId) {
        List<Node> itemNodes = new ArrayList<>();
        Map<Integer, List<Node>> fieldNodesByItem = new java.util.HashMap<>();
        for (int i = 0; i < itemCount; i++) {
            Node itemNode = new Node("li", itemIdPrefix + i, "row", "row-" + i,
                    Map.of("data-id", String.valueOf(i)));
            itemNodes.add(itemNode);
            Node fieldNode = new Node("h1", fieldId + "-" + i, null,
                    "title-" + i, Map.of());
            fieldNodesByItem.put(i, List.of(fieldNode));
        }
        return new DomState() {
            @Override
            public String url() {
                return "https://example.com/list";
            }

            @Override
            public List<Node> query(String selector, SelectorType type) {
                // 简单通配：return all items
                return itemNodes;
            }

            @Override
            public DomState scopeToNode(Node item) {
                int idx = -1;
                for (int i = 0; i < itemNodes.size(); i++) {
                    if (itemNodes.get(i) == item) {
                        idx = i;
                        break;
                    }
                }
                if (idx < 0) {
                    throw new IllegalArgumentException("unknown item");
                }
                final int captured = idx;
                return new DomState() {
                    @Override
                    public String url() {
                        return "https://example.com/list";
                    }

                    @Override
                    public List<Node> query(String selector, SelectorType type) {
                        return fieldNodesByItem.getOrDefault(captured, List.of());
                    }
                };
            }
        };
    }

    static TaskDefinition singleFieldListTask(String listSelector, String fieldName, String fieldSelector) {
        ListItemRule rule = listSelector == null
                ? null
                : new ListItemRule(listSelector, SelectorType.CSS);
        FieldDefinition field = new FieldDefinition(fieldName, FieldSource.VISIBLE_TEXT,
                fieldSelector, null, SelectorType.CSS, ResultType.TEXT, TrimPolicy.TRIM, null, true);
        return new TaskDefinition(2, new TaskMode.List(),
                "https://example.com", Viewport.DEFAULT, null, null, rule, null,
                List.of(field));
    }
}
