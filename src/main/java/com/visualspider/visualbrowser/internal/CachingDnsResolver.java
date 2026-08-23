package com.visualspider.visualbrowser.internal;

import com.visualspider.shared.time.Clock;
import java.net.InetAddress;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DNS 解析 TTL 缓存装饰器（M6-1 / spec §D2）。
 *
 * <p>为请求拦截热路径（同一页面几十个子资源同 host）减少重复解析；TTL 30s 是
 * 性能与防护窗口的折中（见 {@code docs/security/ssrf-residual-risk.md}）。
 * 不做负缓存（解析失败的结果不缓存），失败路径保持实时重试。
 *
 * <p>线程安全：route 回调运行在多个 lane 的 Playwright 分发线程上，缓存表用
 * {@link ConcurrentHashMap}，条目为不可变 record。
 */
final class CachingDnsResolver implements DnsResolver {

    /** 缓存 TTL（毫秒）。包级可见供测试断言。 */
    static final long TTL_MILLIS = 30_000L;

    private record Entry(List<InetAddress> addresses, long expiresAtMillis) {
        boolean expired(long nowMillis) {
            return nowMillis >= expiresAtMillis;
        }
    }

    private final DnsResolver delegate;
    private final Clock clock;
    private final ConcurrentHashMap<String, Entry> cache = new ConcurrentHashMap<>();

    CachingDnsResolver(DnsResolver delegate, Clock clock) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate 不能为空");
        }
        if (clock == null) {
            throw new IllegalArgumentException("clock 不能为空");
        }
        this.delegate = delegate;
        this.clock = clock;
    }

    @Override
    public List<InetAddress> resolve(String host) {
        long now = clock.millis();
        Entry cached = cache.get(host);
        if (cached != null && !cached.expired(now)) {
            return cached.addresses();
        }
        List<InetAddress> resolved = delegate.resolve(host);
        if (!resolved.isEmpty()) {
            cache.put(host, new Entry(resolved, now + TTL_MILLIS));
        }
        return resolved;
    }
}
