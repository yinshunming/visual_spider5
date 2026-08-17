package com.visualspider.task.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * TaskDefinition / FieldDefinition JSON 兼容性测试（M3 spec §D6 关键不变量）：
 * <ul>
 *   <li>旧 M2 快照（无 {@code waitPolicy}、无 {@code selectorType}）反序列化成功；
 *   <li>{@code waitPolicy == null} → 默认填充 {@code WaitPolicy(0)}；
 *   <li>{@code selectorType == null} → 默认填充 {@code SelectorType.CSS}。</li>
 * </ul>
 */
class TaskDefinitionJsonCompatTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();

    @Test
    @DisplayName("M2 旧快照（无 waitPolicy/selectorType）反序列化成功")
    void deserializeM2Snapshot() throws Exception {
        // 这是 M2 时代的 JSON 形态：没有 waitPolicy / selectorType。
        String json = """
                {
                  "schemaVersion": 1,
                  "mode": "SINGLE_PAGE",
                  "startUrl": "https://example.com",
                  "viewport": {"width": 1280, "height": 720},
                  "fields": [
                    {"name": "title", "source": "VISIBLE_TEXT", "selector": "h1",
                     "resultType": "TEXT", "trim": "TRIM", "required": true}
                  ]
                }
                """;
        TaskDefinition def = mapper.readValue(json, TaskDefinition.class);
        assertThat(def.schemaVersion()).isEqualTo(1);
        assertThat(def.waitPolicy()).isNotNull();
        assertThat(def.waitPolicy().extraWaitSeconds()).isZero();
        assertThat(def.fields()).hasSize(1);
        assertThat(def.fields().get(0).selectorType()).isEqualTo(SelectorType.CSS);
    }

    @Test
    @DisplayName("M3 快照（含 waitPolicy/selectorType）正确反序列化")
    void deserializeM3Snapshot() throws Exception {
        String json = """
                {
                  "schemaVersion": 1,
                  "mode": "SINGLE_PAGE",
                  "startUrl": "https://example.com",
                  "viewport": {"width": 1280, "height": 720},
                  "waitPolicy": {"extraWaitSeconds": 3},
                  "fields": [
                    {"name": "title", "source": "VISIBLE_TEXT", "selector": "//h1",
                     "selectorType": "XPATH",
                     "resultType": "TEXT", "trim": "TRIM", "required": true}
                  ]
                }
                """;
        TaskDefinition def = mapper.readValue(json, TaskDefinition.class);
        assertThat(def.waitPolicy().extraWaitSeconds()).isEqualTo(3);
        assertThat(def.fields().get(0).selectorType()).isEqualTo(SelectorType.XPATH);
        assertThat(def.fields().get(0).selector()).isEqualTo("//h1");
    }

    @Test
    @DisplayName("@JsonIgnoreProperties 保证未知字段不破坏解析（前向兼容留位 M4+）")
    void ignoresUnknownFields() throws Exception {
        String json = """
                {
                  "schemaVersion": 1,
                  "mode": "SINGLE_PAGE",
                  "startUrl": "https://example.com",
                  "viewport": {"width": 1280, "height": 720},
                  "futureField": "reserved-for-M4",
                  "fields": []
                }
                """;
        TaskDefinition def = mapper.readValue(json, TaskDefinition.class);
        assertThat(def.startUrl()).isEqualTo("https://example.com");
    }
}