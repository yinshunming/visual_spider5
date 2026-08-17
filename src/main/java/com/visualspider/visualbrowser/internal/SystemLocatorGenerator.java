package com.visualspider.visualbrowser.internal;

import com.visualspider.visualbrowser.spi.AdvancedSelectorEditor;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 系统定位规则生成器（M2-2 #18）。
 *
 * <p>基于 {@link AdvancedSelectorEditor.ElementSnapshot} 元素静态特征生成稳定候选；
 * 按 specificity + stability 评分从高到低排序，第一个为推荐选择器。
 *
 * <p>稳定度评分：
 * <ul>
 *   <li>id 在文档中唯一 → 100</li>
 *   <li>组合 class 后唯一 → 80</li>
 *   <li>可由 {@code :nth-of-type(...)} 在父结构中区分 → 60</li>
 *   <li>仅 tag 或仅 :nth-child(n) → 40</li>
 * </ul>
 *
 * <p>每个候选同时返回 CSS + XPath 两套；M2 不使用文本内容作为选择器部分。
 */
public final class SystemLocatorGenerator {

    public record LocatorCandidate(
            String css,
            String xpath,
            int specificityScore,
            int stabilityScore,
            int matchCount
    ) {}

    public record GenerationContext(boolean idUnique, boolean classCombinationUnique) {}

    public List<LocatorCandidate> generate(AdvancedSelectorEditor.ElementSnapshot element,
                                           GenerationContext ctx) {
        if (element == null) {
            return List.of();
        }
        String tag = lower(element.tagName());
        String id = element.id();
        String className = element.className();
        List<LocatorCandidate> out = new ArrayList<>();

        if (isUsable(id) && ctx != null && ctx.idUnique()) {
            String css = tag + "#" + cssEscape(id);
            String xpath = "//" + tag + "[@id='" + escapeAttr(id) + "']";
            out.add(new LocatorCandidate(css, xpath, 100, 100, 1));
        }
        if (isUsable(className) && ctx != null && ctx.classCombinationUnique()) {
            String firstClass = firstClass(className);
            if (firstClass != null) {
                String css = tag + "." + cssEscape(firstClass);
                String xpath = "//" + tag + "[contains(concat(' ', normalize-space(@class), ' '), ' "
                        + escapeAttr(firstClass) + " ')]";
                out.add(new LocatorCandidate(css, xpath, 80, 80, 1));
            }
        }
        // nth-of-type（占位；M2 在 #18 范围内按 (60) 给出但不要求运行时测）
        out.add(new LocatorCandidate(
                tag + ":nth-of-type(1)",
                "(//" + tag + ")[1]",
                60, 60, ctx == null ? -1 : ctx.idUnique() ? 1 : -1));
        // tag-only 占位
        out.add(new LocatorCandidate(tag, "//" + tag, 40, 40, ctx == null ? -1 : -1));

        return out;
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    private static boolean isUsable(String s) {
        return s != null && !s.isBlank();
    }

    private static String firstClass(String className) {
        if (className == null) {
            return null;
        }
        for (String part : className.trim().split("\\s+")) {
            if (!part.isEmpty()) {
                return part;
            }
        }
        return null;
    }

    private static String cssEscape(String value) {
        // 简化：去除空白与逗号，避免大量边界场景；外部不再使用此选择器编译为 selector 列表
        return value.replaceAll("[\\s,>+~*]", "\\\\$0");
    }

    private static String escapeAttr(String value) {
        return value.replace("'", "&apos;");
    }
}
