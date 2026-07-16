package com.visualspider.spike.m0;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CandidateGeneratorTest {

    @Test
    void cssWithIdAndClass() {
        assertThat(CandidateGenerator.css("input", "name", "form-control active"))
                .containsExactly("input#name", "input.form-control", "input");
    }

    @Test
    void cssOnlyTag() {
        assertThat(CandidateGenerator.css("h1", "", "")).containsExactly("h1");
    }

    @Test
    void xpathWithId() {
        assertThat(CandidateGenerator.xpath("input", "name", null))
                .containsExactly("//input[@id='name']", "//input");
    }

    @Test
    void xpathWithClass() {
        assertThat(CandidateGenerator.xpath("div", "", "card item"))
                .containsExactly("//div[contains(@class,'card')]", "//div");
    }

    @Test
    void cssAndXpathOrderedBySpecificity() {
        assertThat(CandidateGenerator.css("button", "submit-btn", "primary"))
                .first().isEqualTo("button#submit-btn");
        assertThat(CandidateGenerator.xpath("button", "submit-btn", "primary"))
                .first().isEqualTo("//button[@id='submit-btn']");
    }
}
