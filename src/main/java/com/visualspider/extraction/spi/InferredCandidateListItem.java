package com.visualspider.extraction.spi;

import com.visualspider.task.domain.ListItemRule;
import java.util.List;

/**
 * 候选列表项推断结果（M4 spec §D3）。
 *
 * <p>{@code score} 加权后总分，{@code matchCount} 为该候选 ancestor 容器内的
 * 同类子元素数；{@code ancestorPath} 给出从 clicked 起的 ancestor 链摘要，
 * 可被 {@code VisualSession} UI 渲染（"上溯 / 下移" 调整）。
 *
 * <p>{@code alternatives} 当并列分差 < 0.05 时给出 ≤ 3 个候选，供 UI 选择。
 */
public record InferredCandidateListItem(
        ListItemRule rule,
        int matchCount,
        double score,
        List<AncestorHop> ancestorPath,
        List<ScoreComponent> components,
        List<ListItemRule> alternatives) {

    public InferredCandidateListItem {
        if (ancestorPath == null) {
            ancestorPath = List.of();
        }
        if (components == null) {
            components = List.of();
        }
        if (alternatives == null) {
            alternatives = List.of();
        }
    }

    public record AncestorHop(int depth, String tagAndClass) {}

    public record ScoreComponent(String name, double raw, double weighted, String note) {
        public ScoreComponent(String name, double raw, double weighted) {
            this(name, raw, weighted, null);
        }
    }
}
