package com.visualspider.task.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 列表项规则（M4 spec §D1 / §D3）。
 *
 * <p>定义 list 模式下"列表项容器"的定位规则；与
 * {@link FieldDefinition} 共用 {@link SelectorType} 模型，
 * 由 {@code extraction} 模块 import 同一枚举；反向依赖方向
 * 保持 {@code task → extraction} 单向（实际是 {@code extraction → task.domain}）。
 *
 * <p>{@code selector} 非空；{@code selectorType} 可空默认 {@link SelectorType#CSS}。
 * 实匹配校验（{@code >= 2}）由 {@code task.TaskReadiness.validateForRun}
 * 在获取 preview 实数据后判断（spec §D10），构造器只做"非空"兜底。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ListItemRule(String selector, SelectorType selectorType) {

    public ListItemRule {
        if (selector == null || selector.isBlank()) {
            throw new IllegalArgumentException("listItemRule.selector 不能为空");
        }
        if (selectorType == null) {
            selectorType = SelectorType.CSS;
        }
    }

    public ListItemRule(String selector) {
        this(selector, SelectorType.CSS);
    }
}
