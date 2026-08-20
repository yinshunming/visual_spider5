package com.visualspider.run.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.visualspider.extraction.spi.ExtractionPreview.DomState;
import com.visualspider.extraction.spi.ExtractionPreview.Node;
import com.visualspider.result.spi.RunResultSink;
import com.visualspider.run.spi.RunExecutionContext;
import com.visualspider.run.spi.RunPageHandle;
import com.visualspider.run.spi.RunPageHandle.ClickResult;
import com.visualspider.run.spi.StopReason;
import com.visualspider.task.domain.Limits;
import com.visualspider.task.domain.ListItemRule;
import com.visualspider.task.domain.NavigationMode;
import com.visualspider.task.domain.PaginationRule;
import com.visualspider.task.domain.SelectorType;
import com.visualspider.task.domain.TaskDefinition;
import com.visualspider.task.domain.TaskMode;
import com.visualspider.task.domain.Viewport;
import com.visualspider.task.domain.WaitPolicy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link PagingExecutor} 单元测试（M5-3 / issue #41 / spec §D5 / T1）。
 *
 * <p>覆盖：
 * <ul>
 *   <li>NEXT_PAGE：URL 命中已访问 -> DUPLICATE_PAGE(reason=URL)</li>
 *   <li>NEXT_PAGE：URL 变但 contentHash 相同 -> DUPLICATE_PAGE(reason=contentHash)</li>
 *   <li>NEXT_PAGE：末页元素消失 -> 自然翻完（NATURAL，保持 M5-2 COMPLETED 语义）</li>
 *   <li>NEXT_PAGE：元素 disabled -> PAGINATION_DISABLED</li>
 *   <li>LOAD_MORE：连续 2 次无新增 -> PAGINATION_NO_NEW_ITEMS（processor 不被重复回调）</li>
 *   <li>LOAD_MORE：追加后只处理新增前缀（skipFirstN）</li>
 *   <li>LOAD_MORE：元素消失 -> PAGINATION_DISAPPEARED</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class PagingExecutorTest {

    @Mock private RunResultSink resultSink;
    @Mock private RunPageHandle page;

    /** 可变 list-item 集合（模拟翻页 / 追加后 DOM 变化）。 */
    private final AtomicInteger itemCount = new AtomicInteger(3);
    /** 当前 URL（模拟 NEXT_PAGE 翻页）。 */
    private volatile String currentUrl = "https://example.com/list";

    /** processor 调用记录：pageNo / skipFirstN / processedCount。 */
    private final List<String> processorCalls = new ArrayList<>();

    @BeforeEach
    void setUp() {
        lenient().when(resultSink.appendBatch(anyLong(), any(), any()))
                .thenReturn(new com.visualspider.result.spi.BatchOutcome(0, 0, 0, 0));
        lenient().when(page.currentUrl()).thenAnswer(inv -> currentUrl);
        lenient().when(page.waitForSelector(any(), anyLong())).thenReturn(true);
        lenient().when(page.acquireDomState()).thenAnswer(inv -> fakeDom());
        // 默认 click 未 stub 用例显式 when（避免 UnnecessaryStubbing）
    }

    @Test
    @DisplayName("NEXT_PAGE：点击后 URL 未变 -> DUPLICATE_PAGE(reason=URL)")
    void nextPageDuplicateUrl() {
        when(page.click(any(), anyLong())).thenReturn(ClickResult.CLICKED);

        PagingExecutor.LoopStop stop = runPages(NavigationMode.NEXT_PAGE);

        assertThat(stop.kind()).isEqualTo(PagingExecutor.LoopStop.Kind.PAGINATION_STOP);
        assertThat(stop.reason()).isEqualTo(StopReason.DUPLICATE_PAGE);
        assertThat(stop.message()).contains("reason=URL");
    }

    @Test
    @DisplayName("NEXT_PAGE：URL 变但内容相同 -> DUPLICATE_PAGE(reason=contentHash)")
    void nextPageDuplicateContent() {
        currentUrl = "https://example.com/list";
        when(page.click(any(), anyLong())).thenAnswer(inv -> {
            currentUrl = "https://example.com/list?page=2";  // URL 变了
            return ClickResult.CLICKED;                       // 但 itemCount 不变 -> 内容相同
        });

        PagingExecutor.LoopStop stop = runPages(NavigationMode.NEXT_PAGE);

        assertThat(stop.kind()).isEqualTo(PagingExecutor.LoopStop.Kind.PAGINATION_STOP);
        assertThat(stop.reason()).isEqualTo(StopReason.DUPLICATE_PAGE);
        assertThat(stop.message()).contains("reason=contentHash");
    }

    @Test
    @DisplayName("NEXT_PAGE：内容与 URL 都变化 -> 翻页成功后末页元素消失 -> NATURAL")
    void nextPageAdvancesThenNaturalEnd() {
        currentUrl = "https://example.com/list";
        when(page.click(any(), anyLong())).thenAnswer(inv -> {
            currentUrl = "https://example.com/list?page=2";
            itemCount.set(6);
            return ClickResult.CLICKED;
        }).thenReturn(ClickResult.NOT_FOUND);

        PagingExecutor.LoopStop stop = runPages(NavigationMode.NEXT_PAGE);

        assertThat(stop.kind()).isEqualTo(PagingExecutor.LoopStop.Kind.NATURAL);
        assertThat(stop.reason()).isNull();
        // 第 2 页全量处理（skipFirstN=0）
        assertThat(processorCalls).containsExactly("1/0/3", "2/0/6");
    }

    @Test
    @DisplayName("NEXT_PAGE：元素 disabled -> PAGINATION_DISABLED")
    void nextPageDisabled() {
        when(page.click(any(), anyLong())).thenReturn(ClickResult.DISABLED);

        PagingExecutor.LoopStop stop = runPages(NavigationMode.NEXT_PAGE);

        assertThat(stop.kind()).isEqualTo(PagingExecutor.LoopStop.Kind.PAGINATION_STOP);
        assertThat(stop.reason()).isEqualTo(StopReason.PAGINATION_DISABLED);
        assertThat(processorCalls).containsExactly("1/0/3");
    }

    @Test
    @DisplayName("LOAD_MORE：连续 2 次点击无新增 -> PAGINATION_NO_NEW_ITEMS，processor 只回调初始页")
    void loadMoreNoNewItems() {
        when(page.click(any(), anyLong())).thenReturn(ClickResult.CLICKED);

        PagingExecutor.LoopStop stop = runPages(NavigationMode.LOAD_MORE);

        assertThat(stop.kind()).isEqualTo(PagingExecutor.LoopStop.Kind.PAGINATION_STOP);
        assertThat(stop.reason()).isEqualTo(StopReason.PAGINATION_NO_NEW_ITEMS);
        // 2 次无新增点击不触发新页回调（不算 page_count++）
        assertThat(processorCalls).containsExactly("1/0/3");
    }

    @Test
    @DisplayName("LOAD_MORE：追加 3 项 -> 只处理新增前缀（skipFirstN=3）；随后无新增停止")
    void loadMoreAppendsThenStops() {
        when(page.click(any(), anyLong())).thenAnswer(inv -> {
            itemCount.set(6);
            return ClickResult.CLICKED;
        }).thenReturn(ClickResult.CLICKED).thenReturn(ClickResult.CLICKED);

        PagingExecutor.LoopStop stop = runPages(NavigationMode.LOAD_MORE);

        assertThat(stop.reason()).isEqualTo(StopReason.PAGINATION_NO_NEW_ITEMS);
        // 初始页全量 + 追加页只处理 3 条新增（skipFirstN=3）
        assertThat(processorCalls).containsExactly("1/0/3", "2/3/6");
    }

    @Test
    @DisplayName("LOAD_MORE：点击后按钮消失 -> PAGINATION_DISAPPEARED")
    void loadMoreDisappeared() {
        when(page.click(any(), anyLong())).thenReturn(ClickResult.NOT_FOUND);

        PagingExecutor.LoopStop stop = runPages(NavigationMode.LOAD_MORE);

        assertThat(stop.kind()).isEqualTo(PagingExecutor.LoopStop.Kind.PAGINATION_STOP);
        assertThat(stop.reason()).isEqualTo(StopReason.PAGINATION_DISAPPEARED);
    }

    // ---------- helpers ----------

    private PagingExecutor.LoopStop runPages(NavigationMode mode) {
        TaskDefinition def = new TaskDefinition(3, new TaskMode.List(),
                "https://example.com/list", Viewport.DEFAULT, new WaitPolicy(0),
                new Limits(200, 10_000, Duration.ofMinutes(30)),
                new ListItemRule("tbody > tr", SelectorType.CSS),
                List.of(), new PaginationRule(mode, "a.next"),
                List.of());
        RunExecutionContext ctx = new RunExecutionContext(System.currentTimeMillis(),
                30 * 60 * 1000L, 200, 10_000, page);
        return new PagingExecutor(resultSink).runPages(def, page, ctx, 11L,
                (pageNo, skipFirstN, dom, items) -> {
                    processorCalls.add(pageNo + "/" + skipFirstN + "/" + items.size());
                    return true;
                });
    }

    private DomState fakeDom() {
        return new DomState() {
            @Override public String url() { return currentUrl; }

            @Override public List<Node> query(String selector, SelectorType type) {
                int n = itemCount.get();
                List<Node> nodes = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    nodes.add(new Node("tr", "", "", "item-" + i, Map.of()));
                }
                return nodes;
            }
        };
    }
}
