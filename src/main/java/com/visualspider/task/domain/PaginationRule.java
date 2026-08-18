package com.visualspider.task.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 翻页规则（M5 spec §D1）。
 *
 * <p>{@link TaskDefinition#paginationRule()} 可空：{@code null} 等价"只跑当前页"
 * （与 V2 LIST 任务行为一致，由 {@code MultiPageRunExecutor} 检测到 null 时退化）。
 *
 * <p>{@code selector} 必填（翻页 / 加载更多元素的 CSS/XPath）；
 * {@code selectorType} 可空默认 {@link SelectorType#CSS}，与 {@link ListItemRule} 一致。
 * 实匹配校验（元素存在且可见）由 M5 readiness 承担，构造器只做"非空"兜底。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PaginationRule(
        NavigationMode mode,
        String selector,
        SelectorType selectorType) {

    public PaginationRule {
        if (mode == null) {
            throw new IllegalArgumentException("paginationRule.mode 不能为空");
        }
        if (selector == null || selector.isBlank()) {
            throw new IllegalArgumentException("paginationRule.selector 不能为空");
        }
        if (selectorType == null) {
            selectorType = SelectorType.CSS;
        }
    }

    public PaginationRule(NavigationMode mode, String selector) {
        this(mode, selector, SelectorType.CSS);
    }
}
