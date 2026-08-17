package com.visualspider.task.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link UniqueKeyField} 单元测试（M4 spec §D1）。
 */
class UniqueKeyFieldTest {

    @Test
    @DisplayName("合法 fieldName 通过")
    void validConstruction() {
        UniqueKeyField k = new UniqueKeyField("title");
        assertThat(k.fieldName()).isEqualTo("title");
    }

    @Test
    @DisplayName("fieldName=null → IllegalArgumentException")
    void nullFieldNameRejected() {
        assertThatThrownBy(() -> new UniqueKeyField(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("fieldName 空白 → IllegalArgumentException")
    void blankFieldNameRejected() {
        assertThatThrownBy(() -> new UniqueKeyField("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("@JsonIgnoreProperties 未知字段不破坏反序列化")
    void jsonIgnoreUnknownFields() throws Exception {
        String json = "{\"fieldName\":\"href\",\"future\":\"x\"}";
        UniqueKeyField k = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readValue(json, UniqueKeyField.class);
        assertThat(k.fieldName()).isEqualTo("href");
    }
}
