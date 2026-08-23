package com.visualspider.visualbrowser.internal;

import java.net.Inet6Address;
import java.net.InetAddress;

/**
 * IP 地址分类（M6-1 / spec §D1）：纯函数，判定地址是否属于"非公网"网段。
 *
 * <p>拒绝网段（IPv4 / IPv6 对应）：
 * <ul>
 *   <li>回环：{@code 127/8}、{@code ::1}</li>
 *   <li>私有：{@code 10/8}、{@code 172.16/12}、{@code 192.168/16}、{@code fc00::/7}</li>
 *   <li>链路本地（含云元数据 {@code 169.254.169.254}）：{@code 169.254/16}、{@code fe80::/10}</li>
 *   <li>保留 / 特殊用途：{@code 0/8}（含未指定）、{@code 100.64/10} CGNAT、
 *       {@code 192.0.2/24}、{@code 198.51.100/24}、{@code 203.0.113/24}（TEST-NET）、
 *       {@code 198.18/15}（benchmark）、v4 组播 {@code 224/4}、v6 组播 {@code ff00::/8}、
 *       v6 未指定 {@code ::}</li>
 *   <li>IPv4-mapped（{@code ::ffff:a.b.c.d}）按 IPv4 规则分类</li>
 * </ul>
 *
 * <p>不依赖 DNS / 网络状态，可离线确定性测试（spec §T1）。
 */
final class IpAddressClassifier {

    private IpAddressClassifier() {
    }

    /** 是否属于拒绝网段（回环 / 私有 / 链路本地 / 保留 / 组播 / 未指定）。 */
    static boolean isBlocked(InetAddress address) {
        byte[] b = address.getAddress();
        if (address instanceof Inet6Address) {
            if (isV4Mapped(b)) {
                return isBlockedV4(slice(b, 12, 4));
            }
            if (isV6Loopback(b) || isV6Unspecified(b)) {
                return true;
            }
            if ((b[0] & 0xFE) == 0xFC) {
                return true;  // fc00::/7 唯一本地（ULA）
            }
            if (b[0] == (byte) 0xFE && (b[1] & 0xC0) == 0x80) {
                return true;  // fe80::/10 链路本地
            }
            if ((b[0] & 0xFF) == 0xFF) {
                return true;  // ff00::/8 组播
            }
            return false;
        }
        return isBlockedV4(b);
    }

    /** 是否回环地址（仅此类地址可被 {@code allow-loopback} 豁免，spec §D1/D4）。 */
    static boolean isLoopback(InetAddress address) {
        byte[] b = address.getAddress();
        if (address instanceof Inet6Address) {
            if (isV4Mapped(b)) {
                return (b[12] & 0xFF) == 127;
            }
            return isV6Loopback(b);
        }
        return (b[0] & 0xFF) == 127;
    }

    private static boolean isBlockedV4(byte[] b) {
        int o0 = b[0] & 0xFF;
        int o1 = b[1] & 0xFF;
        int o2 = b[2] & 0xFF;
        if (o0 == 127) return true;                        // 回环 127/8
        if (o0 == 10) return true;                         // 私有 10/8
        if (o0 == 172 && (o1 & 0xF0) == 16) return true;   // 私有 172.16/12
        if (o0 == 192 && o1 == 168) return true;           // 私有 192.168/16
        if (o0 == 169 && o1 == 254) return true;           // 链路本地 169.254/16（含云元数据）
        if (o0 == 0) return true;                          // 0/8（含未指定 0.0.0.0）
        if (o0 == 100 && (o1 & 0xC0) == 64) return true;   // CGNAT 100.64/10
        if (o0 == 192 && o1 == 0 && o2 == 2) return true;  // TEST-NET-1 192.0.2/24
        if (o0 == 198 && o1 == 51 && o2 == 100) return true;  // TEST-NET-2
        if (o0 == 203 && o1 == 0 && o2 == 113) return true;   // TEST-NET-3
        if (o0 == 198 && (o1 & 0xFE) == 18) return true;   // benchmark 198.18/15
        if ((o0 & 0xF0) == 0xE0) return true;              // 组播 224/4
        return false;
    }

    /** {@code ::ffff:a.b.c.d}：前 10 字节全零 + 2 字节 0xff 0xff。 */
    private static boolean isV4Mapped(byte[] b) {
        for (int i = 0; i < 10; i++) {
            if (b[i] != 0) {
                return false;
            }
        }
        return b[10] == (byte) 0xFF && b[11] == (byte) 0xFF;
    }

    /** {@code ::1}。 */
    private static boolean isV6Loopback(byte[] b) {
        for (int i = 0; i < 15; i++) {
            if (b[i] != 0) {
                return false;
            }
        }
        return b[15] == 1;
    }

    /** {@code ::}（全零）。 */
    private static boolean isV6Unspecified(byte[] b) {
        for (byte x : b) {
            if (x != 0) {
                return false;
            }
        }
        return true;
    }

    private static byte[] slice(byte[] src, int from, int len) {
        byte[] out = new byte[len];
        System.arraycopy(src, from, out, 0, len);
        return out;
    }
}
