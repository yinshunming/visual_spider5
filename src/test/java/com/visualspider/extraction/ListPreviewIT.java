package com.visualspider.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import com.visualspider.extraction.internal.CleaningPipeline;
import com.visualspider.extraction.internal.ExtractionPreviewImpl;
import com.visualspider.extraction.spi.ExtractionPreview;
import com.visualspider.extraction.spi.ExtractionPreview.ListPreviewResult;
import com.visualspider.extraction.spi.PreviewResult;
import com.visualspider.task.domain.FieldDefinition;
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
import com.visualspider.visualbrowser.VisualSession;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * List 模式受限预览集成测试（M4-3 #33 / spec §D9 / §T3）：
 * 真 Chromium + 4 个 list fixture。复用 {@code ListCandidiateInferenceIT} 的
 * {@code HttpServer + 127.0.0.1 fixture} 模式（避开 {@code file:} 路径平台差异）。
 *
 * <p>每测试独立 {@link VisualSession}（独立非持久化 BrowserContext）。
 * 通过 session.previewList 在 lane 线程跑 ExtractionPreview.previewList，
 * 验证 VisualSession.buildDomState 的 scopeToNode 接线（M4-3 修复 preview 路径
 * scopeToNode fallback 到父 state 的缺口），每 item 拿到本 item 子树内的字段值。
 */
class ListPreviewIT {

    private static final long TIMEOUT_SECONDS = 20;

    private static com.sun.net.httpserver.HttpServer fixtureServer;
    private static int fixturePort;
    private static final ExtractionPreview extraction = new ExtractionPreviewImpl(new CleaningPipeline());

    private VisualSession session;
    private String baseUrl;

    @BeforeAll
    static void startFixtureServer() throws Exception {
        Path listDir = Paths.get(ListPreviewIT.class.getResource("/list/standard-list.html").toURI())
                .getParent();
        fixtureServer = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fixtureServer.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.startsWith("/")) {
                path = path.substring(1);
            }
            Path file = listDir.resolve(path).normalize();
            if (!file.startsWith(listDir) || !Files.isReadable(file)) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            byte[] body = Files.readAllBytes(file);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (var os = exchange.getResponseBody()) {
                os.write(body);
            }
            exchange.close();
        });
        fixtureServer.start();
        fixturePort = fixtureServer.getAddress().getPort();
    }

    @AfterAll
    static void stopFixtureServer() {
        if (fixtureServer != null) {
            fixtureServer.stop(0);
        }
    }

    @BeforeEach
    void setUp() {
        session = null;
        baseUrl = "http://127.0.0.1:" + fixturePort;
    }

    @AfterEach
    void tearDown() {
        if (session != null) {
            session.close();
        }
    }

    @Test
    @DisplayName("sanity：VisualSession 构造后 currentUrl 指向 fixture（preview 路径前提）")
    void sanityCurrentUrl() throws Exception {
        open("/standard-list.html");
        String url = session.control().currentUrl().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(url).contains("standard-list.html");
    }

    @Test
    @DisplayName("sanity2：PlaywrightControl.validateSelector('tbody > tr') 在 fixture 上 = 5（验证 page 有内容）")
    void sanityValidateSelector() throws Exception {
        open("/standard-list.html");
        com.visualspider.visualbrowser.ValidationResult vr = session.control()
                .validateSelector("tbody > tr", "css").get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(vr.valid()).isTrue();
        assertThat(vr.count()).isEqualTo(5);
    }

    @Test
    @DisplayName("standard-list：5 行 / 每行 3 字段（title/date/count）/ totalMatchCount=5")
    void standardListPreview() throws Exception {
        open("/standard-list.html");
        TaskDefinition def = taskWithFields("tbody > tr",
                List.of(field("title", "a.title"), field("date", ".date"), field("count", ".count")));
        ListPreviewResult result = session.previewList(def, extraction, 20);

        assertThat(result.totalMatchCount()).isEqualTo(5);
        assertThat(result.previews()).hasSize(5);
        assertThat(fieldValues(result.previews(), "title"))
                .containsExactly("Alpha", "Beta", "Gamma", "Delta", "Epsilon");
        assertThat(fieldValues(result.previews(), "count"))
                .containsExactly("10", "20", "30", "40", "50");
    }

    @Test
    @DisplayName("card-grid：6 卡 / totalMatchCount=6 / scopeToNode 字段正确 per-card")
    void cardGridPreview() throws Exception {
        open("/card-grid.html");
        TaskDefinition def = taskWithFields("div.cards > div.card",
                List.of(field("text", "span.text"), field("title", "a.title")));
        ListPreviewResult result = session.previewList(def, extraction, 20);

        assertThat(result.totalMatchCount()).isEqualTo(6);
        assertThat(result.previews()).hasSize(6);
        assertThat(fieldValues(result.previews(), "title"))
                .containsExactly("Card 1 title", "Card 2 title", "Card 3 title",
                        "Card 4 title", "Card 5 title", "Card 6 title");
        assertThat(fieldValues(result.previews(), "text"))
                .containsExactly("Card 1 body", "Card 2 body", "Card 3 body",
                        "Card 4 body", "Card 5 body", "Card 6 body");
    }

    @Test
    @DisplayName("nested-list：6 项（4+2）/ totalMatchCount=6 / 字段值随 item 正确变化")
    void nestedListPreview() throws Exception {
        open("/nested-list.html");
        TaskDefinition def = taskWithFields("li.item",
                List.of(field("title", "a")));
        ListPreviewResult result = session.previewList(def, extraction, 20);

        assertThat(result.totalMatchCount()).isEqualTo(6);
        assertThat(result.previews()).hasSize(6);
        assertThat(fieldValues(result.previews(), "title"))
                .containsExactly("Alpha", "Beta", "Gamma", "Delta", "Epsilon", "Zeta");
    }

    @Test
    @DisplayName("with-duplicates：5 行 / preview 不过滤重复（去重是 run 路径 sink 的事）")
    void withDuplicatesPreview() throws Exception {
        open("/with-duplicates.html");
        TaskDefinition def = taskWithFields("tbody > tr",
                List.of(field("title", "a.title")));
        ListPreviewResult result = session.previewList(def, extraction, 20);

        assertThat(result.totalMatchCount()).isEqualTo(5);
        assertThat(result.previews()).hasSize(5);
        assertThat(fieldValues(result.previews(), "title"))
                .containsExactly("Alpha", "Beta", "Alpha", "Beta", "Gamma");
    }

    @Test
    @DisplayName("maxItems cap：limit=3 → 仅预览前 3 条；totalMatchCount 不变")
    void maxItemsCapEnforced() throws Exception {
        open("/with-duplicates.html");
        TaskDefinition def = taskWithFields("tbody > tr",
                List.of(field("title", "a.title")));
        ListPreviewResult result = session.previewList(def, extraction, 3);

        assertThat(result.totalMatchCount()).isEqualTo(5);
        assertThat(result.previews()).hasSize(3);
        assertThat(fieldValues(result.previews(), "title"))
                .containsExactly("Alpha", "Beta", "Alpha");
    }

    // ---------- helpers ----------

    private void open(String fixturePath) {
        session = new VisualSession("preview-it", baseUrl + fixturePath);
    }

    private static FieldDefinition field(String name, String selector) {
        return new FieldDefinition(name, FieldSource.VISIBLE_TEXT,
                selector, null, SelectorType.CSS, ResultType.TEXT,
                TrimPolicy.TRIM, null, true);
    }

    private static TaskDefinition taskWithFields(String listSelector, List<FieldDefinition> fields) {
        return new TaskDefinition(
                2, new TaskMode.List(),
                "http://127.0.0.1:" + fixturePort + "/list.html",
                Viewport.DEFAULT, new WaitPolicy(0),
                new Limits(200, 10_000, Duration.ofMinutes(30)),
                new ListItemRule(listSelector, SelectorType.CSS),
                List.of(),
                fields);
    }

    private static List<String> fieldValues(List<PreviewResult> previews, String fieldName) {
        return previews.stream()
                .map(p -> p.fieldOutcomes().stream()
                        .filter(o -> o.fieldName().equals(fieldName))
                        .findFirst()
                        .map(o -> o.cleanedValue())
                        .orElse(null))
                .map(v -> (String) v)
                .toList();
    }
}