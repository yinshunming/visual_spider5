package com.visualspider.task.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 字段定义。M1 暂不校验 regex / selector 语法（仅记账）；
 * 完整字段校验（regex、类型转换、字段缺失诊断）M2 启用。
 *
 * <p>{@code @JsonIgnoreProperties(ignoreUnknown = true)} 保证 schema 升级时旧 reader 仍可读新快照。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FieldDefinition(
        String name,
        FieldSource source,
        String selector,
        String attributeName,
        ResultType resultType,
        TrimPolicy trim,
        String regex,
        boolean required) {
}
