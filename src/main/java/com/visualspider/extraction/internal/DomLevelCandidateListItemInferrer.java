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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 候选列表项推断：DOM 层级 + 启发式评分（M4 spec §D3）。
 *
 * <p><b>评分对象</b>：对 clicked 的每层祖先（depth &gt;= 1，即 parent 及以上）评分。
 * 该祖先被视为"候选<b>容器</b>"，其直接子元素中<b>同 tag+class 的多数派</b>被视为"列表项"。
 * 最终 {@link ListItemRule#selector()} 指向<b>列表项</b>（与 {@code ListRunExecutor} /
 * {@code ExtractionPreviewImpl} 的 {@code dom.query(listItemRule.selector())} 语义一致：
 * 选出 N 个 item，而非 1 个容器）。
 *
 * <p><b>评分维度</b>（spec §D3 加权和，权重不变）：
 * <ul>
 *   <li>siblingCount 0.4：多数派子元素数（matchCount），{@code min(count,5)/5} 饱和</li>
 *   <li>structureSimilarity 0.3：多数派子元素两两 structureKey jaccard；
 *       structureKey 含 {@link ElementSignature#childTagCounts()}，故"子结构一致"的容器
 *       （如 tbody 的 5 个 tr，每个 tr 都含 3 个 td）得高分，"子结构各异"的容器
 *       （如 tr 的 3 个 td，分别是 a/span/文本）得低分</li>
 *   <li>textSimilarity 0.2：多数派子元素 innerText token jaccard</li>
 *   <li>depthDistance 0.1：{@code 1/(1+depth)}，距 clicked 越近越好</li>
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
    private static final int SIBLING_SATURATE = 5;

    @Override
    public InferredCandidateListItem infer(DomSnapshot clicked) {
        List<Scored> all = scoreAll(clicked);
        if (all.isEmpty() || all.get(0).score < THRESHOLD) {
            return InferredCandidateListItem.empty();
        }
        return buildResult(clicked, all.get(0), all);
    }

    @Override
    public InferredCandidateListItem adjustAncestor(DomSnapshot clicked, Direction direction) {
        // 注：步骤 5 "重跑 2–4" 字面含阈值过滤；但 adjustAncestor 是<b>用户驱动</b>的方向调整，
        // 应允许探索低分候选（用户主动 UP/DOWN 时不应被阈值阻挡）。阈值仅作用于 infer 的初始
        // 自动选择；adjustAncestor 在所有 matchCount ≥ 2 候选中找方向目标，target 的实际
        // score 由 components 暴露给 UI 判断。
        List<Scored> all = scoreAll(clicked);
        if (all.isEmpty()) {
            return InferredCandidateListItem.empty();
        }
        Scored top = all.get(0);
        int currentDepth = top.depth;
        // 方向感知：跳过无效层（matchCount < 2），找该方向上最近的候选容器。
        // UP（更粗容器）：depth > current 中最小者；DOWN（更细容器）：depth < current 中最大者。
        Scored target = (direction == Direction.UP)
                ? all.stream().filter(s -> s.depth > currentDepth)
                        .min(Comparator.comparingInt(s -> s.depth)).orElse(null)
                : all.stream().filter(s -> s.depth < currentDepth)
                        .max(Comparator.comparingInt(s -> s.depth)).orElse(null);
        if (target == null) {
            return InferredCandidateListItem.empty();
        }
        return buildResult(clicked, target, all);
    }

    private InferredCandidateListItem buildResult(DomSnapshot clicked, Scored pick, List<Scored> all) {
        List<ListItemRule> alternatives = new ArrayList<>();
        for (Scored s : all) {
            if (s == pick) {
                continue;
            }
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
                alternatives,
                false);
    }

    private List<AncestorHop> ancestorPath(DomSnapshot clicked, int upToDepth) {
        List<AncestorHop> path = new ArrayList<>();
        for (int i = 0; i <= upToDepth && i < clicked.ancestors().size(); i++) {
            AncestorSnapshot a = clicked.ancestors().get(i);
            path.add(new AncestorHop(i, tagAndClass(a.tagName(), a.className())));
        }
        return path;
    }

    private static int maxScorableDepth(DomSnapshot clicked) {
        return Math.min(clicked.ancestors().size() - 1, MAX_ANCESTOR_DEPTH);
    }

    private List<Scored> scoreAll(DomSnapshot clicked) {
        List<Scored> all = new ArrayList<>();
        int maxDepth = maxScorableDepth(clicked);
        for (int depth = 1; depth <= maxDepth; depth++) {
            Scored s = scoreAt(clicked, depth);
            if (s != null && s.matchCount >= 2) {
                all.add(s);
            }
        }
        all.sort(Comparator.comparingDouble((Scored x) -> x.score).reversed()
                .thenComparingInt(x -> x.depth));
        return all;
    }

    /**
     * 对 depth 层祖先（容器）评分：其直接子元素中的多数派 = 列表项。
     */
    private Scored scoreAt(DomSnapshot clicked, int depth) {
        AncestorSnapshot container = clicked.ancestors().get(depth);
        int childCount = container.childCount();
        List<ElementSignature> sigs = container.childSignatures();
        if (sigs == null || sigs.isEmpty() || childCount < 2) {
            return null;
        }
        DominantChild dominant = dominantChild(sigs);
        if (dominant == null || dominant.matchCount() < 2) {
            return null;
        }
        List<ElementSignature> items = dominant.signatures();

        // siblingCount (0.4)：多数派 item 数，min(count,5)/5 饱和
        double siblingRaw = Math.min(dominant.matchCount(), SIBLING_SATURATE) / (double) SIBLING_SATURATE;
        // structureSimilarity (0.3)：多数派 item 两两 structureKey jaccard（含 childTagCounts）
        double structRaw = pairwiseJaccard(items, DomLevelCandidateListItemInferrer::structureKey);
        // textSimilarity (0.2)：多数派 item textTokens jaccard
        double textRaw = pairwiseJaccard(items, ElementSignature::textTokens);
        // depthDistance (0.1)：1/(1+depth)，越近越好
        double depthRaw = 1.0 / (1 + depth);

        double weighted = siblingRaw * 0.4 + structRaw * 0.3 + textRaw * 0.2 + depthRaw * 0.1;

        double sameKindRatio = (double) dominant.matchCount() / childCount;
        List<ScoreComponent> components = List.of(
                new ScoreComponent("siblingCount", siblingRaw, siblingRaw * 0.4,
                        "items=" + dominant.matchCount() + " of children=" + childCount + " ratio=" + sameKindRatio),
                new ScoreComponent("structureSimilarity", structRaw, structRaw * 0.3,
                        "jaccard over item structureKeys (incl childTagCounts)"),
                new ScoreComponent("textSimilarity", textRaw, textRaw * 0.2, "jaccard over item text tokens"),
                new ScoreComponent("depthDistance", depthRaw, depthRaw * 0.1, "depth=" + depth));
        ListItemRule rule = buildRule(dominant);
        return new Scored(depth, rule, dominant.matchCount(), weighted, components);
    }

    /**
     * 多数派子元素：按 (tag, firstClass) 分组，取数量最多的一组。同数时取首个出现者。
     */
    private static DominantChild dominantChild(List<ElementSignature> sigs) {
        LinkedHashMap<String, List<ElementSignature>> groups = new LinkedHashMap<>();
        for (ElementSignature sig : sigs) {
            if (sig == null || sig.tagName() == null) {
                continue;
            }
            String key = kindKey(sig);
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(sig);
        }
        String bestKey = null;
        int bestCount = 0;
        for (Map.Entry<String, List<ElementSignature>> e : groups.entrySet()) {
            if (e.getValue().size() > bestCount) {
                bestCount = e.getValue().size();
                bestKey = e.getKey();
            }
        }
        if (bestKey == null) {
            return null;
        }
        return new DominantChild(bestKey, groups.get(bestKey));
    }

    private static String kindKey(ElementSignature sig) {
        String tag = sig.tagName().toLowerCase(Locale.ROOT);
        String cls = firstClass(sig.className());
        return cls.isEmpty() ? tag : tag + "." + cls;
    }

    /**
     * 对子元素签名两两计算 jaccard，取平均。
     */
    private static double pairwiseJaccard(List<ElementSignature> sigs,
                                          java.util.function.Function<ElementSignature, Set<String>> keyFn) {
        if (sigs.size() < 2) {
            return 0.0;
        }
        int pairs = 0;
        double sum = 0;
        for (int i = 0; i < sigs.size(); i++) {
            for (int j = i + 1; j < sigs.size(); j++) {
                Set<String> a = keyFn.apply(sigs.get(i));
                Set<String> b = keyFn.apply(sigs.get(j));
                sum += jaccard(a, b);
                pairs++;
            }
        }
        return pairs == 0 ? 0.0 : sum / pairs;
    }

    private static double jaccard(Set<String> a, Set<String> b) {
        if (a == null) a = Set.of();
        if (b == null) b = Set.of();
        if (a.isEmpty() && b.isEmpty()) {
            return 0.0;
        }
        Set<String> intersect = new LinkedHashSet<>(a);
        intersect.retainAll(b);
        Set<String> union = new LinkedHashSet<>(a);
        union.addAll(b);
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
        // childTagCounts 进 structureKey：区分"子结构一致"与"子结构各异"
        if (sig.childTagCounts() != null && !sig.childTagCounts().isEmpty()) {
            List<String> tags = new ArrayList<>(sig.childTagCounts().keySet());
            tags.sort(String::compareTo);
            for (String t : tags) {
                keys.add("child:" + t.toLowerCase(Locale.ROOT) + ":" + sig.childTagCounts().get(t));
            }
        }
        return keys;
    }

    private static ListItemRule buildRule(DominantChild dominant) {
        ElementSignature sample = dominant.signatures().get(0);
        String tag = sample.tagName() == null ? "div" : sample.tagName().toLowerCase(Locale.ROOT);
        String cls = firstClass(sample.className());
        if (!cls.isEmpty()) {
            return new ListItemRule(tag + "." + cls, SelectorType.CSS);
        }
        return new ListItemRule(tag, SelectorType.CSS);
    }

    private static String firstClass(String className) {
        if (className == null || className.isBlank()) {
            return "";
        }
        return className.trim().split("\\s+")[0];
    }

    private static String tagAndClass(String tag, String cls) {
        if (tag == null) {
            return "";
        }
        String first = firstClass(cls);
        return first.isEmpty() ? tag : tag + "." + first;
    }

    /** 多数派子元素分组结果。 */
    private record DominantChild(String kindKey, List<ElementSignature> signatures) {
        int matchCount() {
            return signatures.size();
        }
    }

    /** 内部评分结果，{@code depth} 1..N 表示祖先层深度。 */
    private record Scored(int depth, ListItemRule rule, int matchCount, double score,
                          List<ScoreComponent> components) {}
}
