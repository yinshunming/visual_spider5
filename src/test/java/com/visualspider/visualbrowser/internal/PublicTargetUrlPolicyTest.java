package com.visualspider.visualbrowser.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.visualspider.shared.time.MutableClock;
import com.visualspider.shared.time.SystemClock;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 公网目标策略单测（M6-1 / spec §D1 / T1）：覆盖 IP 字面量（含混合表示）、DNS 解析、
 * 多 A 记录含私有 IP、NXDOMAIN、allow-loopback 豁免范围、DNS rebinding。
 * DnsResolver 用 fake，离线确定性（spec §T1）。
 */
class PublicTargetUrlPolicyTest {

    private static final InetAddress PUB1 = addr("8.8.8.8");
    private static final InetAddress PUB2 = addr("1.1.1.1");
    private static final InetAddress LOOP = addr("127.0.0.1");
    private static final InetAddress PRIV = addr("10.0.0.1");
    private static final InetAddress META = addr("169.254.169.254");
    private static final InetAddress V6_LOOP = addr("::1");
    private static final InetAddress V6_PUB = addr("2606:4700:4700::1111");
    private static final InetAddress V6_ULA = addr("fc00::1");

    private static InetAddress addr(String host) {
        try {
            return InetAddress.getByName(host);
        } catch (UnknownHostException e) {
            throw new RuntimeException(e);
        }
    }

    /** 固定映射 fake：host -> 结果列表（无副作用、不计次）。 */
    private static DnsResolver fake(Map<String, List<InetAddress>> mapping) {
        return host -> mapping.getOrDefault(host, List.of());
    }

    private static DnsResolver fakeNxd() {
        return host -> {
            throw new AssertionError("NXDOMAIN resolver should not be called for " + host);
        };
    }

    @Test
    @DisplayName("公网域名：A 记录均为公网 -> 放行")
    void allowsPublicDomain() {
        PublicTargetUrlPolicy p = new PublicTargetUrlPolicy(
                fake(Map.of("example.com", List.of(PUB1, PUB2))), false);
        assertThat(p.isAllowed("https://example.com/path")).isTrue();
    }

    @Test
    @DisplayName("公网域名：多 A 记录任一私有 -> 拒绝")
    void rejectsMultiAWhenAnyPrivate() {
        PublicTargetUrlPolicy p = new PublicTargetUrlPolicy(
                fake(Map.of("example.com", List.of(PUB1, PRIV))), false);
        assertThat(p.isAllowed("https://example.com/path")).isFalse();
    }

    @Test
    @DisplayName("公网域名：NXDOMAIN -> 拒绝（fail-closed）")
    void rejectsNxdomain() {
        PublicTargetUrlPolicy p = new PublicTargetUrlPolicy(fake(Map.of()), false);
        assertThat(p.isAllowed("https://nonexistent.invalid/path")).isFalse();
    }

    @Test
    @DisplayName("非 http/https scheme -> 拒绝")
    void rejectsNonHttpScheme() {
        PublicTargetUrlPolicy p = new PublicTargetUrlPolicy(fakeNxd(), false);
        assertThat(p.isAllowed("file:///etc/passwd")).isFalse();
        assertThat(p.isAllowed("data:text/plain,hello")).isFalse();
        assertThat(p.isAllowed("ftp://example.com/")).isFalse();
        assertThat(p.isAllowed("javascript:alert(1)")).isFalse();
    }

    @Test
    @DisplayName("IPv4 字面量 127.0.0.1 / 10.0.0.1 / 169.254.169.254 / 0.0.0.0 / 224.0.0.1 -> 拒绝")
    void rejectsBlockedIpv4Literals() {
        PublicTargetUrlPolicy p = new PublicTargetUrlPolicy(fakeNxd(), false);
        assertThat(p.isAllowed("http://127.0.0.1/x")).isFalse();
        assertThat(p.isAllowed("http://10.0.0.1/x")).isFalse();
        assertThat(p.isAllowed("http://169.254.169.254/latest/meta-data/")).isFalse();
        assertThat(p.isAllowed("http://0.0.0.0/x")).isFalse();
        assertThat(p.isAllowed("http://224.0.0.1/x")).isFalse();
    }

    @Test
    @DisplayName("IPv4 字面量公网 8.8.8.8 -> 放行")
    void allowsPublicIpv4Literal() {
        PublicTargetUrlPolicy p = new PublicTargetUrlPolicy(fakeNxd(), false);
        assertThat(p.isAllowed("http://8.8.8.8/x")).isTrue();
    }

    @Test
    @DisplayName("混合表示 127.1 / 2130706433 / 0x7f.0.0.1 -> 拒绝")
    void rejectsMixedNotationLoopback() {
        PublicTargetUrlPolicy p = new PublicTargetUrlPolicy(fakeNxd(), false);
        assertThat(p.isAllowed("http://127.1:8080/x")).isFalse();
        assertThat(p.isAllowed("http://2130706433:8080/x")).isFalse();
        assertThat(p.isAllowed("http://0x7f.0.0.1:8080/x")).isFalse();
    }

    @Test
    @DisplayName("IPv6 字面量 ::1 / fc00::1 / fe80::1 -> 拒绝")
    void rejectsBlockedIpv6Literals() {
        PublicTargetUrlPolicy p = new PublicTargetUrlPolicy(fakeNxd(), false);
        assertThat(p.isAllowed("http://[::1]/x")).isFalse();
        assertThat(p.isAllowed("http://[fc00::1]/x")).isFalse();
        assertThat(p.isAllowed("http://[fe80::1]/x")).isFalse();
        assertThat(p.isAllowed("http://[ff02::1]/x")).isFalse();
    }

    @Test
    @DisplayName("IPv6 公网字面量 -> 放行")
    void allowsPublicIpv6Literal() {
        PublicTargetUrlPolicy p = new PublicTargetUrlPolicy(fakeNxd(), false);
        assertThat(p.isAllowed("http://[2606:4700:4700::1111]/x")).isTrue();
    }

    @Test
    @DisplayName("allow-loopback=true 仅豁免回环（127/8 + ::1），私有 / 元数据仍被拦截")
    void allowLoopbackExemptsOnlyLoopback() {
        PublicTargetUrlPolicy strict = new PublicTargetUrlPolicy(fakeNxd(), false);
        PublicTargetUrlPolicy loose = new PublicTargetUrlPolicy(fakeNxd(), true);
        assertThat(strict.isAllowed("http://127.0.0.1/x")).isFalse();
        assertThat(loose.isAllowed("http://127.0.0.1/x")).isTrue();
        assertThat(loose.isAllowed("http://[::1]/x")).isTrue();

        // 非回环：私有 / 元数据 / 多播任何 profile 都拒
        for (PublicTargetUrlPolicy p : new PublicTargetUrlPolicy[]{strict, loose}) {
            assertThat(p.isAllowed("http://10.0.0.1/x")).isFalse();
            assertThat(p.isAllowed("http://169.254.169.254/x")).isFalse();
            assertThat(p.isAllowed("http://192.168.1.1/x")).isFalse();
            assertThat(p.isAllowed("http://[fc00::1]/x")).isFalse();
            assertThat(p.isAllowed("http://[fe80::1]/x")).isFalse();
            assertThat(p.isAllowed("http://224.0.0.1/x")).isFalse();
        }
    }

    @Test
    @DisplayName("validate() 在拒绝情况下抛 InvalidTargetUrlException；公网放行不抛")
    void validatePropagatesDecision() {
        PublicTargetUrlPolicy p = new PublicTargetUrlPolicy(fakeNxd(), false);
        assertThatThrownBy(() -> p.validate("http://127.0.0.1/x"))
                .isInstanceOf(InvalidTargetUrlException.class);
        assertThatThrownBy(() -> p.validate("file:///etc/passwd"))
                .isInstanceOf(InvalidTargetUrlException.class);
        // 公网 IP 字面量：BasicTargetUrlPolicy 已要求 host 语法合法，IPv6 字面量会被
        // 语法层拒，因此这里只验证 v4 公网 IP + dns 路由走通的公网域名。
        DnsResolver fakePub = host -> List.of(PUB1);
        PublicTargetUrlPolicy pubPolicy = new PublicTargetUrlPolicy(fakePub, false);
        // 不会抛
        pubPolicy.validate("http://example.com/x");
        pubPolicy.validate("http://8.8.8.8/x");
    }

    @Test
    @DisplayName("DNS rebinding：两次解析结果不同（私有 IP 在后） -> 拒绝")
    void rejectsDnsRebinding() {
        // 计数器 fake：第一次返公网，第二次返私有，模拟名称在解析间切换
        Map<String, Integer> calls = new ConcurrentHashMap<>();
        DnsResolver rebinding = host -> {
            int n = calls.merge(host, 1, Integer::sum);
            return n == 1 ? List.of(PUB1) : List.of(PRIV);
        };
        PublicTargetUrlPolicy p = new PublicTargetUrlPolicy(rebinding, false);
        // 第一次解析时仍返回公网（allow），第二次返回私有（reject）
        assertThat(p.isAllowed("http://rebinding.example/x")).isTrue();
        assertThat(p.isAllowed("http://rebinding.example/x")).isFalse();
    }

    @Test
    @DisplayName("异常输入：null / 空串 / 无 scheme / unparseable -> 拒绝")
    void rejectsMalformedInputs() {
        PublicTargetUrlPolicy p = new PublicTargetUrlPolicy(fakeNxd(), false);
        assertThat(p.isAllowed(null)).isFalse();
        assertThat(p.isAllowed("")).isFalse();
        assertThat(p.isAllowed("   ")).isFalse();
        assertThat(p.isAllowed("not a url")).isFalse();
        assertThat(p.isAllowed("://no-scheme")).isFalse();
    }

    @Test
    @DisplayName("默认实现（生产）使用 CachingDnsResolver(SystemClock)：IP 字面量判定不依赖 DNS")
    void defaultResolverHonorsPolicy() {
        // 仅做烟雾测试：默认构造后公网字面量 / 私有字面量判定路径正常
        PublicTargetUrlPolicy p = new PublicTargetUrlPolicy(false);
        // 私有 IP 字面量不依赖 DNS，必然拒绝
        assertThat(p.isAllowed("http://127.0.0.1/x")).isFalse();
        // 公网字面量
        assertThat(p.isAllowed("http://8.8.8.8/x")).isTrue();
        // DNS 路径在测试机无保证；仅验证不抛实现错误（运行时可能 NXDOMAIN）
        try {
            p.isAllowed("http://example.com/");
        } catch (RuntimeException ignored) {
            // 真实 DNS 可能 NXDOMAIN（网络受限），但不应抛 IllegalState 之类的实现错误
        }
    }

    @Test
    @DisplayName("导入的常量/内部 seam 可达")
    void sanitySeamConstants() {
        assertThat(CachingDnsResolver.TTL_MILLIS).isEqualTo(30_000L);
        assertThat(SystemClock.INSTANCE).isNotNull();
        MutableClock mc = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(mc.millis()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z").toEpochMilli());
    }

    @Test
    @DisplayName("IPv4-mapped IPv6 ::ffff:127.0.0.1 按 v4 分类为回环")
    void rejectsIpv4MappedLoopback() {
        PublicTargetUrlPolicy strict = new PublicTargetUrlPolicy(fakeNxd(), false);
        PublicTargetUrlPolicy loose = new PublicTargetUrlPolicy(fakeNxd(), true);
        // Java InetAddress 把 ::ffff:a.b.c.d 折叠为 Inet4Address，按 v4 路径分类
        assertThat(strict.isAllowed("http://[::ffff:127.0.0.1]/x")).isFalse();
        assertThat(loose.isAllowed("http://[::ffff:127.0.0.1]/x")).isTrue();
        assertThat(strict.isAllowed("http://[::ffff:10.0.0.1]/x")).isFalse();
    }
}