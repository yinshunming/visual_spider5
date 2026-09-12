package com.visualspider.shared.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link LoopbackStartupFailFastValidator} 单测（M8-3 / issue #62）。
 *
 * <p>覆盖 M8-3 spec D3 退出标准列出的 3 个场景 + 1 个 baseline：
 * <ul>
 *   <li>prod + allow-loopback=true → fail-fast</li>
 *   <li>prod + allow-loopback 未设（false）→ 启动正常</li>
 *   <li>dev + allow-loopback=true → 启动正常（test bypass）</li>
 *   <li>it / smoke / e2e profile + allow-loopback=true → 启动正常</li>
 * </ul>
 */
class LoopbackStartupFailFastValidatorTest {

    @Test
    @DisplayName("prod profile + allow-loopback=true → fail-fast（指向 configuration.md §3）")
    void rejectsProdWithLoopbackAllowed() {
        assertThatThrownBy(() ->
                LoopbackStartupFailFastValidator.validateOrFail(true, List.of("prod")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(LoopbackStartupFailFastValidator.CONFIG_KEY)
                .hasMessageContaining("prod")
                .hasMessageContaining(LoopbackStartupFailFastValidator.CONFIG_DOC_ANCHOR);
    }

    @Test
    @DisplayName("prod profile + allow-loopback 未设（false）→ 启动正常")
    void allowsProdWithLoopbackDisabled() {
        assertThatCode(() ->
                LoopbackStartupFailFastValidator.validateOrFail(false, List.of("prod")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("dev profile + allow-loopback=true → 启动正常（test bypass）")
    void allowsDevWithLoopbackAllowed() {
        assertThatCode(() ->
                LoopbackStartupFailFastValidator.validateOrFail(true, List.of("dev")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("空 profile（无 active）+ allow-loopback=true → fail-fast（视为 prod）")
    void rejectsEmptyProfileWithLoopbackAllowed() {
        assertThatThrownBy(() ->
                LoopbackStartupFailFastValidator.validateOrFail(true, List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(LoopbackStartupFailFastValidator.CONFIG_KEY);
    }

    @Test
    @DisplayName("it profile + allow-loopback=true → 启动正常（IT bypass）")
    void allowsItWithLoopbackAllowed() {
        assertThatCode(() ->
                LoopbackStartupFailFastValidator.validateOrFail(true, List.of("it")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("smoke profile + allow-loopback=true → 启动正常（m6/m7 smoke bypass）")
    void allowsSmokeWithLoopbackAllowed() {
        assertThatCode(() ->
                LoopbackStartupFailFastValidator.validateOrFail(true, List.of("smoke")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("e2e profile + allow-loopback=true → 启动正常（m7-acceptance bypass）")
    void allowsE2eWithLoopbackAllowed() {
        assertThatCode(() ->
                LoopbackStartupFailFastValidator.validateOrFail(true, List.of("e2e")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("多 profile 中任一为 dev/it/smoke/e2e → 启动正常")
    void allowsMixedProfilesWhenOneIsBypass() {
        assertThatCode(() ->
                LoopbackStartupFailFastValidator.validateOrFail(true, List.of("prod", "smoke")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("allow-loopback=false 在任何 profile 下 → 启动正常")
    void allowsAllWhenLoopbackDisabled() {
        assertThatCode(() ->
                LoopbackStartupFailFastValidator.validateOrFail(false, List.of()))
                .doesNotThrowAnyException();
        assertThatCode(() ->
                LoopbackStartupFailFastValidator.validateOrFail(false, List.of("prod")))
                .doesNotThrowAnyException();
    }
}