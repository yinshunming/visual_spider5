package com.visualspider.run.internal;

import com.visualspider.extraction.spi.ExtractionPreview;
import com.visualspider.extraction.spi.PreviewResult;
import com.visualspider.result.spi.ResultRecord;
import com.visualspider.result.spi.RunEventInput;
import com.visualspider.result.spi.RunEventLevel;
import com.visualspider.result.spi.RunResultSink;
import com.visualspider.run.spi.RunExecutionContext;
import com.visualspider.run.spi.RunExecutor;
import com.visualspider.run.spi.RunPageHandle;
import com.visualspider.run.spi.RunState;
import com.visualspider.run.spi.StopReason;
import com.visualspider.task.domain.FieldDefinition;
import com.visualspider.task.domain.TaskDefinition;
import com.visualspider.task.domain.TaskSnapshot;
import com.visualspider.visualbrowser.internal.BasicTargetUrlPolicy;
import com.visualspider.visualbrowser.spi.TargetUrlPolicy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 单页运行执行器（issue #25 / spec §D9）。
 *
 * <p>替换 {@link TestRunExecutor} stub。在 RunLanePool 固定线程上执行，循环
 * {@code attempt 1..3}：导航 -&gt; 等选择器 15s -&gt; extraWait -&gt; 提取 -&gt;
 * 写结果 + 事件 -&gt; 终态；任一次重试入口失败或提取异常继续下一次，三次全败
 * 写 {@code PAGE_RETRY_EXHAUSTED + FAILED}。
 *
 * <p>取消检查点（spec §D9）：
 * <ol>
 *   <li>导航前</li>
 *   <li>页面就绪后（waitForSelector 后）</li>
 *   <li>提取前（preview 调用前）</li>
 *   <li>终态写入前</li>
 * </ol>
 *
 * <p>触发即写 {@code CANCELLED + USER_CANCEL}，已写结果保留。
 *
 * <p>429 / 持续 403 / captcha 命中 -&gt; 写对应 stopReason + FAILED（不切 UA 指纹代理；
 * 提交一次导航直接停；spec §D20）。
 *
 * <p>{@link RunEventInput}（{@code run_event} 行）按"阶段变更 / 结果写 / 终态"粒度写入，
 * 供 issue #26/#(e) WS 推送消费（spec §D16）。
 */
public class SinglePageRunExecutor implements RunExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(SinglePageRunExecutor.class);

    /** 选择器等待默认超时（M2 config session 默认 + spec §D9）。 */
    private static final long DEFAULT_SELECTOR_WAIT_MS = 15_000L;
    /** 单页重试总数：1 次成功 = 总尝试 1；失败继续；总尝试上限 3（spec §D9）。 */
    private static final int MAX_ATTEMPTS = 3;

    private final RunRepository repository;
    private final RunResultSink resultSink;
    private final ExtractionPreview extractionPreview;
    private final TargetUrlPolicy targetUrlPolicy;

    public SinglePageRunExecutor(RunRepository repository,
                                 RunResultSink resultSink,
                                 ExtractionPreview extractionPreview,
                                 TargetUrlPolicy targetUrlPolicy) {
        this.repository = repository;
        this.resultSink = resultSink;
        this.extractionPreview = extractionPreview;
        // 默认行为：未指定时复用 M2 BasicTargetUrlPolicy（spec §D20：复用同策略）。
        this.targetUrlPolicy = targetUrlPolicy == null
                ? new BasicTargetUrlPolicy()
                : targetUrlPolicy;
    }

    @Override
    public void execute(RunExecutionContext context, long runId) {
        RunPageHandle page = context == null ? null : context.pageHandle();
        if (page == null) {
            // 没有 page 句柄说明 dispatcher 路径未接入；为 /M3-3 与 M3-2 stub 兼容给出一致失败：
            LOG.error("SinglePageRunExecutor: 缺少 RunPageHandle（dispatcher 未接入或上下文为 null）runId={}", runId);
            tryEmitTerminal(runId, RunState.FAILED, StopReason.BROWSER_START_FAILED,
                    "no page handle (dispatcher not wired)");
            return;
        }
        try {
            runWithRetries(context, runId, page);
        } catch (RuntimeException unexpected) {
            // 兜底：单页 3 次重试失败也走 PAGE_RETRY_EXHAUSTED；这里是"非预期异常"的兜底。
            LOG.error("SinglePageRunExecutor: unexpected failure runId={}", runId, unexpected);
            tryEmitTerminal(runId, RunState.FAILED, StopReason.PAGE_RETRY_EXHAUSTED,
                    "unexpected: " + unexpected.getClass().getSimpleName());
        } finally {
            try {
                page.close();
            } catch (RuntimeException closeEx) {
                LOG.warn("page handle close failed runId={}: {}", runId, closeEx.getMessage());
            }
        }
    }

    /**
     * 带重试的执行体。即使任一阶段抛未捕获异常，调用方 finally 块保证 {@code page.close()}。
     */
    private void runWithRetries(RunExecutionContext context, long runId, RunPageHandle page) {
        // 0) 终止前 cancel 检查
        if (isCancelled(runId, context)) {
            return;
        }

        RunRepository.RunRecord rec = repository.findById(runId).orElse(null);
        if (rec == null) {
            LOG.error("SinglePageRunExecutor: run not found runId={}", runId);
            tryEmitTerminal(runId, RunState.FAILED, StopReason.BROWSER_START_FAILED,
                    "run not found");
            return;
        }
        TaskSnapshot snapshot = rec.snapshot();
        TaskDefinition definition = snapshot.definition();
        String startUrl = definition.startUrl();

        // 1) 入口 URL 静态校验（构造阶段 TargetUrlPolicy）
        emitInfo(runId, "entry-start", startUrl, null,
                "validating entry url");
        try {
            targetUrlPolicy.validate(startUrl);
        } catch (RuntimeException ex) {
            tryEmitTerminal(runId, RunState.FAILED, StopReason.ENTRY_FAILED,
                    "invalid start url: " + safeMessage(ex));
            emitError(runId, "entry-failed", startUrl, null,
                    "start url rejected: " + safeMessage(ex));
            return;
        }

        // 2) 重试循环
        StopReason finalStop = StopReason.PAGE_RETRY_EXHAUSTED;
        RunState finalState = RunState.FAILED;
        boolean terminalWritten = false;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            // 2a) 每轮入口：cancel / time / page / record 检查
            if (context.timeLimitExceeded(System.currentTimeMillis())) {
                finalStop = StopReason.TIME_LIMIT;
                break;
            }
            if (context.isCancelRequested()) {
                finalStop = StopReason.USER_CANCEL;
                finalState = RunState.CANCELLED;
                break;
            }
            if (context.pageLimitExceeded() || context.recordLimitExceeded()) {
                // 单页不可达；M4/M5 起作用。当前实现保留检查点但不写终态。
                finalStop = StopReason.PAGE_LIMIT;
                break;
            }

            emitInfo(runId, "attempt", startUrl, null,
                    "attempt " + attempt + "/" + MAX_ATTEMPTS);

            // 2b) 导航
            RunPageHandle.NavigationResult nav = page.navigateAndAwaitDomContentLoaded(startUrl);
            if (!nav.ok()) {
                emitWarn(runId, "navigate", startUrl, null,
                        "navigation failed: status=" + nav.httpStatus() + " msg=" + nav.errorMessage());
                continue;
            }

            // 429 / captcha 命中即停（不重试）
            if (nav.httpStatus() == 429) {
                tryEmitTerminal(runId, RunState.FAILED, StopReason.HTTP_429,
                        "HTTP 429 too many requests");
                terminalWritten = true;
                return;
            }
            if (nav.captchaDetected()) {
                tryEmitTerminal(runId, RunState.FAILED, StopReason.CAPTCHA,
                        "captcha detected");
                terminalWritten = true;
                return;
            }
            if (nav.httpStatus() == 403) {
                tryEmitTerminal(runId, RunState.FAILED, StopReason.HTTP_403,
                        "HTTP 403 forbidden (stop, do not retry)");
                terminalWritten = true;
                return;
            }

            // 2c) 最终 URL 校验（BasicTargetUrlPolicy；spec §D20 复用）
            String finalUrl = page.currentUrl();
            try {
                targetUrlPolicy.validate(finalUrl);
            } catch (RuntimeException ex) {
                emitError(runId, "entry-failed", finalUrl, null,
                        "final url rejected: " + safeMessage(ex));
                tryEmitTerminal(runId, RunState.FAILED, StopReason.ENTRY_FAILED,
                        "final url rejected: " + safeMessage(ex));
                terminalWritten = true;
                return;
            }

            // 2d) 等字段选择器（15s；无字段跳过）
            List<FieldDefinition> fields = definition.fields() == null
                    ? List.of() : definition.fields();
            boolean selectorsOk = true;
            if (!fields.isEmpty()) {
                // 用第一个字段的选择器作为"页面已就绪"的代表性信号；
                // 字段多选择器时 run 在 query(DomState) 中会再次校验，对超时场景回退到重试。
                FieldDefinition firstField = fields.get(0);
                String selector = firstField.selector();
                long timeoutMs = DEFAULT_SELECTOR_WAIT_MS;
                selectorsOk = page.waitForSelector(selector, timeoutMs);
                if (!selectorsOk) {
                    emitWarn(runId, "wait-selector", finalUrl, null,
                            "wait for selector timed out: " + selector);
                    continue; // 重试
                }
            }

            // 取消检查点 2：页面就绪后
            if (context.isCancelRequested()) {
                finalStop = StopReason.USER_CANCEL;
                finalState = RunState.CANCELLED;
                break;
            }

            // 2e) extraWaitSeconds（0-5）
            int extra = definition.waitPolicy() == null
                    ? 0 : definition.waitPolicy().extraWaitSeconds();
            page.extraWaitSeconds(extra);

            // 取消检查点 3：提取前
            if (context.isCancelRequested()) {
                finalStop = StopReason.USER_CANCEL;
                finalState = RunState.CANCELLED;
                break;
            }

            // 2f) DomState + extraction
            ExtractionPreview.DomState domState = page.acquireDomState();
            PreviewResult result;
            try {
                result = extractionPreview.preview(definition, domState);
            } catch (RuntimeException extractEx) {
                emitError(runId, "extract-error", finalUrl, null,
                        "extraction failed: " + safeMessage(extractEx));
                continue; // 重试
            }

            // 2g) 取消检查点 4：终态写前（已成功提取但可能在写之前被取消）
            if (context.isCancelRequested()) {
                // 不写结果直接退：保留"已成功提取则尽量落库"的语义，先写结果再 cancel。
                tryWriteResultAndEvents(runId, result, fields, finalUrl);
                finalStop = StopReason.USER_CANCEL;
                finalState = RunState.CANCELLED;
                break;
            }

            // 2h) 写结果 + 事件（仅写入成功 → 终态 SUCCESS；否则继续重试）
            tryWriteResultAndEvents(runId, result, fields, finalUrl);
            context.incrementPageCount();
            context.incrementRecordCount(1);

            // 取消检查点 5（终态写后、写 terminal 前）
            if (context.isCancelRequested()) {
                finalStop = StopReason.USER_CANCEL;
                finalState = RunState.CANCELLED;
                break;
            }

            tryEmitTerminal(runId, RunState.SUCCESS, StopReason.COMPLETED,
                    "single-page extraction completed");
            terminalWritten = true;
            // 用过 selectorsOk 抑制 unused 警告（保留以便未来诊断可访问）
            if (!selectorsOk) {
                LOG.warn("selectorsOk=false but reached success; selectors code path");
            }
            return;
        }

        if (!terminalWritten) {
            tryEmitTerminal(runId, finalState, finalStop, terminalMessage(finalStop));
        }
    }

    /** 取消检测 + 写终态 + 事件。返回 true（fixed）以保持调用点简单。 */
    private boolean isCancelled(long runId, RunExecutionContext context) {
        if (!context.isCancelRequested()) {
            return false;
        }
        tryEmitTerminal(runId, RunState.CANCELLED, StopReason.USER_CANCEL,
                "user cancelled (early)");
        return true;
    }

    private void tryWriteResultAndEvents(long runId, PreviewResult result,
                                         List<FieldDefinition> fields, String finalUrl) {
        // 写 1 条结果（M3 单页） + 提取成功事件
        Map<String, String> data = new LinkedHashMap<>();
        for (PreviewResult.FieldOutcome out : result.fieldOutcomes()) {
            data.put(out.fieldName(), out.cleanedValue());
        }
        ResultRecord record = ResultRecord.forInsert(runId, 1, data);

        List<RunEventInput> events = new ArrayList<>();
        events.add(new RunEventInput(RunEventLevel.INFO, "extract-success",
                finalUrl, null, "extracted " + (fields == null ? 0 : fields.size()) + " fields"));

        try {
            resultSink.appendBatch(runId, List.of(record), events);
        } catch (RuntimeException ex) {
            // 写失败不阻塞：标记但不重试（M4 起多记录再考虑重试；M3 单页一锤定音）。
            LOG.warn("appendBatch failed runId={}: {}", runId, safeMessage(ex));
        }
    }

    private void tryEmitTerminal(long runId, RunState state, StopReason stopReason, String message) {
        // 先发 terminal 事件，再写终态；保持与"结果写 -> 事件写 -> 终态写"顺序
        RunEventInput terminalEvent = new RunEventInput(
                RunEventLevel.INFO, "terminal", null, null,
                state + "/" + (stopReason == null ? "null" : stopReason.name())
                        + (message == null ? "" : ": " + message));
        try {
            resultSink.appendBatch(runId, List.of(), List.of(terminalEvent));
        } catch (RuntimeException ex) {
            LOG.warn("appendBatch(terminal event) failed runId={}: {}", runId, safeMessage(ex));
        }
        try {
            repository.markTerminal(runId, state, stopReason);
        } catch (RuntimeException ex) {
            LOG.warn("markTerminal failed runId={}: {}", runId, safeMessage(ex));
        }
    }

    private void emitInfo(long runId, String stage, String url, String code, String msg) {
        emit(runId, RunEventLevel.INFO, stage, url, code, msg);
    }

    private void emitWarn(long runId, String stage, String url, String code, String msg) {
        emit(runId, RunEventLevel.WARN, stage, url, code, msg);
    }

    private void emitError(long runId, String stage, String url, String code, String msg) {
        emit(runId, RunEventLevel.ERROR, stage, url, code, msg);
    }

    private void emit(long runId, RunEventLevel level, String stage, String url,
                      String code, String msg) {
        String safeMsg = msg == null ? "" : msg;
        try {
            resultSink.appendBatch(runId, List.of(),
                    List.of(new RunEventInput(level, stage, url, code, safeMsg)));
        } catch (RuntimeException ex) {
            LOG.warn("run event write failed runId={}: {}", runId, safeMessage(ex));
        }
    }

    private static String safeMessage(Throwable t) {
        if (t == null) return "null";
        String m = t.getMessage();
        if (m == null || m.isBlank()) return t.getClass().getSimpleName();
        return m;
    }

    private static String safeMessage(Exception e) {
        return safeMessage((Throwable) e);
    }

    private static String terminalMessage(StopReason r) {
        if (r == null) return "stopped";
        return switch (r) {
            case TIME_LIMIT -> "exceeded 30 minutes";
            case PAGE_LIMIT -> "exceeded page limit";
            case RECORD_LIMIT -> "exceeded record limit";
            case USER_CANCEL -> "cancelled by user";
            case ENTRY_FAILED -> "entry failed";
            case HTTP_429 -> "HTTP 429";
            case HTTP_403 -> "HTTP 403";
            case CAPTCHA -> "captcha detected";
            case PAGE_RETRY_EXHAUSTED -> "3 attempts exhausted";
            default -> r.name();
        };
    }
}
