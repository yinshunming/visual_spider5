package com.visualspider.task.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 唯一键字段（M4 spec §D1 / §D5）。
 *
 * <p>采集人员选择若干字段作为运行内去重键；
 * {@code UniqueKeyHasher} 用 stable canonical JSON 序列化为 SHA-256，
 * 任一字段值为空时该 record 不参与判重。
 *
 * <p>{@code fieldName} 非空；与 {@link FieldDefinition#name()} 对应。
 * {@code TaskReadiness} 校验必须包含于 {@code TaskDefinition.fields}。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UniqueKeyField(String fieldName) {

    public UniqueKeyField {
        if (fieldName == null || fieldName.isBlank()) {
            throw new IllegalArgumentException("UniqueKeyField.fieldName 不能为空");
        }
    }
}
