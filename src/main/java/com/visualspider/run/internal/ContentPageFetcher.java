package com.visualspider.run.internal;

import com.visualspider.extraction.spi.ExtractionPreview;
import com.visualspider.extraction.spi.PreviewResult;
import com.visualspider.extraction.spi.PreviewResult.FieldOutcome;
import com.visualspider.run.spi.ContentPageHandle;
import com.visualspider.run.spi.RunPageHandle;
import com.visualspider.task.domain.FieldDefinition;
import com.visualspider.task.domain.FieldKind;
import com.visualspider.task.domain.FieldScope;
import com.visualspider.task.domain.TaskDefinition;
import com.visualspider.visualbrowser.spi.TargetUrlPolicy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 一层内容页 navigate + retry + 字段抽取（M5-4 / issue #42 / spec §D6）。
 *
 * <p>沿用 M3 page-level retry 2 次（共 3 次），失败返回 {@code ContentFetchResult.failed}
 * （{@code errorCode=RETRY_EXHAUSTED}）。{@code TargetUrlPolicy.validate} 在 retry
 * 之前 + 每次 attempt 后做（list 入口策略；完整 SSRF 留 M6）。
 *
 * <p>字段抽取：{@code scope=CONTENT} 且 {@code fieldKind != LIST_CONTENT_LINK} 的字段；
 * 与 list 字段同名时按字段名合并到同一 record（spec §D6：合并在 sink 之前，由
 * {@link MultiPageRunExecutor#processListPage} 完成）。
 *
 * <p>不在本类写任何 run_event（保持 narrow SPI）；{@code CONTENT_PAGE_FAILED} 事件与
 * content_fail_count 累加由 {@link MultiPageRunExecutor} 在调用 sink 时按 events 路径
 * 触发（spec §D8 / §D17）。
 */
final class ContentPageFetcher {

    private static final Logger LOG = LoggerFactory.getLogger(ContentPageFetcher.class);

    /** retry 总次数：首次 + 2 次重试 = 3（spec §D6 沿用 M3 page-level retry）。 */
    static final int MAX_ATTEMPTS = 3;
    /** attempt 间退避：1s * attempt（spec §D6）。 */
    static final long BACKOFF_MS = 1_000L;

    private final TargetUrlPolicy urlPolicy;
    private final ExtractionPreview preview;

    ContentPageFetcher(TargetUrlPolicy urlPolicy, ExtractionPreview preview) {
        this.urlPolicy = urlPolicy;
        this.preview = preview;
    }

    ContentFetchResult fetchWithRetry(RunPageHandle page, TaskDefinition def, String contentUrl) {
        if (contentUrl == null || contentUrl.isBlank()) {
            return ContentFetchResult.failed("URL_NOT_ALLOWED", "blank content url");
        }
        try {
            urlPolicy.validate(contentUrl);
        } catch (RuntimeException ex) {
            return ContentFetchResult.failed("URL_NOT_ALLOWED", safeMsg(ex));
        }
        Throwable last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try (ContentPageHandle cp = page.openContentPageAndAwaitDomContentLoaded(contentUrl)) {
                // navigate 后再次校验最终 URL，防止重定向到内网（spec §D6 沿用 list 入口策略）。
                urlPolicy.validate(cp.currentUrl());
                Map<String, String> fields = extractContentFields(def, cp);
                return ContentFetchResult.ok(fields);
            } catch (RuntimeException ex) {
                last = ex;
                LOG.warn("ContentPageFetcher: attempt {} failed url={}: {}",
                        attempt, contentUrl, safeMsg(ex));
                if (attempt < MAX_ATTEMPTS) {
                    backoff(attempt);
                }
            }
        }
        return ContentFetchResult.failed("RETRY_EXHAUSTED",
                last == null ? "unknown" : safeMsg(last));
    }

    private Map<String, String> extractContentFields(TaskDefinition def, ContentPageHandle cp) {
        PreviewResult pr;
        try {
            pr = preview.preview(def, cp.acquireDomState());
        } catch (RuntimeException ex) {
            throw new RuntimeException("content fields extraction failed: " + safeMsg(ex), ex);
        }
        // 过滤 scope=CONTENT 且 fieldKind != LIST_CONTENT_LINK 的字段。
        // LIST_CONTENT_LINK 字段在 content 页上没有意义（针对 list-item 子树定位）。
        Map<String, FieldDefinition> fieldByName = new LinkedHashMap<>();
        for (FieldDefinition f : def.fields()) {
            fieldByName.put(f.name(), f);
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (FieldOutcome o : pr.fieldOutcomes()) {
            if (o.cleanedValue() == null) {
                continue;
            }
            FieldDefinition fd = fieldByName.get(o.fieldName());
            if (fd == null) {
                continue;
            }
            if (fd.scope() != FieldScope.CONTENT) {
                continue;
            }
            if (fd.fieldKind() == FieldKind.LIST_CONTENT_LINK) {
                continue;
            }
            out.put(o.fieldName(), o.cleanedValue());
        }
        return out;
    }

    private void backoff(int attempt) {
        try {
            Thread.sleep(BACKOFF_MS * attempt);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private static String safeMsg(Throwable t) {
        if (t == null) return "";
        String m = t.getMessage();
        return m == null || m.isBlank() ? t.getClass().getSimpleName() : m;
    }

    /** 内容页 fetch 结果（spec §D6：ok=true 带 fields；ok=false 带 errorCode + cause）。 */
    record ContentFetchResult(boolean ok, Map<String, String> fields, String errorCode, String cause) {
        static ContentFetchResult ok(Map<String, String> fields) {
            return new ContentFetchResult(true, fields == null ? Map.of() : Map.copyOf(fields), null, null);
        }

        static ContentFetchResult failed(String code, String cause) {
            return new ContentFetchResult(false, Map.of(), code, cause == null ? "" : cause);
        }
    }
}