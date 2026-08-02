package com.visualspider.extraction.internal;

import com.visualspider.extraction.spi.CandidateListItemInferrer;
import com.visualspider.extraction.spi.DomSnapshot;
import com.visualspider.extraction.spi.DomSnapshot.AncestorSnapshot;
import com.visualspider.extraction.spi.DomSnapshot.ElementSignature;
import com.visualspider.extraction.spi.InferredCandidateListItem;
import com.visualspider.extraction.spi.InferredCandidateListItem.AncestorHop;
import com.visualspider.extraction.spi.InferredCandidateListItem.ScoreComponent;
import com.visualspider.task.domain.ListItemRule;
import com.visualspider.task.domain.SelectorType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 候选列表项推断：DOM 层级 + 启发式评分（M4 spec §D3）。
 *
 * <p>评分维度（spec §D3 加权和）：
 * <ul>
 *   <li>siblingCount 0.4：直接子元素 ≥ 2 且同类</li>
 *   <li>structureSimilarity 0.3：子结构 tag/attr jaccard ≥ 0.5</li>
 *   <li>textSimilarity 0.2：innerText 头部 token 集合 jaccard ≥ 0.4</li>
 *   <li>depthDistance 0.1：距 clicked 层级越近越好</li>
 * </ul>
 *
 * <p>score &gt;= 0.6 视为有效；并列分差 &lt; 0.05 时保留前 3 替代候选给 UI。
 *
 * <p>最大 ancestor 上溯层数 10（spec §D3）。
 */
@Service
public class DomLevelCandidateListItemInferrer implements CandidateListItemInferrer {

    private static final int MAX_ANCESTOR_DEPTH = 10;
    private static final double THRESHOLD = 0.6;
    private static final double TIE_DELTA = 0.05;
    private static final int MAX_ALTERNATIVES = 3;

    @Override
    public InferredCandidateListItem infer(DomSnapshot clicked) {
        // 返回评分最高的祖先层作为最佳候选（spec §D3）
        List<Scored> all = scoreAll(clicked);
        if (all.isEmpty()) {
            return emptyResult();
        }
        Scored pick = all.get(0);
        List<ListItemRule> alternatives = new ArrayList<>();
        for (Scored s : all) {
            if (s == pick) continue;
            if (Math.abs(s.score - pick.score) <= TIE_DELTA
                    && alternatives.size() < MAX_ALTERNATIVES) {
                alternatives.add(s.rule);
            }
        }
        return new InferredCandidateListItem(
                pick.rule,
                pick.matchCount,
                pick.score,
                ancestorPath(clicked, pick.depth),
                pick.components,
                alternatives);
    }

    @Override
    public InferredCandidateListItem adjustAncestor(DomSnapshot clicked, Direction direction) {
        List<Scored> all = scoreAll(clicked);
        if (all.isEmpty()) {
            return emptyResult();
        }
        Scored top = all.get(0);
        int currentDepth = top.depth;
        int target = direction == Direction.DOWN
                ? Math.max(1, currentDepth - 1)
                : Math.min(all.size(), currentDepth + 1);
        return pickAtDepth(clicked, target, all);
    }

    private InferredCandidateListItem pickAtDepth(DomSnapshot clicked, int targetDepth, List<Scored> scored) {
        if (scored.isEmpty()) {
            return emptyResult();
        }
        Scored pick = scored.stream()
                .filter(s -> s.depth == targetDepth)
                .findFirst()
                .or(() -> scored.stream()
                        .min(Comparator.comparingInt(s -> Math.abs(s.depth - targetDepth))))
                .orElse(scored.get(0));
        // 找并列替代候选：分差 ≤ 0.05
        List<ListItemRule> alternatives = new ArrayList<>();
        for (Scored s : scored) {
            if (s == pick) continue;
            if (Math.abs(s.score - pick.score) <= TIE_DELTA
                    && alternatives.size() < MAX_ALTERNATIVES) {
                alternatives.add(s.rule);
            }
        }
        return new InferredCandidateListItem(
                pick.rule,
                pick.matchCount,
                pick.score,
                ancestorPath(clicked, pick.depth),
                pick.components,
                alternatives);
    }

    private InferredCandidateListItem emptyResult() {
        return new InferredCandidateListItem(
                new ListItemRule("__no_match__", SelectorType.CSS),
                0, 0.0, List.of(), List.of(), List.of());
    }

    private List<AncestorHop> ancestorPath(DomSnapshot clicked, int upToDepth) {
        List<AncestorHop> path = new ArrayList<>();
        for (int i = 0; i <= upToDepth && i < clicked.ancestors().size(); i++) {
            AncestorSnapshot a = clicked.ancestors().get(i);
            path.add(new AncestorHop(i, tagAndClass(a.tagName(), a.className())));
        }
        return path;
    }

    private List<Scored> scoreAll(DomSnapshot clicked) {
        List<Scored> all = new ArrayList<>();
        int maxDepth = Math.min(clicked.ancestors().size() - 1, MAX_ANCESTOR_DEPTH);
        for (int depth = 1; depth <= maxDepth; depth++) {
            Scored s = scoreAt(clicked, depth);
            if (s != null && s.matchCount >= 2) {  // spec：候选须至少 2 个重复项
                all.add(s);
            }
        }
        all.sort(Comparator.comparingDouble((Scored x) -> x.score).reversed());
        return all;
    }

    private Scored scoreAt(DomSnapshot clicked, int depth) {
        AncestorSnapshot ancestor = clicked.ancestors().get(depth);
        int childCount = ancestor.childCount();
        List<ElementSignature> sigs = ancestor.childSignatures();
        if (sigs.isEmpty()) {
            return null;
        }
        // siblingCount (0.4)：同类子元素占比 * min(count, 8)/8
        double sameKindRatio = ratioSameKind(sigs, ancestor.tagName(), ancestor.className());
        double siblingRaw = Math.min(childCount, 8) / 8.0 * sameKindRatio;
        // structureSimilarity (0.3)：子结构 jaccard
        double structRaw = pairwiseJaccard(sigs, DomLevelCandidateListItemInferrer::structureKey);
        // textSimilarity (0.2)：子 innerText token jaccard
        double textRaw = pairwiseJaccard(sigs, ElementSignature::textTokens);
        // depthDistance (0.1)：1/(1+depth)
        double depthRaw = 1.0 / (1 + depth);

        double weighted = siblingRaw * 0.4 + structRaw * 0.3 + textRaw * 0.2 + depthRaw * 0.1;

        List<ScoreComponent> components = List.of(
                new ScoreComponent("siblingCount", siblingRaw, siblingRaw * 0.4, "n=" + childCount + " sameRatio=" + sameKindRatio),
                new ScoreComponent("structureSimilarity", structRaw, structRaw * 0.3, "jaccard over child structs"),
                new ScoreComponent("textSimilarity", textRaw, textRaw * 0.2, "jaccard over text tokens"),
                new ScoreComponent("depthDistance", depthRaw, depthRaw * 0.1, "depth=" + depth));
        ListItemRule rule = buildRule(ancestor);
        return new Scored(depth, rule, childCount, weighted, components);
    }

    /**
     * siblings 自身 tag 一致性（spec §D3 "直接子元素 ≥ 2 且同类"）。
     *
     * <p>"同类" = 第一项的 tagName 占所有 sibling 比例；不与 ancestor tag 比较
     * （否则 tr 容器下 td 子元素会被错认为 0 同类）。
     */
    private static double ratioSameKind(List<ElementSignature> sigs, String ancTag, String ancClass) {
        if (sigs.isEmpty()) return 0.0;
        ElementSignature first = sigs.get(0);
        if (first == null || first.tagName() == null) return 0.0;
        String firstTag = first.tagName();
        long same = sigs.stream()
                .filter(sig -> firstTag.equalsIgnoreCase(sig.tagName()))
                .count();
        return (double) same / sigs.size();
    }

    /**
     * 对子元素签名两两计算 jaccard，取平均。
     */
    private static double pairwiseJaccard(List<ElementSignature> sigs,
                                          java.util.function.Function<ElementSignature, ?> keyFn) {
        if (sigs.size() < 2) return 0.0;
        int pairs = 0;
        double sum = 0;
        for (int i = 0; i < sigs.size(); i++) {
            for (int j = i + 1; j < sigs.size(); j++) {
                sum += jaccardOf(sigs.get(i), sigs.get(j), keyFn);
                pairs++;
            }
        }
        return pairs == 0 ? 0.0 : sum / pairs;
    }

    @SuppressWarnings("unchecked")
    private static double jaccardOf(ElementSignature a, ElementSignature b,
                                    java.util.function.Function<ElementSignature, ?> keyFn) {
        Object oa = keyFn.apply(a);
        Object ob = keyFn.apply(b);
        if (!(oa instanceof Set) || !(ob instanceof Set)) return 0.0;
        Set<String> sa = new HashSet<>((Set<String>) oa);
        Set<String> sb = new HashSet<>((Set<String>) ob);
        if (sa.isEmpty() && sb.isEmpty()) return 0.0;
        Set<String> intersect = new LinkedHashSet<>(sa);
        intersect.retainAll(sb);
        Set<String> union = new LinkedHashSet<>(sa);
        union.addAll(sb);
        return union.isEmpty() ? 0.0 : (double) intersect.size() / union.size();
    }

    private static Set<String> structureKey(ElementSignature sig) {
        Set<String> keys = new LinkedHashSet<>();
        keys.add("tag:" + (sig.tagName() == null ? "" : sig.tagName().toLowerCase(Locale.ROOT)));
        if (sig.className() != null && !sig.className().isBlank()) {
            for (String c : sig.className().split("\\s+")) {
                keys.add("class:" + c);
            }
        }
        keys.addAll(sig.attributeKeys());
        return keys;
    }

    private static ListItemRule buildRule(AncestorSnapshot a) {
        String tag = a.tagName() == null ? "div" : a.tagName().toLowerCase(Locale.ROOT);
        if (a.className() != null && !a.className().isBlank()) {
            String firstClass = a.className().split("\\s+")[0];
            return new ListItemRule(tag + "." + firstClass, SelectorType.CSS);
        }
        return new ListItemRule(tag, SelectorType.CSS);
    }

    private static String tagAndClass(String tag, String cls) {
        if (tag == null) {
            return "";
        }
        if (cls == null || cls.isBlank()) {
            return tag;
        }
        return tag + "." + cls.split("\\s+")[0];
    }

    /** 内部评分结果，{@code depth} 1..N 表示祖先层深度。 */
    private record Scored(int depth, ListItemRule rule, int matchCount, double score,
                          List<ScoreComponent> components) {}
}
