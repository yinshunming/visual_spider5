package com.visualspider.task.domain;

/**
 * 字段选择器类型（M3 spec §D6）。
 *
 * <ul>
 *   <li>{@link #CSS}：{@code document.querySelectorAll} 语法</li>
 *   <li>{@link #XPATH}：{@code document.evaluate} 语法</li>
 * </ul>
 *
 * <p>由 {@code FieldDefinition.selectorType} 持有（可空 → 默认 CSS）；
 * {@code extraction} 模块导入本枚举，反向依赖方向保持 {@code task → extraction} 单向。
 */
public enum SelectorType {
    CSS,
    XPATH
}