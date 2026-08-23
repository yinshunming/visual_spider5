package com.visualspider.run.internal;

import com.microsoft.playwright.BrowserContext;
import com.visualspider.result.spi.RunEventInput;
import com.visualspider.result.spi.RunEventLevel;
import com.visualspider.result.spi.RunResultSink;
import com.visualspider.run.spi.RunPageHandle;
import com.visualspider.run.spi.RunState;
import com.visualspider.run.spi.StopReason;
import com.visualspider.visualbrowser.BrowserLane;
import com.visualspider.visualbrowser.SsrfRouteGuard;
import com.visualspider.visualbrowser.spi.Lease;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link RunPageHandleProvider} 默认实现（issue #25 / spec §D9 / M5-5 / issue #43 / M6-1）。
 *
 * <p>从 {@link RunLanePool} 反查 lease 关联的 {@link BrowserLane}，在其上创建
 * {@link DefaultRunPageHandle}（独立 BrowserContext + Page + SSRF 拦截注册器 +
 * 一个 {@link PageStopDetector}（{@code 429} / 持续 {@code 403} / 验证码停止检测，
 * spec §D10）。检测触发由 {@link PageStopDetector} 调 {@code onStop} 回调：先写
 * {@code STOP_*} run_event（message 含 url + 首次/末次命中），再
 * {@code markTerminal(runId, FAILED, reason)}。
 *
 * <p>M6-1：每次 openFor 创建独立 context customizer，通过 {@link SsrfRouteGuard}
 * 注册一个拦截该 BrowserContext 全部请求的 route，把被拦截的 host 回传给本页
 * {@link #onSsrfBlocked}：每个 host 在同一 run 内只写一次 {@code run_event(WARN,
 * SSRF_BLOCKED, message="host=&lt;host&gt;")}（去重由闭包内 {@code emittedHosts} 维护）。
 *
 * <p>未找到 lane（lease 已被归还 / 跨调用方伪造 lease）抛 {@link IllegalStateException}，
 * 由 dispatcher 在 leaseAndSubmit 兜底捕获并写 {@code BROWSER_START_FAILED}。
 */
public class DefaultRunPageHandleProvider implements RunPageHandleProvider {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultRunPageHandleProvider.class);

    private final RunLanePool lanePool;
    private final RunRepository repository;
    private final RunResultSink sink;
    private final SsrfRouteGuard ssrfRouteGuard;

    public DefaultRunPageHandleProvider(RunLanePool lanePool,
                                        RunRepository repository,
                                        RunResultSink sink,
                                        SsrfRouteGuard ssrfRouteGuard) {
        if (lanePool == null) {
            throw new IllegalArgumentException("lanePool 不能为空");
        }
        if (repository == null) {
            throw new IllegalArgumentException("repository 不能为空");
        }
        if (sink == null) {
            throw new IllegalArgumentException("sink 不能为空");
        }
        if (ssrfRouteGuard == null) {
            throw new IllegalArgumentException("ssrfRouteGuard 不能为空");
        }
        this.lanePool = lanePool;
        this.repository = repository;
        this.sink = sink;
        this.ssrfRouteGuard = ssrfRouteGuard;
    }

    @Override
    public RunPageHandle openFor(Lease lease, long runId) {
        BrowserLane lane = lanePool.laneOf(lease);
        if (lane == null) {
            LOG.error("RunPageHandleProvider: 找不到 lease 关联的 lane runId={}", runId);
            throw new IllegalStateException("RunLanePool.lease 找不到 lane（已归还或伪造）");
        }
        try {
            PageStopDetector detector = new PageStopDetector(reason ->
                    onStopRun(runId, reason));
            // 每 run 独立 host 去重集合；listener 在 Playwright 分发线程上调用，写 DB
            // 通过 sink.appendBatch（spec §D3：listener 轻量、自行捕获异常）。
            Set<String> emittedHosts = ConcurrentHashMap.newKeySet();
            Consumer<String> onBlocked = host -> {
                if (host == null || host.isEmpty()) {
                    return;
                }
                if (emittedHosts.add(host)) {
                    onSsrfBlocked(runId, host);
                }
            };
            Consumer<BrowserContext> customizer =
                    ctx -> ssrfRouteGuard.install(ctx, onBlocked);
            return new DefaultRunPageHandle(lane, runId, detector, customizer);
        } catch (RuntimeException ex) {
            // Playwright 启动失败 / 资源耗尽；本 issue 允许 BROWSER_START_FAILED 兜底终态
            LOG.error("DefaultRunPageHandle 启动失败 runId={}", runId, ex);
            throw ex;
        }
    }

    /**
     * 检测器触发：写 run_event + markTerminal（spec §D10）。
     * 多次触发幂等（run 终态后 markTerminal 不再 UPDATE）。
     */
    private void onStopRun(long runId, StopReason reason) {
        String stage = switch (reason) {
            case HTTP_429 -> "STOP_429";
            case HTTP_403 -> "STOP_403_PERSISTENT";
            case CAPTCHA -> "STOP_CAPTCHA";
            default -> "STOP";
        };
        String msg = "detector at " + Instant.now();
        try {
            sink.appendBatch(runId, List.of(), List.of(
                    new RunEventInput(RunEventLevel.WARN, stage, null, null, msg)));
        } catch (RuntimeException ex) {
            LOG.warn("PageStopDetector onStop event failed runId={}: {}", runId, ex.getMessage());
        }
        try {
            repository.markTerminal(runId, RunState.FAILED, reason);
        } catch (RuntimeException ex) {
            LOG.warn("PageStopDetector onStop markTerminal failed runId={}: {}", runId, ex.getMessage());
        }
    }

    /**
     * SSRF 拦截事件（M6-1 / spec §D3）：写一条 WARN 事件 + 阶段码 {@code SSRF_BLOCKED}，
     * message 只含 host（spec §3 脱敏约定）。同 run 同一 host 由 caller 去重保证只写一次。
     */
    private void onSsrfBlocked(long runId, String host) {
        RunEventInput event = new RunEventInput(
                RunEventLevel.WARN,
                "SSRF_BLOCKED",
                null,
                null,
                "host=" + host);
        try {
            sink.appendBatch(runId, List.of(), List.of(event));
        } catch (RuntimeException ex) {
            LOG.warn("write SSRF_BLOCKED event failed runId={} host={}: {}",
                    runId, host, ex.getMessage());
        }
    }
}