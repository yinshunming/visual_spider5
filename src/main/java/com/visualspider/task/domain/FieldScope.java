package com.visualspider.task.domain;

/**
 * 字段作用域（M5 spec §D2）。
 *
 * <ul>
 *   <li>{@link #LIST}：列表项 DOM 上下文抽取；executor 对每个 list-item 解析</li>
 *   <li>{@link #CONTENT}：内容页 DOM 上下文抽取；executor navigate 内容页后解析</li>
 * </ul>
 */
public enum FieldScope {
    /** 列表项 DOM 上下文抽取；per-item 解析。 */
    LIST,
    /** 内容页 DOM 上下文抽取；内容页 navigate 后解析。 */
    CONTENT
}
