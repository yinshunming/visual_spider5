package com.visualspider.visualbrowser.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.visualspider.visualbrowser.SsrfRouteGuard;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * SSRF 请求拦截集成测试（M6-1 / spec §D5 / T2）。
 *
 * <p>真实 Chromium + 本地 HttpServer probe 记账。两种策略配置：
 * <ul>
 *   <li>{@code STRICT}：allow-loopback=false，模拟生产默认；</li>
 *   <li>{@code LOOSE}：allow-loopback=true，模拟 it/dev profile。</li>
 * </ul>
 *
 * <p>断言对象是"探测端点从未收到请求"（probe 计数器 == 0），而非页面渲染错误。
 * 由于生产风格（严格）下 fixture 自身就位于 loopback，访问任何 fixture URL 都会被
 * 在顶层拦截，因此 redirect/subresource fixture 在严格模式下只能断言"probe 计数 == 0"
 * （事实上的顶层拦截保证）。为了对"重定向"和"子资源"做更强的端点级断言（top 页面
 * 加载，目标被拦截），测试用 about:blank 起步 + page.evaluate 的 JS 重定向 / fetch
 * 触发：about:blank 不经路由，触发到的目标请求走 route，从而精确锁定拦截语义。
 */
class SsrfInterceptionIT {

    private static final long TIMEOUT_MS = 10_000L;

    private static com.sun.net.httpserver.HttpServer server;
    private static int serverPort;
    private static AtomicLong probeHits = new AtomicLong();
    private static Path ssrfDir;

    private Playwright playwright;
    private Browser browser;
    private SimpleMeterRegistry registry;
    private Set<String> blockedHosts;

    @BeforeAll
    static void startServer() throws Exception {
        ssrfDir = Paths.get(SsrfInterceptionIT.class
                .getResource("/ssrf/direct-loopback.html").toURI()).getParent();
        server = com.sun.net.httpserver.HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.startsWith("/")) {
                path = path.substring(1);
            }
            byte[] body;
            if (path.equals("probe")) {
                probeHits.incrementAndGet();
                body = "ok".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
                exchange.sendResponseHeaders(200, body.length);
                try (var os = exchange.getResponseBody()) {
                    os.write(body);
                }
                exchange.close();
                return;
            }
            Path file = ssrfDir.resolve(path).normalize();
            if (!file.startsWith(ssrfDir) || !Files.isReadable(file)) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            String rendered = new String(Files.readAllBytes(file), StandardCharsets.UTF_8)
                    .replace("__PROBE_PORT__", String.valueOf(serverPort));
            body = rendered.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (var os = exchange.getResponseBody()) {
                os.write(body);
            }
            exchange.close();
        });
        server.start();
        serverPort = server.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        probeHits.set(0L);
        // 强制 ms 级独立：每次测试前重置端口级基准不影响，但确保计数器从 0 开始
        Files.createDirectories(Paths.get(System.getProperty("java.io.tmpdir")));
        registry = new SimpleMeterRegistry();
        blockedHosts = ConcurrentHashMap.newKeySet();
        playwright = Playwright.create();
        browser = playwright.chromium().launch(
                new BrowserType.LaunchOptions().setHeadless(true));
    }

    @AfterEach
    void tearDown() {
        if (browser != null) {
            browser.close();
        }
        if (playwright != null) {
            playwright.close();
        }
    }

    private Page newPageWithGuard(boolean allowLoopback) {
        PublicTargetUrlPolicy policy = new PublicTargetUrlPolicy(
                new InetDnsResolver(), allowLoopback);
        SsrfRouteGuard guard = new SsrfRouteGuard(policy, registry);
        BrowserContext ctx = browser.newContext();
        guard.install(ctx, blockedHosts::add);
        return ctx.newPage();
    }

    private void awaitBlocked(String expected) {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (blockedHosts.contains(expected)) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private long awaitCounter(String name, long atLeast) {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            long v = readCounter(name);
            if (v >= atLeast) {
                return v;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return -1;
            }
        }
        return readCounter(name);
    }

    private long readCounter(String name) {
        var counter = registry.find(name).counter();
        return counter == null ? 0L : (long) counter.count();
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    @DisplayName("STRICT：直接访问 loopback fixture -> 顶层拦截，probe 0，host 入拦截集，计数 +1")
    void strictDirectLoopbackBlocked() {
        Page page = newPageWithGuard(false);
        String url = "http://127.0.0.1:" + serverPort + "/ssrf/direct-loopback.html";
        assertThatThrownBy(() -> page.navigate(url, new Page.NavigateOptions().setTimeout(5_000)))
                .isInstanceOf(RuntimeException.class);
        awaitBlocked("127.0.0.1");
        assertThat(probeHits.get()).isZero();
        assertThat(blockedHosts).contains("127.0.0.1");
        assertThat(awaitCounter("ssrf.blocked", 1L)).isGreaterThanOrEqualTo(1L);
    }

    @Test
    @DisplayName("STRICT：四类恶意 fixture 顶层访问全部被拦截（probe 计数 == 0）")
    void strictBlocksAllFixturePages() {
        String[] urls = {
                "/ssrf/direct-loopback.html",
                "/ssrf/redirect-to-loopback.html",
                "/ssrf/subresource-loopback.html",
                "/ssrf/mixed-notation.html",
        };
        for (String path : urls) {
            probeHits.set(0L);
            blockedHosts.clear();
            Page page = newPageWithGuard(false);
            String url = "http://127.0.0.1:" + serverPort + path;
            assertThatThrownBy(() -> page.navigate(url, new Page.NavigateOptions().setTimeout(5_000)))
                    .as("navigate " + url + " 应被拦截")
                    .isInstanceOf(RuntimeException.class);
            assertThat(probeHits.get()).as("probe 计数（路径 %s）", path).isZero();
            page.context().close();
        }
    }

    @Test
    @DisplayName("STRICT：about:blank + JS location.href -> 重定向到 loopback 被拦截（probe 0）")
    void strictBlocksRedirectFromBlank() {
        Page page = newPageWithGuard(false);
        String target = "http://127.0.0.1:" + serverPort + "/probe";
        page.evaluate("(u) => { location.href = u; }", target);
        awaitBlocked("127.0.0.1");
        sleep(500);  // 给拦截后的请求一点时间走到网络层（应不会到达）
        assertThat(probeHits.get()).isZero();
    }

    @Test
    @DisplayName("STRICT：about:blank + fetch(loopback probe) -> 子资源被拦截，fetch 失败，probe 0")
    void strictBlocksFetchFromBlank() {
        Page page = newPageWithGuard(false);
        String target = "http://127.0.0.1:" + serverPort + "/probe";
        Object outcome = page.evaluate(
                "(u) => fetch(u, {mode:'no-cors'}).then(()=> 'ok').catch(e => 'err')",
                target);
        assertThat(outcome).isEqualTo("err");
        awaitBlocked("127.0.0.1");
        sleep(500);
        assertThat(probeHits.get()).isZero();
    }

    @Test
    @DisplayName("STRICT：混合表示 127.1 / 2130706433 / 0x7f.0.0.1 -> fetch 被拦截")
    void strictBlocksMixedNotationFetches() {
        Page page = newPageWithGuard(false);
        String[] hosts = {
                "http://127.1:" + serverPort + "/probe",
                "http://2130706433:" + serverPort + "/probe",
                "http://0x7f.0.0.1:" + serverPort + "/probe",
        };
        for (String h : hosts) {
            Object outcome = page.evaluate(
                    "(u) => fetch(u, {mode:'no-cors'}).then(()=> 'ok').catch(e => 'err')",
                    h);
            assertThat(outcome).as("mixed-notation fetch " + h).isEqualTo("err");
        }
        // 至少有一次拦截记录；具体 host 列表受 Chromium 归一化影响不强行断言
        long cnt = awaitCounter("ssrf.blocked", 1L);
        assertThat(cnt).isGreaterThanOrEqualTo(1L);
        // probe 永远不应该被到达（这些 host 都解析为 127.0.0.1）
        sleep(500);
        assertThat(probeHits.get()).isZero();
    }

    @Test
    @DisplayName("LOOSE：about:blank + fetch(loopback probe) -> loopback 放行，probe 命中")
    void looseAllowsLoopbackSubresources() {
        Page page = newPageWithGuard(true);
        String target = "http://127.0.0.1:" + serverPort + "/probe";
        Object outcome = page.evaluate(
                "(u) => fetch(u, {mode:'no-cors'}).then(()=> 'ok').catch(e => 'err')",
                target);
        assertThat(outcome).as("loopback fetch should succeed under LOOSE").isEqualTo("ok");
        assertThat(probeHits.get()).isGreaterThanOrEqualTo(1L);
        // 127.0.0.1 是 loopback，不应进拦截集
        assertThat(blockedHosts).doesNotContain("127.0.0.1");
    }

    @Test
    @DisplayName("LOOSE：about:blank + fetch(metadata 169.254.169.254) -> 顶层放行，子资源被拦截")
    void looseBlocksMetadataSubresources() {
        Page page = newPageWithGuard(true);
        String target = "http://169.254.169.254/latest/meta-data/";
        Object outcome = page.evaluate(
                "(u) => fetch(u, {mode:'no-cors'}).then(()=> 'ok').catch(e => 'err')",
                target);
        assertThat(outcome).as("metadata fetch should be aborted by route").isEqualTo("err");
        awaitBlocked("169.254.169.254");
        assertThat(blockedHosts).contains("169.254.169.254");
    }

    @Test
    @DisplayName("LOOSE：直接访问 TEST-NET-1 (192.0.2.1) -> 顶层拦截（即使 LOOSE 也不豁免非回环）")
    void looseBlocksNonLoopbackDirect() {
        Page page = newPageWithGuard(true);
        String url = "http://192.0.2.1/probe";
        assertThatThrownBy(() -> page.navigate(url, new Page.NavigateOptions().setTimeout(5_000)))
                .isInstanceOf(RuntimeException.class);
        awaitBlocked("192.0.2.1");
        assertThat(blockedHosts).contains("192.0.2.1");
    }
}