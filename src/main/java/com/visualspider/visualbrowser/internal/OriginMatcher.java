package com.visualspider.visualbrowser.internal;

import java.net.URI;
import java.util.Locale;

/**
 * Same-Origin 校验：比较 WebSocket 请求 Origin 与 requestUri 协议/主机/端口。
 *
 * <p>M2-1 #17 决策：通过 Spring Security 已同源，仍在 handshake interceptor 中显式校验，
 * 防止配置错误（同源放行）与跨站 WebSocket Hijacking 漏洞利用。
 */
public final class OriginMatcher {

    private OriginMatcher() {}

    /** {@code origin} 与 {@code requestUri} 同源返回 true。null/非法 Origin 一律拒绝。 */
    public static boolean matches(String origin, String requestUri) {
        if (origin == null || origin.isBlank()) {
            return false;
        }
        try {
            URI originUri = URI.create(origin);
            URI reqUri = URI.create(requestUri);
            String scheme = lower(originUri.getScheme());
            String reqScheme = lower(reqUri.getScheme());
            if (scheme == null || reqScheme == null || !scheme.equals(reqScheme)) {
                return false;
            }
            String host = lower(originUri.getHost());
            String reqHost = lower(reqUri.getHost());
            if (host == null || reqHost == null || !host.equals(reqHost)) {
                return false;
            }
            int port = originUri.getPort() == -1 ? defaultPort(originUri.getScheme())
                    : originUri.getPort();
            int reqPort = reqUri.getPort() == -1 ? defaultPort(reqUri.getScheme())
                    : reqUri.getPort();
            return port == reqPort;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private static String lower(String s) {
        return s == null ? null : s.toLowerCase(Locale.ROOT);
    }

    private static int defaultPort(String scheme) {
        if (scheme == null) {
            return -1;
        }
        return switch (scheme.toLowerCase(Locale.ROOT)) {
            case "http" -> 80;
            case "https", "wss" -> 443;
            case "ws" -> 80;
            default -> -1;
        };
    }
}
