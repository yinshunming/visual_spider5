package com.visualspider.task.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ListItemRule} 单元测试（M4 spec §D1）。
 */
class ListItemRuleTest {

    @Test
    @DisplayName("selector 非空 + selectorType 默认 CSS")
    void singleArgConstructorDefaultsToCss() {
        ListItemRule rule = new ListItemRule("ul > li");
        assertThat(rule.selector()).isEqualTo("ul > li");
        assertThat(rule.selectorType()).isEqualTo(SelectorType.CSS);
    }

    @Test
    @DisplayName("两位置参数原样存储")
    void twoArgConstructor() {
        ListItemRule rule = new ListItemRule("//article", SelectorType.XPATH);
        assertThat(rule.selector()).isEqualTo("//article");
        assertThat(rule.selectorType()).isEqualTo(SelectorType.XPATH);
    }

    @Test
    @DisplayName("selector=null → IllegalArgumentException")
    void nullSelectorRejected() {
        assertThatThrownBy(() -> new ListItemRule(null, SelectorType.CSS))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("selector 空白 → IllegalArgumentException")
    void blankSelectorRejected() {
        assertThatThrownBy(() -> new ListItemRule("   ", SelectorType.CSS))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("selectorType=null 自动默认 CSS（M4 兼容路径）")
    void nullSelectorTypeDefaults() {
        ListItemRule rule = new ListItemRule("div", null);
        assertThat(rule.selectorType()).isEqualTo(SelectorType.CSS);
    }

    @Test
    @DisplayName("@JsonIgnoreProperties：未知字段不破坏反序列化")
    void jsonIgnoreUnknownFields() throws Exception {
        String json = "{\"selector\":\"ul > li\",\"selectorType\":\"CSS\",\"futureField\":\"x\"}";
        ListItemRule rule = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readValue(json, ListItemRule.class);
        assertThat(rule.selector()).isEqualTo("ul > li");
    }
}
