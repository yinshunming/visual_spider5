package com.visualspider.task.domain;

/**
 * 字段来源类型（M2-3 #19）。
 *
 * <ul>
 *   <li>{@link #VISIBLE_TEXT}：element.textContent</li>
 *   <li>{@link #ATTRIBUTE}：element.getAttribute(attributeName)</li>
 *   <li>{@link #LINK_URL}：{@code <a>.getAttribute("href")} 绝对化</li>
 *   <li>{@link #IMAGE_URL}：{@code <img>.getAttribute("src")} 绝对化</li>
 *   <li>{@link #PAGE_URL}：当前 page.url()（selector/attributeName 必填为 null）</li>
 * </ul>
 */
public enum FieldSource {
    VISIBLE_TEXT,
    ATTRIBUTE,
    LINK_URL,
    IMAGE_URL,
    PAGE_URL
}
