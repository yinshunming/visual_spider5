package com.visualspider.visualbrowser.api;

import com.visualspider.extraction.spi.InferredCandidateListItem;
import com.visualspider.extraction.spi.InferredCandidateListItem.AncestorHop;
import com.visualspider.extraction.spi.InferredCandidateListItem.ScoreComponent;
import com.visualspider.task.domain.ListItemRule;
import java.util.List;

/**
 * 候选列表项推断响应（M4-2 #32 / spec §D3）。
 *
 * <p>前端用 {@code score} + {@code components} 渲染"评分维度"调试条；
 * 用 {@code ancestorPath} 渲染"上溯路径"高亮；用 {@code alternatives} 渲染"并列候选"列表。
 */
public record InferResponse(
        String selector,
        String selectorType,
        int matchCount,
        double score,
        List<AncestorHopDto> ancestorPath,
        List<ScoreComponentDto> components,
        List<String> alternatives,
        boolean lowConfidence) {

    public record AncestorHopDto(int depth, String tagAndClass) {
        static AncestorHopDto from(AncestorHop hop) {
            return new AncestorHopDto(hop.depth(), hop.tagAndClass());
        }
    }

    public record ScoreComponentDto(String name, double raw, double weighted, String note) {
        static ScoreComponentDto from(ScoreComponent c) {
            return new ScoreComponentDto(c.name(), c.raw(), c.weighted(), c.note());
        }
    }

    public static InferResponse from(InferredCandidateListItem r) {
        List<AncestorHopDto> path = r.ancestorPath().stream().map(AncestorHopDto::from).toList();
        List<ScoreComponentDto> comps = r.components().stream().map(ScoreComponentDto::from).toList();
        List<String> alts = r.alternatives().stream().map(ListItemRule::selector).toList();
        return new InferResponse(
                r.rule().selector(),
                r.rule().selectorType().name(),
                r.matchCount(),
                r.score(),
                path,
                comps,
                alts,
                r.lowConfidence());
    }
}