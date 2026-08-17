package com.visualspider.task.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * TaskDefinition V2 形状 + JSON 兼容性测试（M4 spec §D1）。
 */
class TaskDefinitionV2Test {

    private final ObjectMapper mapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .build();

    @Test
    @DisplayName("M3 旧 6-arg 构造器仍可用（M4 兼容；新字段由紧凑构造器填默认）")
    void legacySixArgConstructor() {
        TaskDefinition def = new TaskDefinition(2, new TaskMode.SinglePage(),
                "https://example.com", Viewport.DEFAULT, null,
                List.of());
        assertThat(def.schemaVersion()).isEqualTo(2);
        assertThat(def.limits()).isNotNull();
        assertThat(def.limits().pageLimit()).isEqualTo(200);
        assertThat(def.uniqueKey()).isEmpty();
        assertThat(def.listItemRule()).isNull();
        assertThat(def.waitPolicy()).isNotNull();
        assertThat(def.waitPolicy().extraWaitSeconds()).isZero();
    }

    @Test
    @DisplayName("V2 全位置参数构造器按字段存储")
    void fullV2Constructor() {
        TaskDefinition def = new TaskDefinition(2, new TaskMode.List(),
                "https://example.com", Viewport.DEFAULT, new WaitPolicy(2),
                new Limits(100, 500, Duration.ofMinutes(15)),
                new ListItemRule("ul > li", SelectorType.CSS),
                List.of(new UniqueKeyField("title")),
                List.of());
        assertThat(def.schemaVersion()).isEqualTo(2);
        assertThat(def.waitPolicy().extraWaitSeconds()).isEqualTo(2);
        assertThat(def.limits().pageLimit()).isEqualTo(100);
        assertThat(def.listItemRule().selector()).isEqualTo("ul > li");
        assertThat(def.uniqueKey()).hasSize(1);
        assertThat(def.uniqueKey().get(0).fieldName()).isEqualTo("title");
    }

    @Test
    @DisplayName("limits=null → 紧凑构造器填 globalDefault")
    void limitsDefaultsToGlobal() {
        TaskDefinition def = new TaskDefinition(2, new TaskMode.SinglePage(),
                "https://example.com", Viewport.DEFAULT, null, null, null, null,
                List.of());
        assertThat(def.limits()).isNotNull();
        assertThat(def.limits()).isEqualTo(Limits.globalDefault());
    }

    @Test
    @DisplayName("uniqueKey=null → 紧凑构造器填空 list")
    void uniqueKeyDefaultsToEmptyList() {
        TaskDefinition def = new TaskDefinition(2, new TaskMode.SinglePage(),
                "https://example.com", Viewport.DEFAULT, null, null, null, null,
                List.of());
        assertThat(def.uniqueKey()).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("V1 旧快照（无 limits / listItemRule / uniqueKey）反序列化为 V2 record 时默认值填充")
    void v1SnapshotDeserializedWithDefaults() throws Exception {
        String v1Json = """
                {
                  "schemaVersion": 1,
                  "mode": "SINGLE_PAGE",
                  "startUrl": "https://example.com",
                  "viewport": {"width": 1280, "height": 720},
                  "waitPolicy": {"extraWaitSeconds": 3},
                  "fields": [
                    {"name":"title","source":"VISIBLE_TEXT","selector":"h1",
                     "resultType":"TEXT","trim":"TRIM","required":true}
                  ]
                }
                """;
        TaskDefinition def = mapper.readValue(v1Json, TaskDefinition.class);
        assertThat(def.schemaVersion()).isEqualTo(1);  // reader 不动 schemaVersion
        assertThat(def.limits()).isEqualTo(Limits.globalDefault());  // V2 reader 自动补默认
        assertThat(def.listItemRule()).isNull();
        assertThat(def.uniqueKey()).isEmpty();
        assertThat(def.waitPolicy().extraWaitSeconds()).isEqualTo(3);
    }

    @Test
    @DisplayName("V2 完整快照 round-trip 后保持所有字段")
    void v2SnapshotRoundTrip() throws Exception {
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
                  "fields": []
                }
                """;
        TaskDefinition def = mapper.readValue(v2Json, TaskDefinition.class);
        assertThat(def.schemaVersion()).isEqualTo(2);
        assertThat(def.limits().pageLimit()).isEqualTo(100);
        assertThat(def.limits().durationLimit()).isEqualTo(Duration.ofMinutes(15));
        assertThat(def.listItemRule().selector()).isEqualTo("ul > li");
        assertThat(def.uniqueKey().get(0).fieldName()).isEqualTo("title");
    }
}
