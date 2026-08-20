package com.visualspider.run.internal;

import com.visualspider.extraction.spi.ExtractionPreview;
import com.visualspider.extraction.spi.ExtractionPreview.DomState;
import com.visualspider.extraction.spi.ExtractionPreview.Node;
import com.visualspider.extraction.spi.PreviewResult;
import com.visualspider.result.internal.UniqueKeyHasher;
import com.visualspider.result.spi.BatchOutcome;
import com.visualspider.result.spi.ResultRecord;
import com.visualspider.result.spi.RunEventInput;
import com.visualspider.result.spi.RunEventLevel;
import com.visualspider.result.spi.RunResultSink;
import com.visualspider.run.spi.RunExecutionContext;
import com.visualspider.run.spi.RunExecutor;
import com.visualspider.run.spi.RunPageHandle;
import com.visualspider.run.spi.RunState;
import com.visualspider.run.spi.StopReason;
import com.visualspider.task.domain.TaskDefinition;
import com.visualspider.task.domain.TaskMode;
import com.visualspider.task.domain.TaskSnapshot;
import com.visualspider.visualbrowser.spi.TargetUrlPolicy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 多页 List 运行执行器（M5-2 / issue #40 / spec §D4；M5-3 / issue #41 叠加完整翻页循环）。
 *
 * <p>镜像 {@link ListRunExecutor} 的单页处理路径（M4 spec §D8：导航入口 ->
 * waitForSelector -> per-item scopeToNode + preview -> writeOneRecord -> sink 实时去重
 * -> 计数累加），翻页 / 加载更多 / 重复页保护 / 无新增判定委托
 * {@link PagingExecutor}（M5-3 / spec §D5）：{@code paginationRule == null} 时只跑
 * 当前页（等价 M4 行为，隐式升级）。
 *
 * <p>事件沿用 M4 逐 item 事件码（LIST_ITER_START / LIST_ITEM_*），叠加
 * LIST_PAGE_LOADED / PAGINATION_CLICKED / PAGINATION_STOPPED / DUPLICATE_PAGE
 * （spec §D17；翻页事件由 {@link PagingExecutor} 发）。
 *
 * <p>终态（与 {@link ListRunExecutor} 一致，spec §D7）：
 * {@code final>0 && fail>0} -> PARTIAL_SUCCESS；翻页主动停止
 * （DUPLICATE_PAGE / PAGINATION_*）在 SUCCESS 终态下作为 stop_reason 记录。
 */
public class MultiPageRunExecutor implements RunExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(MultiPageRunExecutor.class);

    /** list-item 就绪等待（与 M4 ListRunExecutor 一致：默认 15s）。 */
    private static final long LIST_ITEM_WAIT_MS = 15_000L;

    private final RunRepository repository;
    private final RunResultSink resultSink;
    private final ExtractionPreview preview;
    private final TargetUrlPolicy urlPolicy;
    private final UniqueKeyHasher hasher;
    private final PagingExecutor pagingExecutor;

    public MultiPageRunExecutor(RunRepository repository,
                                RunResultSink resultSink,
                                ExtractionPreview preview,
                                TargetUrlPolicy urlPolicy,
                                UniqueKeyHasher hasher) {
        this(repository, resultSink, preview, urlPolicy, hasher,
                new PagingExecutor(resultSink));
    }

    public MultiPageRunExecutor(RunRepository repository,
                                RunResultSink resultSink,
                                ExtractionPreview preview,
                                TargetUrlPolicy urlPolicy,
                                UniqueKeyHasher hasher,
                                PagingExecutor pagingExecutor) {
        this.repository = repository;
        this.resultSink = resultSink;
        this.preview = preview;
        this.urlPolicy = urlPolicy;
        this.hasher = hasher;
        this.pagingExecutor = pagingExecutor;
    }

    @Override
    public void execute(RunExecutionContext context, long runId) {
        // 2 参 RunExecutor SPI（spec §D4）：内部自取 snapshot，镜像 ListRunExecutor。
        RunRepository.RunRecord rec = repository.findById(runId).orElse(null);
        if (rec == null) {
            LOG.error("MultiPageRunExecutor: run not found runId={}", runId);
            tryEmitTerminal(runId, RunState.FAILED, StopReason.BROWSER_START_FAILED,
                    "run not found");
            return;
        }
        TaskSnapshot snapshot = rec.snapshot();
        TaskDefinition def = snapshot.definition();
        if (!(def.mode() instanceof TaskMode.List)) {
            throw new IllegalArgumentException(
                    "MultiPageRunExecutor 收到非 list 任务 mode=" + def.mode());
        }
        RunPageHandle page = context == null ? null : context.pageHandle();
        if (page == null) {
            LOG.error("MultiPageRunExecutor: 缺少 page handle runId={}", runId);
            tryEmitTerminal(runId, RunState.FAILED, StopReason.BROWSER_START_FAILED,
                    "no page handle");
            return;
        }
        try {
            runMultiPage(context, runId, def, page);
        } catch (RuntimeException unexpected) {
            LOG.error("MultiPageRunExecutor: unexpected failure runId={}", runId, unexpected);
            tryEmitTerminal(runId, RunState.FAILED, StopReason.PAGE_RETRY_EXHAUSTED,
                    "unexpected: " + safeMessage(unexpected));
        } finally {
            try {
                page.close();
            } catch (RuntimeException ignore) {
                // ignore
            }
        }
    }

    private void runMultiPage(RunExecutionContext context, long runId,
                              TaskDefinition def, RunPageHandle page) {
        try {
            urlPolicy.validate(def.startUrl());
        } catch (RuntimeException ex) {
            tryEmitTerminal(runId, RunState.FAILED, StopReason.ENTRY_FAILED,
                    "invalid start url");
            return;
        }
        RunPageHandle.NavigationResult nav = page.navigateAndAwaitDomContentLoaded(def.startUrl());
        if (!nav.ok()) {
            tryEmitTerminal(runId, RunState.FAILED, StopReason.ENTRY_FAILED,
                    "navigate failed: " + nav.errorMessage());
            return;
        }
        if (!page.waitForSelector(def.listItemRule().selector(), LIST_ITEM_WAIT_MS)) {
            tryEmitTerminal(runId, RunState.FAILED, StopReason.ENTRY_FAILED,
                    "list-item selector not found within 15s");
            return;
        }
        if (def.waitPolicy().extraWaitSeconds() > 0) {
            page.extraWaitSeconds(def.waitPolicy().extraWaitSeconds());
        }

        int sequenceNo = 0;  // 跨页连续（run_result UNIQUE(run_id, sequence_no)）
        PagingExecutor.LoopStop stop = pagingExecutor.runPages(def, page, context, runId,
                (pageNo, skipFirstN, dom, items) ->
                        processListPage(runId, context, def, page,
                                pageNo, skipFirstN, dom, items, new int[]{sequenceNo}));
        if (stop.kind() == PagingExecutor.LoopStop.Kind.ABORTED) {
            if (stop.reason() != null) {
                // 翻页后 list-item 消失等需要 FAILED 终态的路径（processor 内已写终态时 reason == null）
                tryEmitTerminal(runId, RunState.FAILED, stop.reason(), stop.message());
            }
            return;
        }
        computeTerminal(context, runId, def, stop.reason());
    }

    /**
     * 单张 list 页处理（M5-3 起由 {@link PagingExecutor} 回调）：
     * 每 item scopeToNode + preview -> writeOneRecord -> sink 实时去重。
     *
     * <p>LOAD_MORE 追加式列表由 {@code skipFirstN} 跳过已处理前缀，只处理新增 item。
     * {@code sequenceRef} 为单元素数组，跨页保持 sequence 连续（lambda 捕获语义）。
     */
    private boolean processListPage(long runId, RunExecutionContext context, TaskDefinition def,
                                    RunPageHandle page, int pageNo, int skipFirstN,
                                    DomState dom, List<Node> items, int[] sequenceRef) {
        emitEvent(runId, RunEventLevel.INFO, "LIST_PAGE_LOADED", page.currentUrl(),
                "page=" + pageNo + " items=" + items.size());
        // M4 逐 item 事件码沿用（ListRunIT 回归依赖）
        emitEvent(runId, RunEventLevel.INFO, "LIST_ITER_START", page.currentUrl(),
                "items=" + items.size());
        for (int i = skipFirstN; i < items.size(); i++) {
            Node item = items.get(i);
            if (context.isCancelRequested() || context.recordLimitExceeded()
                    || context.pageLimitExceeded()
                    || context.timeLimitExceeded(System.currentTimeMillis())) {
                break;
            }
            DomState scoped;
            try {
                scoped = dom.scopeToNode(item);
            } catch (UnsupportedOperationException noScope) {
                scoped = dom;
            }
            PreviewResult pr = preview.preview(def, scoped);
            context.incrementPageCount();
            int seq = ++sequenceRef[0];
            BatchOutcome outcome = writeOneRecord(runId, pr, def, seq,
                    page.currentUrl());
            if (outcome.failedCount() > 0) {
                emitEvent(runId, RunEventLevel.WARN, "LIST_ITEM_FAILED", page.currentUrl(),
                        "seq=" + seq);
            } else if (outcome.dedupCount() > 0) {
                emitEvent(runId, RunEventLevel.INFO, "LIST_ITEM_DEDUPED", page.currentUrl(),
                        "seq=" + seq);
            } else if (outcome.insertedCount() > 0) {
                emitEvent(runId, RunEventLevel.INFO, "LIST_ITEM_EXTRACTED", page.currentUrl(),
                        "seq=" + seq);
            }
        }
        return true;
    }

    private BatchOutcome writeOneRecord(long runId, PreviewResult pr, TaskDefinition def,
                                        int sequenceNo, String finalUrl) {
        // 缺字段 cleanedValue == null 时不入 data map，让 UniqueKeyHasher 走
        // "全/部分空键 -> null" 路径（与 M4 ListRunExecutor / UniqueKeyHasher 语义一致）。
        Map<String, String> data = new LinkedHashMap<>();
        for (PreviewResult.FieldOutcome out : pr.fieldOutcomes()) {
            String value = out.cleanedValue();
            if (value != null) {
                data.put(out.fieldName(), value);
            }
        }
        byte[] hash = null;
        if (def.uniqueKey() != null && !def.uniqueKey().isEmpty()) {
            hash = hasher.hash(def.uniqueKey(), data);
        }
        ResultRecord record = ResultRecord.forInsertWithKey(runId, sequenceNo, data, hash);
        RunEventInput ev = new RunEventInput(RunEventLevel.INFO, "list-item",
                finalUrl, null, "seq=" + sequenceNo);
        try {
            return resultSink.appendBatch(runId, List.of(record), List.of(ev));
        } catch (RuntimeException ex) {
            LOG.warn("appendBatch failed runId={}: {}", runId, safeMessage(ex));
            return new BatchOutcome(1, 0, 0, 1);
        }
    }

    private void computeTerminal(RunExecutionContext context, long runId,
                                  TaskDefinition def, StopReason paginationStop) {
        RunRepository.RunRecord rec = repository.findById(runId).orElse(null);
        if (rec == null) {
            return;
        }
        int finalCount = rec.recordCountFinal();
        int failCount = rec.failCount();
        RunState state;
        StopReason reason;
        if (finalCount > 0 && failCount > 0) {
            state = RunState.PARTIAL_SUCCESS;
            reason = StopReason.COMPLETED;  // PARTIAL_SUCCESS 也带 COMPLETED（spec §D7）
        } else if (finalCount == 0 && failCount > 0) {
            state = RunState.FAILED;
            reason = StopReason.PAGE_RETRY_EXHAUSTED;
        } else {
            state = RunState.SUCCESS;
            reason = StopReason.COMPLETED;
        }
        if (context.isCancelRequested()) {
            state = RunState.CANCELLED;
            reason = StopReason.USER_CANCEL;
        }
        if (context.pageLimitExceeded()) reason = StopReason.PAGE_LIMIT;
        if (context.recordLimitExceeded()) reason = StopReason.RECORD_LIMIT;
        if (context.timeLimitExceeded(System.currentTimeMillis())) reason = StopReason.TIME_LIMIT;
        // M5-3（spec §D5）：翻页主动停止（DUPLICATE_PAGE / PAGINATION_*）优先于 COMPLETED，
        // 但不影响 SUCCESS / PARTIAL_SUCCESS 状态本身与取消 / 达限判定。
        if (paginationStop != null && state == RunState.SUCCESS) {
            reason = paginationStop;
        }

        tryEmitTerminal(runId, state, reason,
                "final=" + finalCount + " fail=" + failCount);
    }

    private void tryEmitTerminal(long runId, RunState state, StopReason reason, String msg) {
        // 先发 terminal 事件，再写终态；保持与"结果写 -> 事件写 -> 终态写"顺序（镜像 ListRunExecutor）。
        String safeMsg = msg == null ? "" : msg;
        try {
            resultSink.appendBatch(runId, List.of(), List.of(
                    new RunEventInput(RunEventLevel.INFO, "terminal", null,
                            state.name() + "/" + (reason == null ? "null" : reason.name()),
                            safeMsg)));
        } catch (RuntimeException ignored) {
            // ignore
        }
        try {
            repository.markTerminal(runId, state, reason);
        } catch (RuntimeException ignored) {
            // ignore
        }
    }

    private void emitEvent(long runId, RunEventLevel level, String stage, String url, String msg) {
        try {
            resultSink.appendBatch(runId, List.of(), List.of(
                    new RunEventInput(level, stage, url, null, msg)));
        } catch (RuntimeException ignored) {
            // ignore
        }
    }

    private static String safeMessage(Throwable t) {
        return t == null ? "" : (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
    }
}
