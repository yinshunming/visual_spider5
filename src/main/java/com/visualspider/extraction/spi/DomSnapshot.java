package com.visualspider.extraction.spi;

import java.util.List;
import java.util.Map;

/**
 * DOM 摘要：{@link CandidateListItemInferrer} 的输入（M4 spec §D3）。
 *
 * <p>{@code ancestors} 长度为 1..N；索引 0 = clicked 元素自身；索引 i = 第 i 层祖先
 * （parent.parent...）。DOM 提取由 Playwright lane 上 {@code evaluate(...)} 完成，
 * 构造 {@link DomSnapshot} 后交给 inferrer 做纯函数评分。
 *
 * <p>提取接口在 M4-6 (#36) 接入 {@code VisualSessionChannel.selectElement}
 * 的扩展路径（点选代表项时构造）。
 */
public record DomSnapshot(
        String tagName,
        String className,
        Map<String, String> attributes,
        String innerTextSnippet,
        List<AncestorSnapshot> ancestors) {

    public DomSnapshot {
        if (ancestors == null || ancestors.isEmpty()) {
            throw new IllegalArgumentException("ancestors 必须 ≥ 1（含 clicked 自身）");
        }
        if (ancestors.get(0) == null) {
            throw new IllegalArgumentException("ancestors[0] 为 clicked 自身；不能为空");
        }
    }

    /**
     * 祖先层 DOM 摘要：tag / class / 子元素签名等。
     */
    public record AncestorSnapshot(
            String tagName,
            String className,
            Map<String, String> attributes,
            String innerTextSnippet,
            int childCount,
            List<ElementSignature> childSignatures) {

        public AncestorSnapshot {
            if (childSignatures == null) {
                childSignatures = List.of();
            }
        }
    }

    /**
     * 一个子元素的签名：用于结构相似度计算（M4 spec §D3 结构 jaccard）。
     *
     * <p>{@code childTagCounts} = 该元素直接子元素的 tag -> 计数（如 {@code {td:3}}）。
     * M4-2 (#32) 新增：让 structureSimilarity 能区分"同 tag 但子结构不同"的容器
     *（如 {@code <tr>} 的 3 个 {@code <td>} 字段格 vs {@code <tbody>} 的 5 个 {@code <tr>} 行），
     * 避免 inferrer 把"单元格相似的行"误判为列表项容器。
     */
    public record ElementSignature(
            String tagName,
            String className,
            java.util.Set<String> attributeKeys,
            java.util.Set<String> textTokens,
            Map<String, Integer> childTagCounts) {

        public ElementSignature {
            if (attributeKeys == null) {
                attributeKeys = java.util.Set.of();
            }
            if (textTokens == null) {
                textTokens = java.util.Set.of();
            }
            if (childTagCounts == null) {
                childTagCounts = Map.of();
            }
        }

        /** 兼容旧构造（无 childTagCounts，视为无子元素）。 */
        public ElementSignature(String tagName, String className,
                                java.util.Set<String> attributeKeys,
                                java.util.Set<String> textTokens) {
            this(tagName, className, attributeKeys, textTokens, Map.of());
        }
    }
}
