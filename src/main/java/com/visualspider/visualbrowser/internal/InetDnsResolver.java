package com.visualspider.visualbrowser.internal;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;

/**
 * 生产 DNS 解析实现（M6-1 / spec §D2）：{@link InetAddress#getAllByName}。
 *
 * <p>解析失败（NXDOMAIN / 临时失败）返回空列表，由策略层 fail-closed 拒绝。
 */
final class InetDnsResolver implements DnsResolver {

    @Override
    public List<InetAddress> resolve(String host) {
        try {
            InetAddress[] all = InetAddress.getAllByName(host);
            return List.copyOf(Arrays.asList(all));
        } catch (UnknownHostException e) {
            return List.of();
        }
    }
}
