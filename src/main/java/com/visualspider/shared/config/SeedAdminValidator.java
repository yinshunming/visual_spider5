package com.visualspider.shared.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.Errors;
import org.springframework.validation.ValidationUtils;
import org.springframework.validation.Validator;

/**
 * 启动校验：{@code seed.admin.username} / {@code seed.admin.raw-password} 必须存在且满足最小长度。
 *
 * <p>绑定时机：Spring Boot 创建 Bean 时（早于 {@code identity.SeedAdminInitializer} 写入 {@code app_user}）。
 * 任一字段不合法 → 抛 {@link IllegalStateException} → Spring Boot 启动失败 → 进程退出码非零。
 *
 * <p>M1-1 只校验"配置缺失/过短"路径；真正的 BCrypt 哈希与 DB 写入由 M1-2 在
 * {@code identity.SeedAdminInitializer} 内完成。
 *
 * <p>校验规则与 spec §D5 一致：trim 后非空；密码 trim 后长度 ≥ 12。
 */
@Configuration
@EnableConfigurationProperties(SeedAdminProperties.class)
public class SeedAdminValidator implements Validator {

    private static final Logger LOG = LoggerFactory.getLogger(SeedAdminValidator.class);

    private static final int MIN_PASSWORD_LENGTH = 12;

    private final SeedAdminProperties properties;

    public SeedAdminValidator(SeedAdminProperties properties) {
        this.properties = properties;
        validateOrFail();
    }

    private void validateOrFail() {
        String username = properties.username();
        String password = properties.rawPassword();

        if (username == null || username.isBlank()) {
            fail("seed.admin.username 未配置（通过 VISUALSPIDER_ADMIN_USERNAME 环境变量注入）");
        }
        if (password == null || password.isBlank()) {
            fail("seed.admin.raw-password 未配置（通过 VISUALSPIDER_ADMIN_PASSWORD 环境变量注入）");
        }
        if (password != null && password.trim().length() < MIN_PASSWORD_LENGTH) {
            fail("seed.admin.raw-password 长度不足：trim 后必须 ≥ " + MIN_PASSWORD_LENGTH + " 字符（当前 "
                    + password.trim().length() + "）");
        }
        if (password != null && password.contains(" ")) {
            LOG.warn("seed.admin.raw-password 含空白字符；通常意味着配置被错误地引号包裹或包含截断");
        }
        // spec §D8：技术日志不得记录密码相关字段（含长度）；此处仅记录 username。
        LOG.info("seed.admin 配置校验通过：username={}", username);
    }

    private static void fail(String reason) {
        throw new IllegalStateException(reason);
    }

    // ---- Spring Validator interface（暂未启用；保留供后续切到 JSR-303 路径）----
    @Override
    public boolean supports(Class<?> clazz) {
        return SeedAdminProperties.class.isAssignableFrom(clazz);
    }

    @Override
    public void validate(Object target, Errors errors) {
        ValidationUtils.rejectIfEmptyOrWhitespace(errors, "username", "seed.admin.username.required");
        ValidationUtils.rejectIfEmptyOrWhitespace(errors, "rawPassword", "seed.admin.raw-password.required");
    }
}
