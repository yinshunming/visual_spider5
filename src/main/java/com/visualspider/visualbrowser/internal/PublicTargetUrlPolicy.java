package com.visualspider.visualbrowser.internal;

import com.visualspider.shared.time.SystemClock;
import com.visualspider.visualbrowser.spi.TargetUrlPolicy;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * M6 公网目标 URL 策略（M6-1 / spec §D1）。
 *
 * <p>在 M2 语法层（{@link BasicTargetUrlPolicy}：scheme + 主机名语法）之上新增
 * IP 层校验，堵住"纯数字 IP（如 {@code 127.0.0.1}、{@code 169.254.169.254}）
 * 每个 DNS label 语法合法、必然放行"的 SSRF 缺口：
 * <ol>
 *   <li>host 是 IP 字面量（含混合表示 {@code 127.1} / {@code 0x7f.0.0.1} /
 *       {@code 2130706433} / IPv6）-> 直接 {@link IpAddressClassifier} 分类；</li>
 *   <li>否则 {@link DnsResolver} 解析全部 A/AAAA 结果，逐一分类，任一属于拒绝
 *       网段即拒绝（覆盖多记录 / DNS rebinding 首次解析）；</li>
 *   <li>解析失败（NXDOMAIN）fail-closed 拒绝。</li>
 * </ol>
 *
 * <p>{@code visualbrowser.target-url.allow-loopback}（默认 {@code false}）为
 * {@code true} 时<b>只</b>豁免回环地址（{@code 127/8}、{@code ::1}），供 it/dev
 * profile 的本地 fixture 使用；私有 / 链路本地 / 保留 / 云元数据地址任何配置下
 * 都不豁免（spec §D4）。
 *
 * <p>{@link #isAllowed} 同时供请求拦截热路径（{@code SsrfRouteGuard}）使用，
 * 绝不抛异常（任何解析/判定异常均视为不允许）。
 */
@Component
public final class PublicTargetUrlPolicy implements TargetUrlPolicy {

    private final BasicTargetUrlPolicy syntaxPolicy = new BasicTargetUrlPolicy();
    private final DnsResolver dns;
    private final boolean allowLoopback;

    @Autowired
    public PublicTargetUrlPolicy(
            @Value("${visualbrowser.target-url.allow-loopback:false}") boolean allowLoopback) {
        this(new CachingDnsResolver(new InetDnsResolver(), SystemClock.INSTANCE), allowLoopback);
    }

    /** 测试构造：注入 fake {@link DnsResolver}（离线、确定性，spec §T1）。 */
    PublicTargetUrlPolicy(DnsResolver dns, boolean allowLoopback) {
        this.dns = dns;
        this.allowLoopback = allowLoopback;
    }

    @Override
    public void validate(String url) {
        syntaxPolicy.validate(url);
        if (!isAllowed(url)) {
            throw new InvalidTargetUrlException();
        }
    }

    /**
     * 判定 URL 是否允许访问（拦截热路径用；fail-closed，不抛异常）。
     * 非 http/https scheme、无法解析、IP 字面量或 DNS 任一结果属于拒绝网段时返回 false。
     */
    public boolean isAllowed(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            if (scheme == null) {
                return false;
            }
            String normalized = scheme.toLowerCase(Locale.ROOT);
            if (!"http".equals(normalized) && !"https".equals(normalized)) {
                return false;
            }
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return false;
            }
            String h = stripBrackets(host);
            if (isIpLiteral(h)) {
                InetAddress addr;
                try {
                    // 纯数字 / 十六进制形式由 JVM 本地解析，不发 DNS 查询
                    addr = InetAddress.getByName(h);
                } catch (UnknownHostException e) {
                    return false;
                }
                return addressAllowed(addr);
            }
            List<InetAddress> addresses = dns.resolve(h);
            if (addresses.isEmpty()) {
                return false;  // NXDOMAIN / 解析失败：fail-closed
            }
            for (InetAddress addr : addresses) {
                if (!addressAllowed(addr)) {
                    return false;
                }
            }
            return true;
        } catch (RuntimeException e) {
            return false;  // URI 解析失败等：fail-closed
        }
    }

    private boolean addressAllowed(InetAddress addr) {
        if (!IpAddressClassifier.isBlocked(addr)) {
            return true;
        }
        return allowLoopback && IpAddressClassifier.isLoopback(addr);
    }

    /** IPv6 字面量在 URL 中带方括号（Java {@code URI.getHost} 原样返回），去掉后再解析。 */
    private static String stripBrackets(String host) {
        if (host.startsWith("[") && host.endsWith("]") && host.length() >= 2) {
            return host.substring(1, host.length() - 1);
        }
        return host;
    }

    /**
     * IP 字面量识别（spec §D1）：IPv6（含冒号）、十进制点分（1-4 组纯数字）、
     * 十六进制（含 {@code 0x} 前缀）、单个超长十进制整数（如 {@code 2130706433}）。
     * 其余形式按主机名走 DNS。
     */
    private static boolean isIpLiteral(String host) {
        if (host.indexOf(':') >= 0) {
            return true;
        }
        if (host.matches("\\d{1,3}(\\.\\d{1,3}){0,3}")) {
            return true;
        }
        if (host.matches("\\d+")) {
            return true;
        }
        return host.toLowerCase(Locale.ROOT).matches("0x[0-9a-f]+(\\.0x[0-9a-f]+){0,3}");
    }
}
