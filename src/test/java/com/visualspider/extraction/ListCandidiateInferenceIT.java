package com.visualspider.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import com.visualspider.extraction.internal.DomLevelCandidateListItemInferrer;
import com.visualspider.extraction.spi.CandidateListItemInferrer;
import com.visualspider.extraction.spi.DomSnapshot;
import com.visualspider.extraction.spi.InferredCandidateListItem;
import com.visualspider.visualbrowser.BrowserLane;
import com.visualspider.visualbrowser.PlaywrightControl;
import com.visualspider.visualbrowser.ValidationResult;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 候选列表项推断集成测试（spec §T3 / M4-2 #32）：真实 Chromium + 3 个 list fixture。
 *
 * <p>继承 {@code PlaywrightControlIT} 的"非 Spring + 直接 new BrowserLane"模式，避免拉起整个
 * Spring context（infer 只需 Playwright + 纯函数 inferrer，不需 DB）。fixture 通过 JDK 内建
 * {@code HttpServer} 以 {@code http://localhost} 提供（避开 {@code file:} 路径在不同平台差异）。
 *
 * <p>每个测试独立 {@link BrowserLane} + 独立非持久化 BrowserContext（spec §D9）。断言两层：
 * (1) inferrer 输出的 selector + matchCount + score；(2) selector 在 fixture 上真实匹配数（用
 * {@code PlaywrightControl.validateSelector} 二次验证，确保 selector 真正可选到 N 个 item）。
 */
class ListCandidiateInferenceIT {

    private static final long TIMEOUT_SECONDS = 20;

    private static com.sun.net.httpserver.HttpServer fixtureServer;
    private static int fixturePort;
    private static final CandidateListItemInferrer inferrer = new DomLevelCandidateListItemInferrer();

    private BrowserLane lane;
    private PlaywrightControl control;
    private String baseUrl;

    @BeforeAll
    static void startFixtureServer() throws Exception {
        Path listDir = Paths.get(ListCandidiateInferenceIT.class.getResource("/list/standard-list.html").toURI())
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
        lane = new BrowserLane();
        control = new PlaywrightControl(lane);
        baseUrl = "http://127.0.0.1:" + fixturePort;
    }

    @AfterEach
    void tearDown() {
        if (lane != null) {
            lane.close();
        }
    }

    @Test
    @DisplayName("standard-list：clicked=a.title -> rule=tr, matchCount=5, querySelectorAll('tr')=5")
    void standardListInferredAsTrItems() throws Exception {
        navigateAndWait("/standard-list.html");
        double[] center = control.elementCenter("tbody tr:first-child a.title")
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        DomSnapshot snap = control.captureDomSnapshot((int) center[0], (int) center[1])
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        InferredCandidateListItem result = inferrer.infer(snap);
        assertThat(result.matchCount()).isEqualTo(5);
        assertThat(result.score()).isGreaterThanOrEqualTo(0.6);
        assertThat(result.rule().selector()).isEqualToIgnoringCase("tr");

        // 二次验证：selector 在真实 DOM 上确实匹配 5 个 tr
        ValidationResult vr = control.validateSelector(result.rule().selector(),
                result.rule().selectorType().name().toLowerCase()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(vr.valid()).isTrue();
        assertThat(vr.count()).isEqualTo(5);
    }

    @Test
    @DisplayName("card-grid：clicked=span.text -> rule=div.card, matchCount=6, querySelectorAll=6")
    void cardGridInferredAsCardItems() throws Exception {
        navigateAndWait("/card-grid.html");
        double[] center = control.elementCenter(".cards .card .text")
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        DomSnapshot snap = control.captureDomSnapshot((int) center[0], (int) center[1])
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        InferredCandidateListItem result = inferrer.infer(snap);
        assertThat(result.matchCount()).isEqualTo(6);
        assertThat(result.score()).isGreaterThanOrEqualTo(0.6);
        assertThat(result.rule().selector()).isEqualToIgnoringCase("div.card");

        ValidationResult vr = control.validateSelector(result.rule().selector(),
                result.rule().selectorType().name().toLowerCase()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(vr.valid()).isTrue();
        assertThat(vr.count()).isEqualTo(6);
    }

    @Test
    @DisplayName("nested-list：clicked=a -> rule=li.item, matchCount=4, querySelectorAll=6 (含另一 category)")
    void nestedListInferredAsInnerItems() throws Exception {
        navigateAndWait("/nested-list.html");
        double[] center = control.elementCenter(".lead-items .item a")
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        DomSnapshot snap = control.captureDomSnapshot((int) center[0], (int) center[1])
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        InferredCandidateListItem result = inferrer.infer(snap);
        assertThat(result.score()).isGreaterThanOrEqualTo(0.6);
        assertThat(result.rule().selector()).isEqualToIgnoringCase("li.item");
        // matchCount = clicked 所在 ul.lead-items 的 li.item 数 = 4
        assertThat(result.matchCount()).isEqualTo(4);
        // selector 全局匹配 = 2 个 ul.lead-items 各 4/2 = 6
        ValidationResult vr = control.validateSelector(result.rule().selector(),
                result.rule().selectorType().name().toLowerCase()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(vr.valid()).isTrue();
        assertThat(vr.count()).isEqualTo(6);
    }

    @Test
    @DisplayName("adjustAncestor UP：standard-list tbody 是唯一 ≥0.6 候选 -> UP 方向无候选 -> lowConfidence")
    void adjustAncestorUpOnStandardList() throws Exception {
        navigateAndWait("/standard-list.html");
        double[] center = control.elementCenter("tbody tr:first-child a.title")
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        DomSnapshot snap = control.captureDomSnapshot((int) center[0], (int) center[1])
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        InferredCandidateListItem initial = inferrer.infer(snap);
        assertThat(initial.rule().selector()).isEqualToIgnoringCase("tr");

        InferredCandidateListItem up = inferrer.adjustAncestor(snap,
                CandidateListItemInferrer.Direction.UP);
        // tbody 是唯一 ≥ 0.6 候选；UP 方向（depth > tbody=3）无其他候选 -> lowConfidence
        assertThat(up.lowConfidence()).isTrue();
    }

    private void navigateAndWait(String fixturePath) throws Exception {
        control.navigate(baseUrl + fixturePath).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        // waitForSelector 防止 DOMContentLoaded 之后页面还没渲染完
        control.waitForSelector("body").get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }
}