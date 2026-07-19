package com.visualspider.task.domain;

/**
 * 字段来源类型。M1 仅 {@code TEXT} 落地，其它类型 M2+ 扩展。
 */
public enum FieldSource {
    TEXT,
    ATTRIBUTE,
    HTML,
    STRUCTURED
}
