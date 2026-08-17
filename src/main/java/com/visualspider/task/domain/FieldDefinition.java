package com.visualspider.task.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 字段定义。M1 暂不校验 regex / selector 语法（仅记账）；
 * 完整字段校验（regex、类型转换、字段缺失诊断）M2 启用。
 *
 * <p>M3 扩展（spec §D6）：新增 {@code selectorType}（{@link SelectorType}），
 * 旧快照反序列化时若为 {@code null} → 视为 {@link SelectorType#CSS}，与 M2 行为一致。
 *
 * <p>{@code @JsonIgnoreProperties(ignoreUnknown = true)} 保证 schema 升级时旧 reader 仍可读新快照。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FieldDefinition(
        String name,
        FieldSource source,
        String selector,
        String attributeName,
        SelectorType selectorType,
        ResultType resultType,
        TrimPolicy trim,
        String regex,
        boolean required) {

    public FieldDefinition {
        // 反序列化旧快照时若 selectorType 字段缺失 → 默认 CSS。
        if (selectorType == null) {
            selectorType = SelectorType.CSS;
        }
    }
}