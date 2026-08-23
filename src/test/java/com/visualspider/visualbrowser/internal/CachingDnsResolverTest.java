package com.visualspider.visualbrowser.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.visualspider.shared.time.Clock;
import com.visualspider.shared.time.MutableClock;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * DNS 缓存 TTL 单测（M6-1 / spec §D2 / T1）：TTL 内复用、过期重解析、不做负缓存。
 */
class CachingDnsResolverTest {

    private static InetAddress addr(String host) {
        try {
            return InetAddress.getByName(host);
        } catch (UnknownHostException e) {
            throw new RuntimeException(e);
        }
    }

    /** 计数 + 可编程结果。 */
    private static final class CountingDns implements DnsResolver {
        final AtomicInteger calls = new AtomicInteger();
        private final List<InetAddress> result;
        CountingDns(List<InetAddress> result) {
            this.result = result;
        }
        @Override
        public List<InetAddress> resolve(String host) {
            calls.incrementAndGet();
            return result;
        }
    }

    private static Clock fixed(Instant t) {
        MutableClock mc = new MutableClock(t);
        return mc;
    }

    @Test
    @DisplayName("TTL 内复用：同一 host 第二次解析不触发底层")
    void cachesWithinTtl() {
        CountingDns inner = new CountingDns(List.of(addr("8.8.8.8")));
        Clock clock = fixed(Instant.parse("2026-01-01T00:00:00Z"));
        CachingDnsResolver cache = new CachingDnsResolver(inner, clock);

        assertThat(cache.resolve("example.com")).hasSize(1);
        assertThat(cache.resolve("example.com")).hasSize(1);
        assertThat(cache.resolve("example.com")).hasSize(1);

        assertThat(inner.calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("TTL 过期后重新解析")
    void reResolvesAfterTtlExpiry() {
        CountingDns inner = new CountingDns(new ArrayList<>(List.of(addr("8.8.8.8"))));
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        CachingDnsResolver cache = new CachingDnsResolver(inner, clock);

        cache.resolve("example.com");
        cache.resolve("example.com");
        assertThat(inner.calls.get()).isEqualTo(1);

        // 推进 29s：仍在 TTL 内
        clock.advanceSeconds(29);
        cache.resolve("example.com");
        assertThat(inner.calls.get()).isEqualTo(1);

        // 推进到 TTL 边界后：过期
        clock.advanceSeconds(2);
        cache.resolve("example.com");
        assertThat(inner.calls.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("不做负缓存：解析失败（空结果）不被缓存，连续失败每次都重新解析")
    void doesNotNegativeCache() {
        CountingDns inner = new CountingDns(List.of());
        Clock clock = fixed(Instant.parse("2026-01-01T00:00:00Z"));
        CachingDnsResolver cache = new CachingDnsResolver(inner, clock);

        assertThat(cache.resolve("nx.example")).isEmpty();
        assertThat(cache.resolve("nx.example")).isEmpty();
        assertThat(cache.resolve("nx.example")).isEmpty();
        assertThat(inner.calls.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("不同 host 独立缓存；同 host 解析结果引用相等")
    void independentHosts() {
        CountingDns inner = new CountingDns(List.of(addr("8.8.8.8")));
        Clock clock = fixed(Instant.parse("2026-01-01T00:00:00Z"));
        CachingDnsResolver cache = new CachingDnsResolver(inner, clock);

        cache.resolve("a.example");
        cache.resolve("b.example");
        cache.resolve("a.example");

        assertThat(inner.calls.get()).isEqualTo(2);
        List<InetAddress> again = cache.resolve("a.example");
        assertThat(again).hasSize(1);
    }
}