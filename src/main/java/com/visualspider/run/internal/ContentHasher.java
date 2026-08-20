package com.visualspider.run.internal;

import com.visualspider.extraction.spi.ExtractionPreview.Node;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;

/**
 * list 页内容 hash（M5-3 / issue #41 / spec §D5）。
 *
 * <p>NEXT_PAGE 模式重复页保护的 contentHash 一侧：对当前页 list-item 集合计算
 * "规范化文本串联" 的 SHA-256（每个 item 的 {@code textContent} 去多余空白后
 * 以 {@code ||} 连接）。复用 M4 {@code UniqueKeyHasher} 的 SHA-256 + canonical
 * 思路，但仅做内存比对，不写 DB。
 *
 * <p>对 query 参数分页（URL 变但内容同）与 JS 状态机分页（URL 不变）都能识别
 * "同一页"：前者 hash 相同，后者 URL 相同（由 {@code PagingExecutor} 双重比对）。
 */
final class ContentHasher {

    /**
     * 计算 list-item 集合内容 hash。
     *
     * @param items 当前页 listItemRule 命中的元素集（顺序敏感，与 DOM 顺序一致）
     * @return SHA-256 hex 字符串；空列表返回空串的 hash（页面间仍可区分）
     */
    String hash(List<Node> items) {
        List<Node> safe = items == null ? List.of() : items;
        String joined = safe.stream()
                .map(n -> normalize(n.textContent()))
                .collect(Collectors.joining("||"));
        return sha256Hex(joined);
    }

    /** 规范化：压缩所有空白为单空格并去除首尾（跨行 / 缩进差异不改变 hash）。 */
    static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("\\s+", " ").trim();
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }
}
