package com.visualspider.visualbrowser.internal;

import java.util.List;
import java.net.InetAddress;

/**
 * DNS 解析内部 seam（M6-1 / spec §D2）：返回主机名的全部 A/AAAA 记录。
 *
 * <p>只在本模块 internal 使用（不进 {@code visualbrowser.spi}）；生产实现走
 * {@link java.net.InetAddress}，测试用 fake 构造"多 A 记录含私有 IP"、
 * "两次解析结果不同（DNS rebinding）"、"解析失败"场景，离线确定性。
 */
interface DnsResolver {

    /**
     * 解析主机名的全部地址。
     *
     * @return 全部 A/AAAA 结果；解析失败（NXDOMAIN 等）返回空列表（调用方 fail-closed 拒绝）
     */
    List<InetAddress> resolve(String host);
}
