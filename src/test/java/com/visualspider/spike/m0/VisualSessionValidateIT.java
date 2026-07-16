package com.visualspider.spike.m0;

import org.junit.jupiter.api.Test;

import java.net.URL;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 手写选择器校验集成测试：系统规则与手写等价规则一致匹配、非法语法报错、多匹配计数。
 * 候选生成纯逻辑由 CandidateGeneratorTest 单测覆盖；本测试验证端到端（querySelectorAll/xpath 重新查询 DOM）。
 */
class VisualSessionValidateIT {
    private static final long TIMEOUT_SECONDS = 15;

    @Test
    void generatedAndManualCssRuleMatchSameElements() throws Exception {
        URL resource = getClass().getResource("/fixtures/static.html");
        assertThat(resource).isNotNull();
        try (VisualSession session = new VisualSession("s1", resource.toURI().toString())) {
            double[] center = session.control().elementCenter("#input")
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            int x = (int) center[0];
            int y = (int) center[1];

            // 先 select 拿系统生成的最具体 CSS 候选
            session.handle(new InputCommand("s1", 1, 1280, 720,
                    InputCommand.TYPE_SELECT, x, y, null, null, null, null));
            String generated = session.status().selection().cssCandidates().get(0);

            // 手写同一选择器走 validate 路径，结果应一致
            session.handle(new InputCommand("s1", 2, 1280, 720,
                    InputCommand.TYPE_VALIDATE_SELECTOR, null, null, null, null, null, null, generated, "css"));
            ValidationResult val = session.status().validationResult();
            assertThat(val.valid()).isTrue();
            assertThat(val.count()).isEqualTo(1);
        }
    }

    @Test
    void invalidCssSyntaxReturnsError() throws Exception {
        URL resource = getClass().getResource("/fixtures/static.html");
        assertThat(resource).isNotNull();
        try (VisualSession session = new VisualSession("s1", resource.toURI().toString())) {
            session.handle(new InputCommand("s1", 1, 1280, 720,
                    InputCommand.TYPE_VALIDATE_SELECTOR, null, null, null, null, null, null, "##invalid", "css"));
            ValidationResult val = session.status().validationResult();
            assertThat(val.valid()).isFalse();
            assertThat(val.error()).isNotEmpty();
        }
    }

    @Test
    void wildcardCssMatchesMultipleElements() throws Exception {
        URL resource = getClass().getResource("/fixtures/static.html");
        assertThat(resource).isNotNull();
        try (VisualSession session = new VisualSession("s1", resource.toURI().toString())) {
            session.handle(new InputCommand("s1", 1, 1280, 720,
                    InputCommand.TYPE_VALIDATE_SELECTOR, null, null, null, null, null, null, "*", "css"));
            ValidationResult val = session.status().validationResult();
            assertThat(val.valid()).isTrue();
            assertThat(val.count()).isGreaterThan(5);
            assertThat(val.elements()).isNotEmpty();
        }
    }
}
