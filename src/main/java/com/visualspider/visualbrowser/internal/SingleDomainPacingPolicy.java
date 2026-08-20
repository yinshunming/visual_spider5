package com.visualspider.visualbrowser.internal;

import com.visualspider.visualbrowser.spi.PacingPolicy;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * PacingPolicy 默认实现（M5-5 / issue #43 / spec §D9）。
 *
 * <p>并发 1（单 JVM 单 lane 自动满足）+ 间隔 ≥ 1s（{@link #DEFAULT_INTERVAL_MS}）。
 * 按目标 URL 的 {@code host} 维护"上次 navigate 时间戳"映射表；每次
 * {@link #beforeNavigate(URL)} 阻塞至距上次同域 navigate 满 1s。
 *
 * <p>M6 替换：本类变 fallback 默认；M6 通过 {@code system_setting} 注入新实现。
 */
@Component
public final class SingleDomainPacingPolicy implements PacingPolicy {

    private static final Logger LOG = LoggerFactory.getLogger(SingleDomainPacingPolicy.class);

    /** 间隔常量集中（spec §D9；M6 入 system_setting 时只改读取路径）。 */
    private static final Duration INTERVAL = Duration.ofMillis(DEFAULT_INTERVAL_MS);

    private final ConcurrentHashMap<String, Instant> lastByDomain = new ConcurrentHashMap<>();

    @Override
    public void beforeNavigate(URL targetUrl) {
        if (targetUrl == null) {
            return;
        }
        String domain = targetUrl.getHost();
        if (domain == null || domain.isBlank()) {
            return;
        }
        Instant now = Instant.now();
        Instant last = lastByDomain.get(domain);
        if (last == null || Duration.between(last, now).compareTo(INTERVAL) >= 0) {
            // 距上次 ≥ 1s，直接放行；登记本次
            lastByDomain.put(domain, now);
            return;
        }
        long sleepMs = INTERVAL.toMillis() - Duration.between(last, now).toMillis();
        if (sleepMs > 0) {
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                LOG.warn("SingleDomainPacingPolicy interrupted while pacing domain={}", domain);
            }
        }
        lastByDomain.put(domain, Instant.now());
    }
}