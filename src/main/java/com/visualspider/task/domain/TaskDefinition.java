package com.visualspider.task.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * 任务定义。M1 校验规则（spec §D4）：
 * <ul>
 *   <li>{@code schemaVersion == 1}</li>
 *   <li>{@code mode ∈ {SinglePage, List}}</li>
 *   <li>{@code startUrl} 为合法 http(s) URL 且 host 非空</li>
 *   <li>{@code viewport == 1280x720}（其它值 M1 拒绝并提示 M2 启用）</li>
 *   <li>{@code fields} 列表字段名非空且唯一</li>
 * </ul>
 *
 * <p>M3 扩展（spec §D6）：
 * <ul>
 *   <li>新增 {@code waitPolicy}（{@link WaitPolicy}，可空 → 默认 {@code WaitPolicy(0)}）</li>
 *   <li>{@link FieldDefinition} 新增 {@code selectorType}（{@link SelectorType}，可空 → 默认 CSS）</li>
 *   <li>{@code schemaVersion} 保持 1（加字段非破坏性，旧 reader 可读新快照）</li>
 * </ul>
 *
 * <p>{@code @JsonIgnoreProperties(ignoreUnknown = true)} 保证历史快照可解释。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TaskDefinition(
        int schemaVersion,
        TaskMode mode,
        String startUrl,
        Viewport viewport,
        WaitPolicy waitPolicy,
        List<FieldDefinition> fields) {

    public TaskDefinition {
        if (fields == null) {
            fields = List.of();
        }
        // 反序列化旧快照（M2 保存的）waitPolicy 字段缺失 → 填默认值 WaitPolicy(0)
        if (waitPolicy == null) {
            waitPolicy = new WaitPolicy(0);
        }
    }
}