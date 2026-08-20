package com.visualspider.run.internal;

import com.visualspider.extraction.spi.ExtractionPreview.DomState;
import com.visualspider.extraction.spi.ExtractionPreview.Node;
import com.visualspider.result.spi.RunEventInput;
import com.visualspider.result.spi.RunEventLevel;
import com.visualspider.result.spi.RunResultSink;
import com.visualspider.run.spi.RunExecutionContext;
import com.visualspider.run.spi.RunPageHandle;
import com.visualspider.run.spi.RunPageHandle.ClickResult;
import com.visualspider.run.spi.StopReason;
import com.visualspider.task.domain.NavigationMode;
import com.visualspider.task.domain.PaginationRule;
import com.visualspider.task.domain.SelectorType;
import com.visualspider.task.domain.TaskDefinition;
import com.visualspider.visualbrowser.spi.PacingPolicy;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 翻页 / 加载更多循环（M5-3 / issue #41 / spec §D5）。
 *
 * <p>由 {@link MultiPageRunExecutor} 在初始页导航 + list-item 就绪后调用，负责：
 * <ul>
 *   <li>NEXT_PAGE：点击后 URL + list-item 集合 SHA-256 双重比对，命中已访问页 ->
 *       {@link StopReason#DUPLICATE_PAGE}（spec §D5 重复页保护）。</li>
 *   <li>LOAD_MORE：点击后等 DOM 稳定 1s，list-item 数不增长连续 2 次 ->
 *       {@link StopReason#PAGINATION_NO_NEW_ITEMS}；追加式列表只把新增 item
 *       交给回调（skipFirstN 跳过已处理前缀）。</li>
 *   <li>元素消失：LOAD_MORE -> {@link StopReason#PAGINATION_DISAPPEARED}；
 *       NEXT_PAGE 末页"下一页"移除是自然翻完信号（保持 M5-2 的 COMPLETED 语义）。</li>
 *   <li>元素 disabled / aria-disabled -> {@link StopReason#PAGINATION_DISABLED}。</li>
 * </ul>
 *
 * <p>防抖：循环每轮至多点击一次翻页元素（同一 list page 内不重复触发）。
 * 取消 / 达限检查点在每次点击前（协作式取消，spec §D5）。
 *
 * <p>事件（spec §D17）：{@code PAGINATION_CLICKED} / {@code PAGINATION_STOPPED} /
 * {@code DUPLICATE_PAGE}；message 只记 url / reason，不记堆栈与页面内容。
 */
final class PagingExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(PagingExecutor.class);

    /** list-item 就绪等待（与 M4 ListRunExecutor 一致：默认 15s）。 */
    static final long LIST_ITEM_WAIT_MS = 15_000L;
    /** 翻页元素等待：最后一页元素消失时等满该超时即判定 NOT_FOUND。 */
    static final long PAGINATION_WAIT_MS = 5_000L;
    /** LOAD_MORE 点击后 DOM 稳定期（spec §D5：等 1s 再判定无新增）。 */
    static final long LOAD_MORE_SETTLE_MS = 1_000L;
    /** LOAD_MORE 连续无新增次数保险阈值（spec §D5：连续 2 次即停）。 */
    static final int NO_NEW_ITEMS_THRESHOLD = 2;

    private final RunResultSink resultSink;
    private final ContentHasher contentHasher;
    private final PacingPolicy pacingPolicy;

    PagingExecutor(RunResultSink resultSink) {
        this(resultSink, new ContentHasher(), null);
    }

    PagingExecutor(RunResultSink resultSink, PacingPolicy pacingPolicy) {
        this(resultSink, new ContentHasher(), pacingPolicy);
    }

    PagingExecutor(RunResultSink resultSink, ContentHasher contentHasher, PacingPolicy pacingPolicy) {
        this.resultSink = resultSink;
        this.contentHasher = contentHasher;
        this.pacingPolicy = pacingPolicy;
    }

    /** 每张 list 页回调：处理当前页 items（含初始页 + 每张翻页后页）。 */
    interface PageProcessor {
        /**
         * @param pageNo    从 1 开始的 list 页序号
         * @param skipFirstN LOAD_MORE 追加式列表中已处理过的前缀条数（NEXT_PAGE 恒 0）
         * @param dom       本页 DomState（query / scopeToNode 用同一实例）
         * @param items     listItemRule 当前命中集合（顺序与 DOM 一致）
         * @return false 表示页内处理已写终态，终止整个循环
         */
        boolean processPage(int pageNo, int skipFirstN, DomState dom, List<Node> items);
    }

    /**
     * 翻页循环终止信息。
     *
     * <ul>
     *   <li>{@code NATURAL}：自然翻完（NEXT_PAGE 末页元素消失）/ 取消 / 达限；
     *       调用方按常规终态判定。</li>
     *   <li>{@code PAGINATION_STOP}：翻页主动停止，{@code reason} 为
     *       DUPLICATE_PAGE / PAGINATION_* 之一，作为终态 stop_reason。</li>
     *   <li>{@code ABORTED}：页内已写终态（{@code reason == null}）或需要调用方
     *       写 FAILED 终态（{@code reason != null}，如翻页后 list-item 消失）。</li>
     * </ul>
     */
    record LoopStop(Kind kind, StopReason reason, String message) {
        enum Kind { NATURAL, PAGINATION_STOP, ABORTED }

        static LoopStop natural() {
            return new LoopStop(Kind.NATURAL, null, null);
        }

        static LoopStop pagination(StopReason reason, String message) {
            return new LoopStop(Kind.PAGINATION_STOP, reason, message);
        }

        static LoopStop aborted() {
            return new LoopStop(Kind.ABORTED, null, null);
        }

        static LoopStop abortedWithTerminal(StopReason reason, String message) {
            return new LoopStop(Kind.ABORTED, reason, message);
        }
    }

    /**
     * 翻页主循环：初始页 -> （翻页判定 -> 点击 -> 校验 -> 处理新页）*，直至终止。
     *
     * @param def       任务定义（paginationRule 为 null 时只处理初始页即返回 NATURAL）
     * @param page      当前 run 的页面句柄
     * @param ctx       协作式取消 / 达限上下文
     * @param runId     运行 id（事件写入）
     * @param processor 每页处理回调（由 MultiPageRunExecutor 提供）
     */
    LoopStop runPages(TaskDefinition def, RunPageHandle page, RunExecutionContext ctx,
                      long runId, PageProcessor processor) {
        PaginationRule pagination = def.paginationRule();
        Set<String> visitedUrls = new HashSet<>();
        Set<String> visitedHashes = new HashSet<>();
        int pageNo = 1;
        int processedCount;  // LOAD_MORE：已交给 processor 的 item 数（追加式前缀）
        int consecutiveNoNew = 0;

        // 初始页：query 一次拿 items（hash / 计数 / 处理共用，processor 用同一 DomState scopeToNode）
        DomState dom = page.acquireDomState();
        List<Node> items = queryItems(dom, def);
        if (items == null) {
            return LoopStop.abortedWithTerminal(StopReason.ENTRY_FAILED,
                    "listItemRule query failed");
        }
        visitedUrls.add(page.currentUrl());
        visitedHashes.add(contentHasher.hash(items));
        processedCount = items.size();
        if (!processor.processPage(pageNo, 0, dom, items)) {
            return LoopStop.aborted();
        }

        while (pagination != null) {
            if (ctx.isCancelRequested() || ctx.recordLimitExceeded()
                    || ctx.pageLimitExceeded() || ctx.timeLimitExceeded(System.currentTimeMillis())) {
                return LoopStop.natural();
            }
            // M5-5 / spec §D9：每次翻页 click 前调用 PacingPolicy（NEXT_PAGE 触发 navigation
            // 时由 click 等 DOMContentLoaded；LOAD_MORE 不触发 navigation 但仍按节奏登记）。
            if (pacingPolicy != null) {
                pacingPolicy.beforeNavigate(page.currentUrl());
            }
            ClickResult click = page.click(pagination.selector(), PAGINATION_WAIT_MS);
            if (click == ClickResult.NOT_FOUND) {
                if (pagination.mode() == NavigationMode.LOAD_MORE) {
                    // LOAD_MORE：更多按钮消失是"异常终止"信号（常规结束走无新增判定）
                    emitEvent(runId, RunEventLevel.INFO, "PAGINATION_STOPPED", page.currentUrl(),
                            "reason=DISAPPEARED mode=LOAD_MORE");
                    return LoopStop.pagination(StopReason.PAGINATION_DISAPPEARED,
                            "pagination element disappeared");
                }
                return LoopStop.natural();  // NEXT_PAGE 末页：自然翻完
            }
            if (click == ClickResult.DISABLED) {
                emitEvent(runId, RunEventLevel.INFO, "PAGINATION_STOPPED", page.currentUrl(),
                        "reason=DISABLED mode=" + pagination.mode().name());
                return LoopStop.pagination(StopReason.PAGINATION_DISABLED,
                        "pagination element disabled");
            }
            if (click == ClickResult.FAILED) {
                // 点击失败（遮挡 / lane 异常）视为终止，不细分（与 M5-2 一致）
                LOG.warn("PagingExecutor: click failed runId={} sel={}", runId, pagination.selector());
                return LoopStop.natural();
            }
            emitEvent(runId, RunEventLevel.INFO, "PAGINATION_CLICKED", page.currentUrl(),
                    "mode=" + pagination.mode().name() + " page=" + pageNo);
            if (!page.waitForSelector(def.listItemRule().selector(), LIST_ITEM_WAIT_MS)) {
                return LoopStop.abortedWithTerminal(StopReason.ENTRY_FAILED,
                        "list-item selector not found after pagination");
            }

            dom = page.acquireDomState();
            items = queryItems(dom, def);
            if (items == null) {
                return LoopStop.abortedWithTerminal(StopReason.ENTRY_FAILED,
                        "listItemRule query failed after pagination");
            }

            if (pagination.mode() == NavigationMode.NEXT_PAGE) {
                // 重复页保护：URL 与 contentHash 任一命中已访问集合即停（spec §D5 双重比对）
                String newUrl = page.currentUrl();
                if (visitedUrls.contains(newUrl)) {
                    emitEvent(runId, RunEventLevel.WARN, "DUPLICATE_PAGE", newUrl, "reason=URL");
                    return LoopStop.pagination(StopReason.DUPLICATE_PAGE, "reason=URL url=" + newUrl);
                }
                String newHash = contentHasher.hash(items);
                if (visitedHashes.contains(newHash)) {
                    emitEvent(runId, RunEventLevel.WARN, "DUPLICATE_PAGE", newUrl,
                            "reason=contentHash");
                    return LoopStop.pagination(StopReason.DUPLICATE_PAGE,
                            "reason=contentHash url=" + newUrl);
                }
                visitedUrls.add(newUrl);
                visitedHashes.add(newHash);
                pageNo++;
                if (!processor.processPage(pageNo, 0, dom, items)) {
                    return LoopStop.aborted();
                }
            } else {  // LOAD_MORE
                settleForDomStabilization();
                if (items.size() <= processedCount) {
                    consecutiveNoNew++;
                    if (consecutiveNoNew >= NO_NEW_ITEMS_THRESHOLD) {
                        emitEvent(runId, RunEventLevel.INFO, "PAGINATION_STOPPED",
                                page.currentUrl(), "reason=NO_NEW_ITEMS mode=LOAD_MORE");
                        return LoopStop.pagination(StopReason.PAGINATION_NO_NEW_ITEMS,
                                "no new items after " + NO_NEW_ITEMS_THRESHOLD + " clicks");
                    }
                    continue;  // 本轮无新增：不算新页，不回调 processor
                }
                consecutiveNoNew = 0;
                pageNo++;
                int skipFirstN = processedCount;
                processedCount = items.size();
                if (!processor.processPage(pageNo, skipFirstN, dom, items)) {
                    return LoopStop.aborted();
                }
            }
        }
        return LoopStop.natural();
    }

    private List<Node> queryItems(DomState dom, TaskDefinition def) {
        SelectorType itemType = def.listItemRule().selectorType() == null
                ? SelectorType.CSS : def.listItemRule().selectorType();
        try {
            return dom.query(def.listItemRule().selector(), itemType);
        } catch (RuntimeException ex) {
            LOG.warn("PagingExecutor: listItemRule query failed: {}", ex.getMessage());
            return null;
        }
    }

    /** LOAD_MORE 点击后等 DOM 稳定（spec §D5：1s 固定稳定期，异步 append 有窗口完成）。 */
    private void settleForDomStabilization() {
        try {
            Thread.sleep(LOAD_MORE_SETTLE_MS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private void emitEvent(long runId, RunEventLevel level, String stage, String url, String msg) {
        try {
            resultSink.appendBatch(runId, List.of(), List.of(
                    new RunEventInput(level, stage, url, null, msg)));
        } catch (RuntimeException ignored) {
            // 事件写失败不阻断翻页主流程
        }
    }
}