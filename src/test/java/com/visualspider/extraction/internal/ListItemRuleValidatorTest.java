package com.visualspider.extraction.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.visualspider.task.domain.ListItemRule;
import com.visualspider.task.domain.SelectorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ListItemRuleValidator} 单元测试。
 */
class ListItemRuleValidatorTest {

    private final ListItemRuleValidator validator = new ListItemRuleValidator();

    @Test
    @DisplayName("合法 CSS 规则")
    void cssValid() {
        var v = validator.validate(new ListItemRule("ul > li", SelectorType.CSS));
        assertThat(v.valid()).isTrue();
    }

    @Test
    @DisplayName("合法 XPath 规则")
    void xpathValid() {
        var v = validator.validate(new ListItemRule("//ul/li", SelectorType.XPATH));
        assertThat(v.valid()).isTrue();
    }

    @Test
    @DisplayName("CSS 语法错误返回 error")
    void cssInvalid() {
        var v = validator.validate(new ListItemRule("<<<broken", SelectorType.CSS));
        assertThat(v.valid()).isFalse();
        assertThat(v.message()).contains("CSS");
    }

    @Test
    @DisplayName("XPath 语法错误返回 error")
    void xpathInvalid() {
        var v = validator.validate(new ListItemRule("///not-valid", SelectorType.XPATH));
        assertThat(v.valid()).isFalse();
        assertThat(v.message()).contains("XPath");
    }

    @Test
    @DisplayName("空白 selector 构造器拒绝（ListItemRule 记录层防线）")
    void blankSelectorRejectedAtConstruction() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> new ListItemRule("", SelectorType.CSS))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("null 规则返回 error")
    void nullRule() {
        var v = validator.validate(null);
        assertThat(v.valid()).isFalse();
    }

    @Test
    @DisplayName("selectorType=null 默认 CSS 校验通过")
    void nullSelectorTypeDefaultsCss() {
        var v = validator.validate(new ListItemRule("ul > li", null));
        assertThat(v.valid()).isTrue();
    }
}
