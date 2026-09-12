package com.visualspider.shared.config;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 启动期 SSRF loopback 豁免 fail-fast 校验（M8-3 / issue #62）。
 *
 * <p>当 {@code visualbrowser.target-url.allow-loopback=true} 且激活 profile 不在
 * {@code dev} / {@code it} / {@code smoke} / {@code e2e} 之列 → 启动失败。
 *
 * <p>背景：{@code PublicTargetUrlPolicy}（M6-1）运行时拒绝访问回环地址；
 * {@code visualbrowser.target-url.allow-loopback} 仅用于本地 fixture / 验收脚本。
 * v0.1.0 没有启动期强制层，prod 误配 {@code true} 会启动成功，直到首次触发 SSRF 才报错。
 * v0.1.1 起，本类在 Spring Bean 创建期（早于端口绑定）直接抛 {@link IllegalStateException}，
 * 让 prod 配错在启动第一行就暴露。详见 {@code docs/specs/m8.md} D3 与
 * {@code docs/security/ssrf-residual-risk.md} "启动期 fail-fast" 段。
 *
 * <p>profile 旁路白名单与 m6-smoke / m7-acceptance 脚本一致。
 */
@Component
public class LoopbackStartupFailFastValidator {

    private static final Logger LOG = LoggerFactory.getLogger(LoopbackStartupFailFastValidator.class);

    static final String CONFIG_KEY = "visualbrowser.target-url.allow-loopback";

    /** 旁路白名单：这些 profile 下允许 loopback 豁免（本地 fixture / 集成测试 / 验收脚本）。 */
    static final Set<String> BYPASS_PROFILES = Set.of("dev", "it", "smoke", "e2e");

    /** 配置文档锚点：错误信息指引读者查看此处。 */
    static final String CONFIG_DOC_ANCHOR = "docs/deploy/configuration.md §3";

    public LoopbackStartupFailFastValidator(
            @Value("${" + CONFIG_KEY + ":false}") boolean allowLoopback,
            @Value("${spring.profiles.active:}") String[] activeProfiles) {
        validateOrFail(allowLoopback, asList(activeProfiles));
    }

    /**
     * 纯逻辑方法（package-private），便于单测覆盖所有 profile × property 组合。
     *
     * @param allowLoopback {@code visualbrowser.target-url.allow-loopback} 的值
     * @param activeProfiles Spring {@code spring.profiles.active} 列表
     * @throws IllegalStateException 当 prod-like profile 下开启 loopback 豁免
     */
    static void validateOrFail(boolean allowLoopback, List<String> activeProfiles) {
        if (!allowLoopback) {
            LOG.info("LoopbackStartupFailFastValidator: {} = false, no bypass needed", CONFIG_KEY);
            return;
        }
        boolean bypassed = activeProfiles.stream().anyMatch(BYPASS_PROFILES::contains);
        if (bypassed) {
            LOG.info("LoopbackStartupFailFastValidator: {} = true in profile(s) {} (test/IT bypass)",
                    CONFIG_KEY, activeProfiles);
            return;
        }
        throw new IllegalStateException(String.format(
                "LoopbackStartupFailFastValidator: %s=true in non-test profile(s) %s; "
                        + "production startup aborted. "
                        + "Loopback bypass is only allowed in dev/it/smoke/e2e profiles. "
                        + "See %s.",
                CONFIG_KEY, activeProfiles, CONFIG_DOC_ANCHOR));
    }

    private static List<String> asList(String[] arr) {
        if (arr == null || arr.length == 0) {
            return List.of();
        }
        return Arrays.asList(arr);
    }
}