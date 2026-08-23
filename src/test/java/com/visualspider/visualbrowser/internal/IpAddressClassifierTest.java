package com.visualspider.visualbrowser.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * IP 分类单测（M6-1 / spec §D1 / T1）：覆盖所有拒绝网段与若干公网样本。
 * 不依赖 DNS / 网络（spec §T1）。
 */
class IpAddressClassifierTest {

    @ParameterizedTest(name = "{0} -> blocked={1} loopback={2}")
    @CsvSource({
            // IPv4 拒绝网段
            "'127.0.0.1',     true,  true",   // 回环
            "'127.255.255.7', true,  true",
            "'10.0.0.1',      true,  false",
            "'10.255.255.255',true,  false",
            "'172.16.0.1',    true,  false",
            "'172.31.255.255',true,  false",
            "'192.168.1.1',   true,  false",
            "'169.254.0.1',   true,  false",  // 链路本地
            "'169.254.169.254',true, false",  // 云元数据
            "'0.0.0.0',       true,  false",  // 未指定 / 0/8
            "'0.1.2.3',       true,  false",
            "'100.64.0.1',    true,  false",  // CGNAT
            "'100.127.255.255',true, false",
            "'192.0.2.1',     true,  false",  // TEST-NET-1
            "'198.51.100.1',  true,  false",  // TEST-NET-2
            "'203.0.113.1',   true,  false",  // TEST-NET-3
            "'198.18.0.1',    true,  false",  // benchmark
            "'198.19.255.255',true,  false",
            "'224.0.0.1',     true,  false",  // 组播
            "'239.255.255.255',true, false",

            // IPv4 公网样本
            "'8.8.8.8',       false, false",
            "'1.1.1.1',       false, false",
            "'93.184.216.34', false, false",

            // IPv6 拒绝网段
            "'::1',           true,  true",   // 回环
            "'fe80::1',       true,  false",  // 链路本地
            "'fc00::1',       true,  false",  // ULA
            "'fd12:3456::1',  true,  false",  // ULA 范围内
            "'ff02::1',       true,  false",  // 组播
            "'::',            true,  false",  // 未指定

            // IPv6 公网样本
            "'2606:4700:4700::1111', false, false",
            "'2001:4860:4860::8888',  false, false",
    })
    void classifiesIpAddresses(String host, boolean expectedBlocked, boolean expectedLoopback) throws Exception {
        InetAddress addr = InetAddress.getByName(host);
        assertThat(IpAddressClassifier.isBlocked(addr))
                .as("blocked for %s", host)
                .isEqualTo(expectedBlocked);
        assertThat(IpAddressClassifier.isLoopback(addr))
                .as("loopback for %s", host)
                .isEqualTo(expectedLoopback);
    }
}