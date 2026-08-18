package com.visualspider.task.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 字段定义。M1 暂不校验 regex / selector 语法（仅记账）；
 * 完整字段校验（regex、类型转换、字段缺失诊断）M2 启用。
 *
 * <p>M3 扩展（spec §D6）：新增 {@code selectorType}（{@link SelectorType}），
 * 旧快照反序列化时若为 {@code null} -> 视为 {@link SelectorType#CSS}，与 M2 行为一致。
 *
 * <p>M5 扩展（spec §D2）：新增 {@code scope}（{@link FieldScope}，可空 -> 默认
 * {@link FieldScope#LIST}）与 {@code fieldKind}（{@link FieldKind}，可空 -> 默认
 * {@link FieldKind#LIST_VALUE}）。V2 JSON 反序列化时字段缺失即走默认值，
 * 与 V2 行为一致；scope / fieldKind 组合的强校验（如
 * {@code LIST_CONTENT_LINK} 必须 {@code scope=LIST}）由 M5 readiness 承担。
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
        boolean required,
        FieldScope scope,
        FieldKind fieldKind) {

    public FieldDefinition {
        // 反序列化旧快照时若 selectorType / scope / fieldKind 字段缺失 -> 默认值。
        if (selectorType == null) {
            selectorType = SelectorType.CSS;
        }
        if (scope == null) {
            scope = FieldScope.LIST;
        }
        if (fieldKind == null) {
            fieldKind = FieldKind.LIST_VALUE;
        }
    }

    /**
     * M4 兼容构造器（9 位置参数对应 V2 字段）。
     *
     * <p>V3 新字段 {@code scope} / {@code fieldKind} 传 {@code null}，
     * 由紧凑构造器填默认值（{@code LIST} / {@code LIST_VALUE}）。
     */
    public FieldDefinition(String name, FieldSource source, String selector,
                           String attributeName, SelectorType selectorType,
                           ResultType resultType, TrimPolicy trim, String regex, boolean required) {
        this(name, source, selector, attributeName, selectorType, resultType, trim, regex,
                required, null, null);
    }
}
