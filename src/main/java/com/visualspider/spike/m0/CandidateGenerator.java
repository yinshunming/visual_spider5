package com.visualspider.spike.m0;

import java.util.ArrayList;
import java.util.List;

/**
 * 候选元素定位规则生成器：基于元素的 tagName/id/class 生成候选 CSS Selector 与 XPath。
 * 按 specificity 从高到低排列（id > class > tag）。纯逻辑，不依赖 Playwright。
 *
 * <p>M0 spike：仅生成简单候选（id/class/tag 级别），不做路径推导。M2 产品化时增强。
 */
public final class CandidateGenerator {

    private CandidateGenerator() {}

    public static List<String> css(String tagName, String id, String className) {
        List<String> candidates = new ArrayList<>();
        if (id != null && !id.isEmpty()) {
            candidates.add(tagName + "#" + id);
        }
        String firstClass = firstClass(className);
        if (firstClass != null) {
            candidates.add(tagName + "." + firstClass);
        }
        candidates.add(tagName);
        return candidates;
    }

    public static List<String> xpath(String tagName, String id, String className) {
        List<String> candidates = new ArrayList<>();
        if (id != null && !id.isEmpty()) {
            candidates.add("//" + tagName + "[@id='" + id + "']");
        }
        String firstClass = firstClass(className);
        if (firstClass != null) {
            candidates.add("//" + tagName + "[contains(@class,'" + firstClass + "')]");
        }
        candidates.add("//" + tagName);
        return candidates;
    }

    private static String firstClass(String className) {
        if (className == null || className.isBlank()) {
            return null;
        }
        return className.trim().split("\\s+")[0];
    }
}
