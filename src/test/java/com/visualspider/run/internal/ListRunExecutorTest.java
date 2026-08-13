package com.visualspider.run.internal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.times;
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
import com.visualspider.run.spi.RunExecutionContext;
import com.visualspider.run.spi.RunPageHandle;
import com.visualspider.run.spi.RunState;
import com.visualspider.run.spi.StopReason;
import com.visualspider.task.domain.FieldDefinition;
import com.visualspider.task.domain.FieldSource;
import com.visualspider.task.domain.Limits;
import com.visualspider.task.domain.ListItemRule;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link ListRunExecutor} 单元测试(M4 spec §D7 / §D8 / §T3)。
 *
 * <p>覆盖三类状态:SUCCESS / PARTIAL_SUCCESS / FAILED 终态判定。
 */
@ExtendWith(MockitoExtension.class)
class ListRunExecutorTest {

    @Mock private RunRepository repository;
    @Mock private com.visualspider.result.spi.RunResultSink resultSink;
    @Mock private ExtractionPreview preview;
    @Mock private TargetUrlPolicy urlPolicy;
    @Mock private RunPageHandle pageHandle;

    private final UniqueKeyHasher hasher = new UniqueKeyHasher();

    /** 共享 listItemRule 命中元素集：commonNavigation 设置 DomState.query 读取此引用；
     *  各 stub 方法（stubAllSuccess 等）在调用前写入对应数量的 Node。 */
    private final AtomicReference<List<Node>> domItems = new AtomicReference<>(List.of());

    @Test
    @DisplayName("3 item 全部成功 → SUCCESS")
    void allItemsSuccess() {
        stubAllSuccess(3);
        stubRepositoryFinal(3, 0);
        newExecutor().execute(newCtxWithHandle(pageHandle), 11L);
        verify(repository, atLeastOnce()).markTerminal(anyLong(), any(RunState.class), any(StopReason.class));
        verify(resultSink, atLeastOnce()).appendBatch(eq(11L), any(), any());
    }

    @Test
    @DisplayName("3 item 中 1 失败 → PARTIAL_SUCCESS")
    void partialSuccess() {
        stubMixedOutcomes(2, 1);
        stubRepositoryFinal(2, 1);
        newExecutor().execute(newCtxWithHandle(pageHandle), 11L);
        verify(repository, atLeastOnce()).markTerminal(anyLong(), any(RunState.class), any(StopReason.class));
    }

    @Test
    @DisplayName("3 item 全部失败 → FAILED")
    void allItemsFail() {
        stubAllFailures(3);
        stubRepositoryFinal(0, 3);
        newExecutor().execute(newCtxWithHandle(pageHandle), 11L);
        verify(repository, atLeastOnce()).markTerminal(anyLong(), any(RunState.class), any(StopReason.class));
    }

    @Test
    @DisplayName("启动即超 30min → 不处理任何 item → SUCCESS + TIME_LIMIT")
    void timeLimitExceeded() {
        commonNavigation();
        domItems.set(List.of(new Node("tr", "", "v-0", "", java.util.Map.of()),
                new Node("tr", "", "v-1", "", java.util.Map.of()),
                new Node("tr", "", "v-2", "", java.util.Map.of())));
        stubRepositoryFinal(0, 0);
        // 起始时间设为 31min 前，第一轮 check 即 timeLimitExceeded=true
        RunExecutionContext ctx = new RunExecutionContext(
                System.currentTimeMillis() - 31L * 60 * 1000L,
                30L * 60 * 1000L, 200, 10_000, pageHandle);
        newExecutor().execute(ctx, 11L);
        // 0 计数 → state=SUCCESS（spec §D7 else 分支），但 reason 被 timeLimitExceeded 覆盖
        verify(repository, atLeastOnce())
                .markTerminal(eq(11L), eq(RunState.SUCCESS), eq(StopReason.TIME_LIMIT));
        // 只能有 2 次空 events 调用（LIST_ITER_START + terminal），不应有 result 写入
        verify(resultSink, times(2)).appendBatch(eq(11L), eq(List.of()), any());
    }

    @Test
    @DisplayName("page limit 上限触发：maxPages=2 + 3 item → 处理 2 条后 break + PAGE_LIMIT")
    void pageLimitExceeded() {
        stubAllSuccess(3);
        stubRepositoryFinal(2, 0);
        // maxPages=2 限制：第 3 轮 check 时 pageCount=2 >= 2，触发 pageLimitExceeded，break
        RunExecutionContext ctx = new RunExecutionContext(System.currentTimeMillis(),
                30 * 60 * 1000L, 2, 10_000, pageHandle);
        newExecutor().execute(ctx, 11L);
        verify(repository, atLeastOnce())
                .markTerminal(eq(11L), eq(RunState.SUCCESS), eq(StopReason.PAGE_LIMIT));
        // 2 result 写 + 2 LIST_ITEM_EXTRACTED 事件 + 1 LIST_ITER_START + 1 terminal = 6 次
        verify(resultSink, times(6)).appendBatch(eq(11L), any(), any());
    }

    private ListRunExecutor newExecutor() {
        return new ListRunExecutor(repository, resultSink, preview, urlPolicy, hasher);
    }

    private static RunExecutionContext newCtx() {
        // 用 @Mock 注入 pageHandle 通过 ctx 构造器（pageHandle 是 ctx 字段，非全局 mock）
        return newCtxWithHandle(null);
    }

    private static RunExecutionContext newCtxWithHandle(RunPageHandle handle) {
        return new RunExecutionContext(System.currentTimeMillis(),
                30 * 60 * 1000L, 200, 10_000, handle);
    }

    private void stubAllSuccess(int total) {
        commonNavigation();
        List<Node> items = new ArrayList<>();
        for (int i = 0; i < total; i++) items.add(new Node("tr", "", "v-" + i, "", java.util.Map.of()));
        domItems.set(items);
        when(resultSink.appendBatch(anyLong(), any(), any()))
                .thenAnswer(inv -> {
                    List<ResultRecord> recs = inv.getArgument(1);
                    int raw = recs == null ? 0 : recs.size();
                    if (raw == 0) return new BatchOutcome(0, 0, 0, 0);
                    return new BatchOutcome(raw, 0, raw, 0);
                });
        when(preview.preview(any(), any())).thenReturn(singleItem("v"));
    }

    private void stubMixedOutcomes(int ok, int fail) {
        commonNavigation();
        List<Node> items = new ArrayList<>();
        for (int i = 0; i < ok + fail; i++) items.add(new Node("tr", "", "v-" + i, "", java.util.Map.of()));
        domItems.set(items);
        AtomicInteger idx = new AtomicInteger(0);
        when(resultSink.appendBatch(anyLong(), any(), any()))
                .thenAnswer(inv -> {
                    List<ResultRecord> recs = inv.getArgument(1);
                    int raw = recs == null ? 0 : recs.size();
                    if (raw == 0) return new BatchOutcome(0, 0, 0, 0);
                    if (idx.getAndIncrement() < ok) {
                        return new BatchOutcome(raw, 0, raw, 0);
                    }
                    return new BatchOutcome(raw, 0, 0, raw);
                });
        when(preview.preview(any(), any())).thenReturn(singleItem("v"));
    }

    private void stubAllFailures(int total) {
        commonNavigation();
        List<Node> items = new ArrayList<>();
        for (int i = 0; i < total; i++) items.add(new Node("tr", "", "v-" + i, "", java.util.Map.of()));
        domItems.set(items);
        when(resultSink.appendBatch(anyLong(), any(), any()))
                .thenAnswer(inv -> {
                    List<ResultRecord> recs = inv.getArgument(1);
                    int raw = recs == null ? 0 : recs.size();
                    if (raw == 0) return new BatchOutcome(0, 0, 0, 0);
                    return new BatchOutcome(raw, 0, 0, raw);
                });
        when(preview.preview(any(), any())).thenReturn(singleItem("v"));
    }

    private void commonNavigation() {
        org.mockito.Mockito.doNothing().when(urlPolicy).validate(any());
        when(pageHandle.navigateAndAwaitDomContentLoaded(any()))
                .thenReturn(new RunPageHandle.NavigationResult(true, 200, false, null));
        when(pageHandle.currentUrl()).thenReturn("https://example.com/list");
        when(pageHandle.waitForSelector(any(), anyLong())).thenReturn(true);
        // 简化 DomState mock：query 一律返回 domItems 引用（listItemRule 与字段共享，
        // 字段查询 scopeToNode 后仍走此 dom，preview() 取首项即可）。
        DomState dom = new DomState() {
            @Override public String url() { return "https://example.com/list"; }
            @Override public List<Node> query(String sel, SelectorType t) { return domItems.get(); }
            @Override public DomState scopeToNode(Node item) { return this; }
        };
        when(pageHandle.acquireDomState()).thenReturn(dom);
    }

    private void stubRepositoryFinal(int finalCount, int failCount) {
        when(repository.findById(anyLong())).thenReturn(java.util.Optional.of(
                new RunRepository.RunRecord(
                        11L, 1L, 100L,
                        RunState.SUCCESS, StopReason.COMPLETED,
                        false, 0, finalCount, failCount,
                        listSnapshot(),
                        java.time.OffsetDateTime.now(), null, null)));
    }

    private static PreviewResult singleItem(String value) {
        return new PreviewResult(
                List.of(new FieldOutcome("title", value, value, false)),
                List.of());
    }

    private static TaskSnapshot listSnapshot() {
        FieldDefinition field = new FieldDefinition("title", FieldSource.VISIBLE_TEXT,
                "h1", null, SelectorType.CSS, ResultType.TEXT, TrimPolicy.TRIM, null, true);
        TaskDefinition def = new TaskDefinition(2, new TaskMode.List(),
                "https://example.com", Viewport.DEFAULT, new WaitPolicy(0),
                new Limits(200, 10_000, Duration.ofMinutes(30)),
                new ListItemRule("ul > li", SelectorType.CSS),
                List.of(new UniqueKeyField("title")),
                List.of(field));
        return new TaskSnapshot(1L, 2L, "name", new TaskMode.List(),
                2, 5L, def);
    }
}
