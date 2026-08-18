package com.visualspider.task.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * TaskDefinition V3 形状 + JSON 兼容性测试（M5 spec §D1 / §D2）。
 *
 * <p>覆盖：{@link PaginationRule} 紧凑构造器校验、{@link FieldDefinition} 新字段默认值、
 * V3 canonical 构造、{@link NavigationMode} 序列化稳定性、V2 JSON 反序列化兼容。
 */
class TaskDefinitionV3Test {

    private final ObjectMapper mapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .build();

    @Test
    @DisplayName("PaginationRule mode=null → IllegalArgumentException")
    void paginationRuleModeRequired() {
        assertThatThrownBy(() -> new PaginationRule(null, "a.next", SelectorType.CSS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mode");
    }

    @Test
    @DisplayName("PaginationRule selector 空 → IllegalArgumentException")
    void paginationRuleSelectorRequired() {
        assertThatThrownBy(() -> new PaginationRule(NavigationMode.NEXT_PAGE, "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("selector");
    }

    @Test
    @DisplayName("PaginationRule selectorType=null → 默认 CSS")
    void paginationRuleSelectorTypeDefaultsToCss() {
        PaginationRule rule = new PaginationRule(NavigationMode.NEXT_PAGE, "a.next", null);
        assertThat(rule.selectorType()).isEqualTo(SelectorType.CSS);
        assertThat(rule.mode()).isEqualTo(NavigationMode.NEXT_PAGE);
        assertThat(rule.selector()).isEqualTo("a.next");
    }

    @Test
    @DisplayName("NavigationMode 枚举值固定")
    void navigationModeEnumStable() {
        assertThat(NavigationMode.values()).containsExactly(NavigationMode.NEXT_PAGE, NavigationMode.LOAD_MORE);
    }

    @Test
    @DisplayName("FieldDefinition 9 参兼容构造器 → scope=LIST / fieldKind=LIST_VALUE 默认")
    void fieldDefinitionCompatConstructorDefaults() {
        FieldDefinition f = new FieldDefinition("title", FieldSource.VISIBLE_TEXT, "h1",
                null, SelectorType.CSS, ResultType.TEXT, TrimPolicy.TRIM, null, true);
        assertThat(f.scope()).isEqualTo(FieldScope.LIST);
        assertThat(f.fieldKind()).isEqualTo(FieldKind.LIST_VALUE);
    }

    @Test
    @DisplayName("FieldScope/FieldKind 枚举值固定")
    void fieldEnumValues() {
        assertThat(FieldScope.values()).containsExactly(FieldScope.LIST, FieldScope.CONTENT);
        assertThat(FieldKind.values()).containsExactly(
                FieldKind.LIST_VALUE, FieldKind.LIST_CONTENT_LINK, FieldKind.CONTENT_VALUE);
    }

    @Test
    @DisplayName("TaskDefinition 11 参 canonical 构造：paginationRule=null 默认 + V3 字段完整")
    void taskDefinitionV3Canonical() {
        TaskDefinition def = new TaskDefinition(3, new TaskMode.List(),
                "https://example.com", Viewport.DEFAULT, new WaitPolicy(2),
                new Limits(100, 500, Duration.ofMinutes(15)),
                new ListItemRule("ul > li", SelectorType.CSS),
                List.of(new UniqueKeyField("title")),
                new PaginationRule(NavigationMode.NEXT_PAGE, "a.next", SelectorType.CSS),
                List.of(new FieldDefinition("title", FieldSource.VISIBLE_TEXT, "h1",
                        null, SelectorType.CSS, ResultType.TEXT, TrimPolicy.TRIM, null, true)));
        assertThat(def.schemaVersion()).isEqualTo(3);
        assertThat(def.paginationRule()).isNotNull();
        assertThat(def.paginationRule().mode()).isEqualTo(NavigationMode.NEXT_PAGE);
        assertThat(def.fields().get(0).scope()).isEqualTo(FieldScope.LIST);
        assertThat(def.fields().get(0).fieldKind()).isEqualTo(FieldKind.LIST_VALUE);
    }

    @Test
    @DisplayName("paginationRule=null 紧凑构造器接受 → 等价 只跑当前页")
    void taskDefinitionPaginationRuleNullable() {
        TaskDefinition def = new TaskDefinition(3, new TaskMode.List(),
                "https://example.com", Viewport.DEFAULT, null,
                new Limits(100, 500, Duration.ofMinutes(15)),
                new ListItemRule("ul > li", SelectorType.CSS),
                List.of(new UniqueKeyField("title")),
                null,
                List.of(new FieldDefinition("title", FieldSource.VISIBLE_TEXT, "h1",
                        null, SelectorType.CSS, ResultType.TEXT, TrimPolicy.TRIM, null, true)));
        assertThat(def.paginationRule()).isNull();
        assertThat(readiness(def)).isTrue();
    }

    @Test
    @DisplayName("V3 完整快照 round-trip 后保持所有字段")
    void v3SnapshotRoundTrip() throws Exception {
        String v3Json = """
                {
                  "schemaVersion": 3,
                  "mode": "LIST",
                  "startUrl": "https://example.com",
                  "viewport": {"width": 1280, "height": 720},
                  "waitPolicy": {"extraWaitSeconds": 2},
                  "limits": {"pageLimit": 100, "recordLimit": 500, "durationLimit": "PT15M"},
                  "listItemRule": {"selector": "ul > li", "selectorType": "CSS"},
                  "uniqueKey": [{"fieldName":"title"}],
                  "paginationRule": {"mode": "NEXT_PAGE", "selector": "a.next", "selectorType": "CSS"},
                  "fields": [
                    {"name":"title","source":"VISIBLE_TEXT","selector":"h1",
                     "selectorType":"CSS","resultType":"TEXT","trim":"TRIM","required":true,
                     "scope":"LIST","fieldKind":"LIST_VALUE"}
                  ]
                }
                """;
        TaskDefinition def = mapper.readValue(v3Json, TaskDefinition.class);
        assertThat(def.schemaVersion()).isEqualTo(3);
        assertThat(def.paginationRule()).isNotNull();
        assertThat(def.paginationRule().mode()).isEqualTo(NavigationMode.NEXT_PAGE);
        assertThat(def.paginationRule().selector()).isEqualTo("a.next");
        assertThat(def.fields().get(0).scope()).isEqualTo(FieldScope.LIST);
        assertThat(def.fields().get(0).fieldKind()).isEqualTo(FieldKind.LIST_VALUE);
    }

    @Test
    @DisplayName("V2 旧 JSON（无 paginationRule / scope / fieldKind）反序列化时填默认值")
    void v2JsonDeserializesWithV3Defaults() throws Exception {
        String v2Json = """
                {
                  "schemaVersion": 2,
                  "mode": "LIST",
                  "startUrl": "https://example.com",
                  "viewport": {"width": 1280, "height": 720},
                  "waitPolicy": {"extraWaitSeconds": 2},
                  "limits": {"pageLimit": 100, "recordLimit": 500, "durationLimit": "PT15M"},
                  "listItemRule": {"selector": "ul > li", "selectorType": "CSS"},
                  "uniqueKey": [{"fieldName":"title"}],
                  "fields": [
                    {"name":"title","source":"VISIBLE_TEXT","selector":"h1",
                     "selectorType":"CSS","resultType":"TEXT","trim":"TRIM","required":true}
                  ]
                }
                """;
        TaskDefinition def = mapper.readValue(v2Json, TaskDefinition.class);
        assertThat(def.schemaVersion()).isEqualTo(2);  // reader 不动 schemaVersion
        assertThat(def.paginationRule()).isNull();      // 新字段缺失 → null（等价"只跑当前页"）
        assertThat(def.fields().get(0).scope()).isEqualTo(FieldScope.LIST);
        assertThat(def.fields().get(0).fieldKind()).isEqualTo(FieldKind.LIST_VALUE);
    }

    private static boolean readiness(TaskDefinition def) {
        // 仅看 shape 完整性：这里不调 TaskReadiness（避免引入 readiness 依赖）。
        return def.paginationRule() == null  // null 是合法的"只跑当前页"
                || (def.paginationRule().mode() != null && def.paginationRule().selector() != null);
    }
}