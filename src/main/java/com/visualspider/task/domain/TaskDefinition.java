package com.visualspider.task.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * 任务定义。
 *
 * <p><b>M1 校验规则</b>（spec §D4）：
 * <ul>
 *   <li>{@code schemaVersion == 1}</li>
 *   <li>{@code mode ∈ {SinglePage, List}}</li>
 *   <li>{@code startUrl} 为合法 http(s) URL 且 host 非空</li>
 *   <li>{@code viewport == 1280x720}（其它值 M1 拒绝并提示 M2 启用）</li>
 *   <li>{@code fields} 列表字段名非空且唯一</li>
 * </ul>
 *
 * <p><b>M3 扩展</b>（spec §D6）：新增 {@code waitPolicy}（{@link WaitPolicy}，
 * 可空 → 默认 {@code WaitPolicy(0)}）；{@link FieldDefinition} 加 {@code selectorType}
 * （{@link SelectorType}，可空 → 默认 CSS）；{@code schemaVersion} 保持 1（加字段非破坏性）。
 *
 * <p><b>M4 扩展</b>（spec §D1）：新增 {@code limits}（{@link Limits}，可空 → 默认
 * {@link Limits#globalDefault()}）、{@code listItemRule}（{@link ListItemRule}，LIST 模式必填）、
 * {@code uniqueKey}（{@link List<UniqueKeyField>}，可空 → 默认空 list）；{@code schemaVersion}
 * bump 到 2。{@code TaskSchemaUpgrader} 启动 hook 静默将 V1 SP 任务迁移到 V2、V1 LIST 任务
 * 缺 {@code listItemRule} 一律降 DRAFT。
 *
 * <p>{@code @JsonIgnoreProperties(ignoreUnknown = true)} 保证 schema 升级时旧 reader 仍可读新快照。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TaskDefinition(
        int schemaVersion,
        TaskMode mode,
        String startUrl,
        Viewport viewport,
        WaitPolicy waitPolicy,
        Limits limits,
        ListItemRule listItemRule,
        List<UniqueKeyField> uniqueKey,
        List<FieldDefinition> fields) {

    public TaskDefinition {
        if (fields == null) {
            fields = List.of();
        }
        // 反序列化旧快照时 waitPolicy 缺失 → 默认 WaitPolicy(0)；M4 新字段同样给默认值。
        if (waitPolicy == null) {
            waitPolicy = new WaitPolicy(0);
        }
        if (limits == null) {
            limits = Limits.globalDefault();
        }
        if (uniqueKey == null) {
            uniqueKey = List.of();
        }
    }

    /**
     * M3 / M2 兼容构造器（6 位置参数对应 V1 字段）。
     *
     * <p>新增的三个 V2 字段（{@code limits} / {@code listItemRule} / {@code uniqueKey}）
     * 全部传 {@code null}，由紧凑构造器填默认值。这允许 M3 测试、m2/m3 调用点继续使用旧
     * 位置参数签名，运行时 upgrader 会把 snapshot 升到 V2。
     */
    public TaskDefinition(int schemaVersion,
                          TaskMode mode,
                          String startUrl,
                          Viewport viewport,
                          WaitPolicy waitPolicy,
                          List<FieldDefinition> fields) {
        this(schemaVersion, mode, startUrl, viewport, waitPolicy, null, null, null, fields);
    }
}
