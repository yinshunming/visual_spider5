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
import com.visualspider.task.domain.SelectorType;
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
 * List 模式运行执行（M4 spec §D8）。
 *
 * <p>骨架继承 {@link SinglePageRunExecutor}：
 * WAITING → RUNNING → {@code RunPageHandle} → 导航入口 → waitForSelector → 每个 item
 * → writeOneRecord → sink 实时去重 → 计数累加 → 终态判定。
 *
 * <p>终态（spec §D7）：{@code final>0 && fail>0} → PARTIAL_SUCCESS；其它见下。
 */
public class ListRunExecutor implements RunExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(ListRunExecutor.class);

    private final RunRepository repository;
    private final RunResultSink resultSink;
    private final ExtractionPreview preview;
    private final TargetUrlPolicy urlPolicy;
    private final UniqueKeyHasher hasher;

    public ListRunExecutor(RunRepository repository,
                           RunResultSink resultSink,
                           ExtractionPreview preview,
                           TargetUrlPolicy urlPolicy,
                           UniqueKeyHasher hasher) {
        this.repository = repository;
        this.resultSink = resultSink;
        this.preview = preview;
        this.urlPolicy = urlPolicy;
        this.hasher = hasher;
    }

    @Override
    public void execute(RunExecutionContext context, long runId) {
        // 2 参 RunExecutor SPI（spec §D8）：内部自取 snapshot，镜像 SinglePageRunExecutor。
        RunRepository.RunRecord rec = repository.findById(runId).orElse(null);
        if (rec == null) {
            LOG.error("ListRunExecutor: run not found runId={}", runId);
            tryEmitTerminal(runId, RunState.FAILED, StopReason.BROWSER_START_FAILED,
                    "run not found");
            return;
        }
        TaskSnapshot snapshot = rec.snapshot();
        TaskDefinition def = snapshot.definition();
        if (!(def.mode() instanceof TaskMode.List)) {
            throw new IllegalArgumentException(
                    "ListRunExecutor 收到非 list 任务 mode=" + def.mode());
        }
        RunPageHandle page = context == null ? null : context.pageHandle();
        if (page == null) {
            LOG.error("ListRunExecutor: 缺少 page handle runId={}", runId);
            tryEmitTerminal(runId, RunState.FAILED, StopReason.BROWSER_START_FAILED,
                    "no page handle");
            return;
        }
        try {
            runList(context, runId, def, page);
        } catch (RuntimeException unexpected) {
            LOG.error("ListRunExecutor: unexpected failure runId={}", runId, unexpected);
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

    private void runList(RunExecutionContext context, long runId,
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
        boolean found = page.waitForSelector(def.listItemRule().selector(), 15_000);
        if (!found) {
            tryEmitTerminal(runId, RunState.FAILED, StopReason.ENTRY_FAILED,
                    "list-item selector not found within 15s");
            return;
        }
        if (def.waitPolicy().extraWaitSeconds() > 0) {
            page.extraWaitSeconds(def.waitPolicy().extraWaitSeconds());
        }
        DomState dom = page.acquireDomState();
        // 正式运行：uncapped iteration（spec §D8）；previewList 的 20 cap 仅用于 preview UI（§D9）。
        // 直接 dom.query(listItemRule) + per-item scopeToNode + preview，避免 previewList 隐式截断。
        SelectorType itemType = def.listItemRule().selectorType() == null
                ? SelectorType.CSS : def.listItemRule().selectorType();
        List<Node> items;
        try {
            items = dom.query(def.listItemRule().selector(), itemType);
        } catch (RuntimeException ex) {
            tryEmitTerminal(runId, RunState.FAILED, StopReason.ENTRY_FAILED,
                    "listItemRule query failed: " + safeMessage(ex));
            return;
        }
        tryEmitEvent(runId, RunEventLevel.INFO, "list-iter-start", page.currentUrl(), null,
                "items=" + items.size());
        int sequenceNo = 1;
        for (Node item : items) {
            if (context.isCancelRequested() || context.recordLimitExceeded()
                    || context.pageLimitExceeded() || context.timeLimitExceeded(System.currentTimeMillis())) {
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
            int seq = sequenceNo++;
            BatchOutcome outcome = writeOneRecord(runId, pr, def, seq,
                    page.currentUrl());
            if (outcome.failedCount() > 0) {
                tryEmitEvent(runId, RunEventLevel.WARN, "LIST_ITEM_FAILED", page.currentUrl(),
                        "LIST_ITEM_FAILED", "seq=" + seq);
            } else if (outcome.dedupCount() > 0) {
                tryEmitEvent(runId, RunEventLevel.INFO, "LIST_ITEM_DEDUPED", page.currentUrl(),
                        null, "seq=" + seq);
            } else if (outcome.insertedCount() > 0) {
                tryEmitEvent(runId, RunEventLevel.INFO, "LIST_ITEM_EXTRACTED", page.currentUrl(),
                        null, "seq=" + seq);
            }
        }
        computeTerminal(context, runId, def);
    }

    private BatchOutcome writeOneRecord(long runId, PreviewResult pr, TaskDefinition def,
                                        int sequenceNo, String finalUrl) {
        // 过滤掉 cleanedValue 为 null 的字段（list-item 缺字段是常见场景，spec §D7 不要求失败，
        // 但 ResultRecord 的 Map.copyOf 不允许 null value）。保留 key 集合与 task 定义一致，便于
        // hash 计算与去重语义稳定。
        Map<String, String> data = new LinkedHashMap<>();
        for (PreviewResult.FieldOutcome out : pr.fieldOutcomes()) {
            String value = out.cleanedValue();
            if (value == null) {
                value = "";
            }
            data.put(out.fieldName(), value);
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
                                  TaskDefinition def) {
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

        tryEmitTerminal(runId, state, reason,
                "final=" + finalCount + " fail=" + failCount);
        // tryEmitTerminal 已写终态（镜像 SinglePageRunExecutor），此处不再重复 markTerminal。
    }

    private void tryEmitTerminal(long runId, RunState state, StopReason reason, String msg) {
        // 先发 terminal 事件，再写终态；保持与"结果写 -> 事件写 -> 终态写"顺序。
        // 镜像 SinglePageRunExecutor.tryEmitTerminal：早退路径（run-not-found / page-null /
        // unexpected）若只发事件不写终态，run 会永久卡在 RUNNING，下次启动恢复扫描兜底。
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

    private void tryEmitEvent(long runId, RunEventLevel level, String stage, String url,
                              String code, String msg) {
        try {
            resultSink.appendBatch(runId, List.of(), List.of(
                    new RunEventInput(level, stage, url, code, msg)));
        } catch (RuntimeException ignored) {
            // ignore
        }
    }

    private static String safeMessage(Throwable t) {
        return t == null ? "" : (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
    }
}
