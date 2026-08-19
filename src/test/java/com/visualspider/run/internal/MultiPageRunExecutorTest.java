package com.visualspider.run.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.visualspider.extraction.spi.ExtractionPreview;
import com.visualspider.extraction.spi.ExtractionPreview.DomState;
import com.visualspider.extraction.spi.ExtractionPreview.Node;
import com.visualspider.extraction.spi.PreviewResult;
import com.visualspider.extraction.spi.PreviewResult.FieldOutcome;
import com.visualspider.result.internal.UniqueKeyHasher;
import com.visualspider.result.spi.BatchOutcome;
import com.visualspider.result.spi.ResultRecord;
import com.visualspider.result.spi.RunEventInput;
import com.visualspider.result.spi.RunResultSink;
import com.visualspider.run.spi.RunExecutionContext;
import com.visualspider.run.spi.RunPageHandle;
import com.visualspider.run.spi.RunState;
import com.visualspider.run.spi.StopReason;
import com.visualspider.task.domain.FieldDefinition;
import com.visualspider.task.domain.FieldSource;
import com.visualspider.task.domain.Limits;
import com.visualspider.task.domain.ListItemRule;
import com.visualspider.task.domain.NavigationMode;
import com.visualspider.task.domain.PaginationRule;
import com.visualspider.task.domain.ResultType;
import com.visualspider.task.domain.SelectorType;
import com.visualspider.task.domain.TaskDefinition;
import com.visualspider.task.domain.TaskMode;
import com.visualspider.task.domain.TaskSnapshot;
import com.visualspider.task.domain.TrimPolicy;
import com.visualspider.task.domain.UniqueKeyField;
import com.visualspider.task.domain.Viewport;
import com.visualspider.task.domain.WaitPolicy;
import com.visualspider.visualbrowser.spi.TargetUrlPolicy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link MultiPageRunExecutor} 单元测试（M5-2 / issue #40 / spec §D4 阶段1）。
 *
 * <p>覆盖：
 * <ul>
 *   <li>{@code paginationRule == null} -> 退化为"只跑当前页"（等价 M4 ListRunExecutor）</li>
 *   <li>NEXT_PAGE：点击成功后处理下一页，元素消失（NOT_FOUND）后正常终止</li>
 *   <li>LOAD_MORE：阶段1未实现 -> 同样退化为"只跑当前页"（完整语义留 (c)）</li>
 *   <li>事件序列：LIST_PAGE_LOADED / PAGINATION_CLICKED + M4 逐 item 事件码</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class MultiPageRunExecutorTest {

    @Mock private RunRepository repository;
    @Mock private RunResultSink resultSink;
    @Mock private ExtractionPreview preview;
    @Mock private TargetUrlPolicy urlPolicy;
    @Mock private RunPageHandle pageHandle;

    private final UniqueKeyHasher hasher = new UniqueKeyHasher();

    /** 共享 listItemRule 命中元素集（镜像 ListRunExecutorTest）。 */
    private final AtomicReference<List<Node>> domItems = new AtomicReference<>(List.of());

    /** 收集 appendBatch 写入的事件 stage，供事件序列断言。 */
    private final List<String> eventStages = new ArrayList<>();

    @Test
    @DisplayName("paginationRule=null -> 只跑当前页：click 不被调用，单页事件 + SUCCESS")
    void nullPaginationRuleDegradesToSinglePage() {
        stubAllSuccess(3, null);
        stubRepositoryFinal(3, 0);

        newExecutor().execute(newCtx(), 11L);

        verify(pageHandle, never()).click(any(), anyLong());
        assertThat(eventStages.stream().filter("LIST_PAGE_LOADED"::equals)).hasSize(1);
        assertThat(eventStages.stream().filter("LIST_ITER_START"::equals)).hasSize(1);
        assertThat(eventStages.stream().filter("LIST_ITEM_EXTRACTED"::equals)).hasSize(3);
        verify(repository).markTerminal(eq(11L), eq(RunState.SUCCESS), eq(StopReason.COMPLETED));
    }

    @Test
    @DisplayName("NEXT_PAGE：第 1 次点击成功 -> 第 2 页，第 2 次元素消失 -> 终止；2 页事件齐全")
    void nextPageTwoPagesThenDisappear() {
        PaginationRule pagination = new PaginationRule(NavigationMode.NEXT_PAGE, "a.next");
        stubAllSuccess(3, pagination);
        stubRepositoryFinal(6, 0, pagination);
        when(pageHandle.click(eq("a.next"), anyLong()))
                .thenReturn(RunPageHandle.ClickResult.CLICKED)
                .thenReturn(RunPageHandle.ClickResult.NOT_FOUND);

        newExecutor().execute(newCtx(), 11L);

        verify(pageHandle, org.mockito.Mockito.times(2)).click(eq("a.next"), anyLong());
        assertThat(eventStages.stream().filter("LIST_PAGE_LOADED"::equals)).hasSize(2);
        assertThat(eventStages.stream().filter("LIST_ITER_START"::equals)).hasSize(2);
        assertThat(eventStages.stream().filter("PAGINATION_CLICKED"::equals)).hasSize(1);
        assertThat(eventStages.stream().filter("LIST_ITEM_EXTRACTED"::equals)).hasSize(6);
        verify(repository).markTerminal(eq(11L), eq(RunState.SUCCESS), eq(StopReason.COMPLETED));
    }

    @Test
    @DisplayName("LOAD_MORE：阶段1未实现 -> 退化为只跑当前页（click 不被调用）")
    void loadMoreDegradesToSinglePage() {
        stubAllSuccess(3, new PaginationRule(NavigationMode.LOAD_MORE, "button.more"));
        stubRepositoryFinal(3, 0);

        newExecutor().execute(newCtx(), 11L);

        verify(pageHandle, never()).click(any(), anyLong());
        assertThat(eventStages.stream().filter("LIST_PAGE_LOADED"::equals)).hasSize(1);
        verify(repository).markTerminal(eq(11L), eq(RunState.SUCCESS), eq(StopReason.COMPLETED));
    }

    @Test
    @DisplayName("page limit 上限触发：maxPages=2 + 3 item -> 处理 2 条后 break + PAGE_LIMIT")
    void pageLimitExceeded() {
        stubAllSuccess(3, null);
        stubRepositoryFinal(2, 0);
        // maxPages=2：第 3 个 item 的 limit 检查触发 break（镜像 ListRunExecutorTest）
        RunExecutionContext ctx = new RunExecutionContext(System.currentTimeMillis(),
                30 * 60 * 1000L, 2, 10_000, pageHandle);

        newExecutor().execute(ctx, 11L);

        verify(repository, atLeastOnce())
                .markTerminal(eq(11L), eq(RunState.SUCCESS), eq(StopReason.PAGE_LIMIT));
    }

    private MultiPageRunExecutor newExecutor() {
        return new MultiPageRunExecutor(repository, resultSink, preview, urlPolicy, hasher);
    }

    private RunExecutionContext newCtx() {
        return new RunExecutionContext(System.currentTimeMillis(),
                30 * 60 * 1000L, 200, 10_000, pageHandle);
    }

    private void stubAllSuccess(int total, PaginationRule pagination) {
        org.mockito.Mockito.doNothing().when(urlPolicy).validate(any());
        when(pageHandle.navigateAndAwaitDomContentLoaded(any()))
                .thenReturn(new RunPageHandle.NavigationResult(true, 200, false, null));
        when(pageHandle.currentUrl()).thenReturn("https://example.com/list");
        when(pageHandle.waitForSelector(any(), anyLong())).thenReturn(true);
        // click 未显式 stub 时（nullPagination / LOAD_MORE 用例不会被调用）默认 NOT_FOUND；
        // lenient 避免严格 stub 在 click 未走的用例上报 UnnecessaryStubbing。
        org.mockito.Mockito.lenient()
                .when(pageHandle.click(any(), anyLong()))
                .thenReturn(RunPageHandle.ClickResult.NOT_FOUND);
        List<Node> items = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            items.add(new Node("tr", "", "v-" + i, "", java.util.Map.of()));
        }
        domItems.set(items);
        DomState dom = new DomState() {
            @Override public String url() { return "https://example.com/list"; }
            @Override public List<Node> query(String sel, SelectorType t) { return domItems.get(); }
            @Override public DomState scopeToNode(Node item) { return this; }
        };
        when(pageHandle.acquireDomState()).thenReturn(dom);
        when(resultSink.appendBatch(anyLong(), any(), any()))
                .thenAnswer(inv -> {
                    List<RunEventInput> events = inv.getArgument(2);
                    if (events != null) {
                        events.forEach(e -> eventStages.add(e.stage()));
                    }
                    List<ResultRecord> recs = inv.getArgument(1);
                    int raw = recs == null ? 0 : recs.size();
                    if (raw == 0) return new BatchOutcome(0, 0, 0, 0);
                    return new BatchOutcome(raw, 0, raw, 0);
                });
        when(preview.preview(any(), any())).thenReturn(singleItem("v"));
    }

    private void stubRepositoryFinal(int finalCount, int failCount) {
        stubRepositoryFinal(finalCount, failCount, null);
    }

    private void stubRepositoryFinal(int finalCount, int failCount, PaginationRule pagination) {
        when(repository.findById(anyLong())).thenReturn(Optional.of(
                new RunRepository.RunRecord(
                        11L, 1L, 100L,
                        RunState.SUCCESS, StopReason.COMPLETED,
                        false, 0, finalCount, failCount,
                        listSnapshotWithPagination(pagination),
                        java.time.OffsetDateTime.now(), null, null)));
    }

    private static PreviewResult singleItem(String value) {
        return new PreviewResult(
                List.of(new FieldOutcome("title", value, value, false)),
                List.of());
    }

    private TaskSnapshot listSnapshot() {
        return listSnapshotWithPagination(null);
    }

    private static TaskSnapshot listSnapshotWithPagination(PaginationRule pagination) {
        FieldDefinition field = new FieldDefinition("title", FieldSource.VISIBLE_TEXT,
                "h1", null, SelectorType.CSS, ResultType.TEXT, TrimPolicy.TRIM, null, true);
        TaskDefinition def = new TaskDefinition(3, new TaskMode.List(),
                "https://example.com", Viewport.DEFAULT, new WaitPolicy(0),
                new Limits(200, 10_000, Duration.ofMinutes(30)),
                new ListItemRule("ul > li", SelectorType.CSS),
                List.of(new UniqueKeyField("title")),
                pagination,
                List.of(field));
        return new TaskSnapshot(1L, 2L, "name", new TaskMode.List(),
                3, 5L, def);
    }
}
