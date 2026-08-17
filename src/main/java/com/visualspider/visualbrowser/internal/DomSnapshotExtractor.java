package com.visualspider.visualbrowser.internal;

import com.visualspider.extraction.spi.DomSnapshot;
import com.visualspider.extraction.spi.DomSnapshot.AncestorSnapshot;
import com.visualspider.extraction.spi.DomSnapshot.ElementSignature;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code Playwright evaluate} 返回的原始 {@link Map} 转为 {@link DomSnapshot}（M4-2 #32）。
 *
 * <p>JS 端契约（{@code captureDomSnapshot} 注入到 {@code page().evaluate} 的箭头函数返回结构）：
 * <pre>{@code
 * {
 *   tagName: string,
 *   className: string,
 *   attributes: { [k: string]: string },
 *   innerTextSnippet: string,
 *   ancestors: [
 *     { tagName, className, attributes, innerTextSnippet,
 *       childCount: number,
 *       childSignatures: [
 *         { tagName, className, attributeKeys: string[],
 *           textTokens: string[], childTagCounts: { [tag: string]: number } }
 *       ]
 *     },
 *     ...   // ancestors[0] = clicked 自身；index i = 第 i 层祖先
 *   ]
 * }
 * }</pre>
 *
 * <p>本类做防御性类型转换（JS 数字 → Integer，缺失字段 → 空集合），不抛异常；调用方负责校验
 * ancestors 非空。
 */
public final class DomSnapshotExtractor {

    private DomSnapshotExtractor() {}

    public static DomSnapshot extract(Map<String, Object> raw) {
        if (raw == null) {
            throw new IllegalArgumentException("raw snapshot 为空");
        }
        String tagName = asString(raw.get("tagName"));
        String className = asString(raw.get("className"));
        Map<String, String> attributes = asStringMap(raw.get("attributes"));
        String innerTextSnippet = asString(raw.get("innerTextSnippet"));
        List<Map<String, Object>> ancestorsRaw = asListOfMaps(raw.get("ancestors"));
        if (ancestorsRaw == null || ancestorsRaw.isEmpty()) {
            throw new IllegalArgumentException("ancestors 必须 ≥ 1（含 clicked 自身）");
        }
        List<AncestorSnapshot> ancestors = new ArrayList<>(ancestorsRaw.size());
        for (Map<String, Object> a : ancestorsRaw) {
            ancestors.add(toAncestor(a));
        }
        return new DomSnapshot(tagName, className, attributes, innerTextSnippet, ancestors);
    }

    private static AncestorSnapshot toAncestor(Map<String, Object> raw) {
        String tagName = asString(raw.get("tagName"));
        String className = asString(raw.get("className"));
        Map<String, String> attributes = asStringMap(raw.get("attributes"));
        String innerTextSnippet = asString(raw.get("innerTextSnippet"));
        int childCount = asInt(raw.get("childCount"));
        List<ElementSignature> childSignatures = toSignatures(raw.get("childSignatures"));
        return new AncestorSnapshot(tagName, className, attributes, innerTextSnippet,
                childCount, childSignatures);
    }

    private static List<ElementSignature> toSignatures(Object sigsRaw) {
        List<Map<String, Object>> list = asListOfMaps(sigsRaw);
        if (list == null) {
            return List.of();
        }
        List<ElementSignature> out = new ArrayList<>(list.size());
        for (Map<String, Object> m : list) {
            String tagName = asString(m.get("tagName"));
            String className = asString(m.get("className"));
            Set<String> attributeKeys = asStringSet(m.get("attributeKeys"));
            Set<String> textTokens = asStringSet(m.get("textTokens"));
            Map<String, Integer> childTagCounts = asStringIntMap(m.get("childTagCounts"));
            out.add(new ElementSignature(tagName, className, attributeKeys, textTokens, childTagCounts));
        }
        return out;
    }

    private static String asString(Object o) {
        return o == null ? null : o.toString();
    }

    private static int asInt(Object o) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        if (o instanceof String s) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> asStringMap(Object o) {
        if (o instanceof Map<?, ?> m) {
            Map<String, String> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.put(String.valueOf(e.getKey()), e.getValue() == null ? null : e.getValue().toString());
            }
            return out;
        }
        return Collections.emptyMap();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Integer> asStringIntMap(Object o) {
        if (o instanceof Map<?, ?> m) {
            Map<String, Integer> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                Object v = e.getValue();
                if (v instanceof Number n) {
                    out.put(String.valueOf(e.getKey()), n.intValue());
                }
            }
            return out;
        }
        return Collections.emptyMap();
    }

    @SuppressWarnings("unchecked")
    private static Set<String> asStringSet(Object o) {
        if (o instanceof List<?> list) {
            Set<String> out = new LinkedHashSet<>();
            for (Object item : list) {
                if (item != null) {
                    out.add(item.toString());
                }
            }
            return out;
        }
        return Collections.emptySet();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asListOfMaps(Object o) {
        if (o instanceof List<?> list) {
            List<Map<String, Object>> out = new ArrayList<>(list.size());
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    out.add((Map<String, Object>) m);
                }
            }
            return out;
        }
        return null;
    }
}