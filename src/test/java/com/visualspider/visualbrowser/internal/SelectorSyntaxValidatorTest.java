package com.visualspider.visualbrowser.internal;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SelectorSyntaxValidatorTest {

    private final SelectorSyntaxValidator validator = new SelectorSyntaxValidator();

    @ParameterizedTest
    @ValueSource(strings = {
            "div.title",
            "ul#main > li.item:nth-of-type(2)",
            "a[href^='http']",
            "form input[name=email]"
    })
    void validCssSelectors(String selector) {
        assertThatCode(() -> validator.validateCss(selector)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "<<<broken",
            "div..",
            "[unclosed"
    })
    void invalidCssRejected(String selector) {
        assertThatThrownBy(() -> validator.validateCss(selector))
                .isInstanceOf(InvalidSelectorException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "//div",
            "//a[@id='go']",
            "(//h1)[1]"
    })
    void validXPath(String selector) {
        assertThatCode(() -> validator.validateXPath(selector)).doesNotThrowAnyException();
    }

    @Test
    void invalidXPathRejected() {
        assertThatThrownBy(() -> validator.validateXPath("///unbalanced"))
                .isInstanceOf(InvalidSelectorException.class);
    }

    @Test
    void emptySelectorRejected() {
        assertThatThrownBy(() -> validator.validateCss(""))
                .isInstanceOf(InvalidSelectorException.class);
        assertThatThrownBy(() -> validator.validateXPath(null))
                .isInstanceOf(InvalidSelectorException.class);
    }
}
