package com.visualspider.task.domain;

/**
 * 字段用途细分（M5 spec §D2）。
 *
 * <ul>
 *   <li>{@link #LIST_VALUE}：普通字段值，{@code scope=LIST} 时从 list-item 抽</li>
 *   <li>{@link #LIST_CONTENT_LINK}：内容页入口 URL；{@code scope} 必须 LIST；
 *       uniqueKey 不能选（M5 readiness {@code UNIQUE_KEY_ON_LINK_FIELD}）</li>
 *   <li>{@link #CONTENT_VALUE}：内容页字段值；{@code scope} 必须 CONTENT；
 *       与 LIST 字段同名时按 name 合并到同一 record</li>
 * </ul>
 */
public enum FieldKind {
    /** 普通字段值，scope=LIST 时从 list-item 抽；scope=CONTENT 时从内容页抽。 */
    LIST_VALUE,
    /** 内容页入口 URL；scope 必须 LIST；uniqueKey 不能选；preview 与 run 把它当跳转源。 */
    LIST_CONTENT_LINK,
    /** 内容页字段值；scope 必须 CONTENT；与 LIST 字段同名时按 name 合并。 */
    CONTENT_VALUE
}
