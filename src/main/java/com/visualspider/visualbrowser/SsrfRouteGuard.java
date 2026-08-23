package com.visualspider.visualbrowser;

import com.microsoft.playwright.BrowserContext;
import com.visualspider.visualbrowser.spi.TargetUrlPolicy;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * SSRF request interception registrar (M6-1 / spec D3).
 *
 * <p>Registers {@code context.route(...)} with a wildcard pattern on a
 * {@link BrowserContext} so that every request the browser issues inside it
 * (top-level navigation, HTTP redirects, iframes, images, scripts, XHR subresources)
 * is validated against {@link TargetUrlPolicy}. Allowed requests {@code resume};
 * blocked requests {@code abort}. This single mechanism covers top-level
 * navigation, redirect targets and subresources.
 *
 * <p>{@link #install} must be called on the lane thread that created the context; the
 * route callback runs on the Playwright dispatcher thread and performs pure JVM work
 * (policy + cached DNS) plus counter increments. {@code context.close()} unregisters.
 *
 * <p>Blocked count is exposed via micrometer {@code ssrf.blocked}. The DEBUG log line
 * records the host only (no URL, path, or payload). The optional {@code onBlockedHost}
 * consumer is used by the run side to write {@code run_event(WARN, SSRF_BLOCKED,
 * "host=<host>")}; it runs on the Playwright dispatcher thread, must stay lightweight,
 * and is invoked at most once per host per run by the caller's own dedup.
 */
@Component
public final class SsrfRouteGuard {

    private static final Logger LOG = LoggerFactory.getLogger(SsrfRouteGuard.class);

    private final TargetUrlPolicy policy;
    private final Counter ssrfBlocked;

    public SsrfRouteGuard(TargetUrlPolicy policy, MeterRegistry registry) {
        if (policy == null) {
            throw new IllegalArgumentException("policy 不能为空");
        }
        if (registry == null) {
            throw new IllegalArgumentException("registry 不能为空");
        }
        this.policy = policy;
        this.ssrfBlocked = Counter.builder("ssrf.blocked")
                .description("SSRF 请求拦截（route abort）次数")
                .register(registry);
    }

    /** 注册无回调的拦截（配置会话 / readiness 等无 run_event 语境的场景）。 */
    public void install(BrowserContext context) {
        install(context, null);
    }

    /**
     * 注册拦截并可选接收"被拦截 host"回调（运行侧写 SSRF_BLOCKED 事件用）。
     *
     * @param onBlockedHost 被拦截时以请求 host 调用；可为 null
     */
    public void install(BrowserContext context, Consumer<String> onBlockedHost) {
        context.route("**/*", route -> {
            String url = route.request().url();
            if (isAllowed(url)) {
                route.resume();
                return;
            }
            String host = hostOf(url);
            ssrfBlocked.increment();
            LOG.debug("ssrf blocked host={}", host);
            if (onBlockedHost != null) {
                try {
                    onBlockedHost.accept(host);
                } catch (RuntimeException e) {
                    LOG.warn("ssrf onBlockedHost callback failed: {}", e.getMessage());
                }
            }
            route.abort();
        });
    }

    private boolean isAllowed(String url) {
        try {
            policy.validate(url);
            return true;
        } catch (RuntimeException e) {
            return false;  // 含 InvalidTargetUrlException；fail-closed
        }
    }

    private static String hostOf(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null ? "<no-host>" : host;
        } catch (RuntimeException e) {
            return "<unparseable>";
        }
    }
}
