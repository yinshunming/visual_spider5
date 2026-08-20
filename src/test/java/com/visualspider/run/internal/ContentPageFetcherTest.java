package com.visualspider.run.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.visualspider.extraction.spi.ExtractionPreview;
import com.visualspider.extraction.spi.PreviewResult;
import com.visualspider.extraction.spi.PreviewResult.FieldOutcome;
import com.visualspider.result.internal.UniqueKeyHasher;
import com.visualspider.result.spi.RunResultSink;
import com.visualspider.run.internal.ContentPageFetcher.ContentFetchResult;
import com.visualspider.run.internal.testutil.FakeContentPageHandle;
import com.visualspider.run.spi.RunPageHandle;
import com.visualspider.task.domain.FieldDefinition;
import com.visualspider.task.domain.FieldKind;
import com.visualspider.task.domain.FieldScope;
import com.visualspider.task.domain.FieldSource;
import com.visualspider.task.domain.Limits;
import com.visualspider.task.domain.ListItemRule;
import com.visualspider.task.domain.ResultType;
import com.visualspider.task.domain.SelectorType;
import com.visualspider.task.domain.TaskDefinition;
import com.visualspider.task.domain.TaskMode;
import com.visualspider.task.domain.TrimPolicy;
import com.visualspider.task.domain.Viewport;
import com.visualspider.task.domain.WaitPolicy;
import com.visualspider.visualbrowser.spi.TargetUrlPolicy;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link ContentPageFetcher} 单元测试（M5-4 / issue #42 / spec §D6 / T1）。
 *
 * <p>覆盖：
 * <ul>
 *   <li>首次成功 -> ok=true + fields 非空</li>
 *   <li>失败 1 / 2 次后第 3 次成功 -> ok=true（retry 生效）</li>
 *   <li>3 次全败 -> ok=false, errorCode=RETRY_EXHAUSTED</li>
 *   <li>URL 不被 TargetUrlPolicy 接受 -> ok=false, errorCode=URL_NOT_ALLOWED（无 retry）</li>
 *   <li>scope=CONTENT 字段正确抽取；scope=LIST 字段被过滤</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ContentPageFetcherTest {

    @Mock private RunPageHandle pageHandle;
    @Mock private TargetUrlPolicy urlPolicy;
    @Mock private ExtractionPreview preview;

    private ContentPageFetcher fetcher;

    /** openContentPage 调用计数器（每次 retry 重置为 0；由测试 setUp 重置）。 */
    private int openCount;

    @BeforeEach
    void setUp() {
        fetcher = new ContentPageFetcher(urlPolicy, preview);
        openCount = 0;
        // TargetUrlPolicy.validate 默认可调用；具体用例按需抛异常。
        lenient().doNothing().when(urlPolicy).validate(anyString());
        // preview.preview 返回 scope=CONTENT 的 price/title 字段。
        lenient().when(preview.preview(any(), any())).thenReturn(contentFieldsResult());
    }

    @Test
    @DisplayName("首次成功 -> ok=true + fields 非空")
    void successOnFirstAttempt() {
        stubOpenOk("https://example.com/c/1");
        ContentFetchResult res = fetcher.fetchWithRetry(pageHandle, def(),
                "https://example.com/c/1");
        assertThat(res.ok()).isTrue();
        assertThat(res.fields()).containsEntry("price", "$10.00").containsEntry("body", "...");
        assertThat(openCount).isEqualTo(1);
    }

    @Test
    @DisplayName("前 2 次失败第 3 次成功 -> ok=true（retry 生效）")
    void retrySucceedsOnThirdAttempt() {
        when(pageHandle.openContentPageAndAwaitDomContentLoaded(anyString()))
                .thenAnswer(inv -> {
                    openCount++;
                    if (openCount < 3) {
                        throw new RuntimeException("transient " + openCount);
                    }
                    return new FakeContentPageHandle((String) inv.getArgument(0));
                });
        ContentFetchResult res = fetcher.fetchWithRetry(pageHandle, def(),
                "https://example.com/c/1");
        assertThat(res.ok()).isTrue();
        assertThat(openCount).isEqualTo(3);
    }

    @Test
    @DisplayName("3 次全败 -> ok=false, errorCode=RETRY_EXHAUSTED")
    void retryExhausted() {
        when(pageHandle.openContentPageAndAwaitDomContentLoaded(anyString()))
                .thenAnswer(inv -> {
                    openCount++;
                    throw new RuntimeException("always fail");
                });
        ContentFetchResult res = fetcher.fetchWithRetry(pageHandle, def(),
                "https://example.com/c/1");
        assertThat(res.ok()).isFalse();
        assertThat(res.errorCode()).isEqualTo("RETRY_EXHAUSTED");
        assertThat(openCount).isEqualTo(3);
    }

    @Test
    @DisplayName("URL 不被 TargetUrlPolicy 接受 -> ok=false, errorCode=URL_NOT_ALLOWED（无 retry）")
    void urlNotAllowedSkipsRetry() {
        doThrow(new com.visualspider.visualbrowser.internal.InvalidTargetUrlException())
                .when(urlPolicy).validate(anyString());
        ContentFetchResult res = fetcher.fetchWithRetry(pageHandle, def(), "data:text/html,x");
        assertThat(res.ok()).isFalse();
        assertThat(res.errorCode()).isEqualTo("URL_NOT_ALLOWED");
        assertThat(openCount).isEqualTo(0);  // 不进入 retry
    }

    @Test
    @DisplayName("scope=CONTENT 字段被抽取；scope=LIST 字段被过滤")
    void scopesAreFiltered() {
        // preview.preview 同时返回 CONTENT + LIST 字段；fetcher 只保留 CONTENT
        lenient().when(preview.preview(any(), any())).thenReturn(
                new PreviewResult(
                        List.of(new FieldOutcome("title", "T1", "T1", false),       // LIST scope -> filter
                                new FieldOutcome("date", "2024-01-01", "2024-01-01", false),  // LIST -> filter
                                new FieldOutcome("price", "$10.00", "$10.00", false),  // CONTENT -> keep
                                new FieldOutcome("body", "...", "...", false)),       // CONTENT -> keep
                        List.of()));
        stubOpenOk("https://example.com/c/1");
        ContentFetchResult res = fetcher.fetchWithRetry(pageHandle, def(),
                "https://example.com/c/1");
        assertThat(res.fields()).containsOnlyKeys("price", "body");
    }

    private void stubOpenOk(String url) {
        when(pageHandle.openContentPageAndAwaitDomContentLoaded(anyString()))
                .thenAnswer(inv -> {
                    openCount++;
                    return new FakeContentPageHandle(url);
                });
    }

    private static PreviewResult contentFieldsResult() {
        // price + body 都对应 scope=CONTENT 字段（见 def()）；title 是 LIST scope，被过滤。
        return new PreviewResult(
                List.of(new FieldOutcome("price", "$10.00", "$10.00", false),
                        new FieldOutcome("body", "...", "...", false),
                        new FieldOutcome("title", "T1", "T1", false)),
                List.of());
    }

    private static TaskDefinition def() {
        return new TaskDefinition(
                3, new TaskMode.List(),
                "https://example.com", Viewport.DEFAULT, new WaitPolicy(0),
                new Limits(200, 10_000, Duration.ofMinutes(30)),
                new ListItemRule("tbody > tr", SelectorType.CSS),
                List.of(),
                null,
                List.of(
                        new FieldDefinition("title", FieldSource.VISIBLE_TEXT,
                                "h1", null, SelectorType.CSS, ResultType.TEXT,
                                TrimPolicy.TRIM, null, false,
                                FieldScope.LIST, FieldKind.LIST_VALUE),
                        new FieldDefinition("price", FieldSource.VISIBLE_TEXT,
                                ".price", null, SelectorType.CSS, ResultType.TEXT,
                                TrimPolicy.TRIM, null, false,
                                FieldScope.CONTENT, FieldKind.CONTENT_VALUE),
                        new FieldDefinition("body", FieldSource.VISIBLE_TEXT,
                                ".body", null, SelectorType.CSS, ResultType.TEXT,
                                TrimPolicy.TRIM, null, false,
                                FieldScope.CONTENT, FieldKind.CONTENT_VALUE)));
    }
}