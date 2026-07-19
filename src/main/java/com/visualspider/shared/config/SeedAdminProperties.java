package com.visualspider.shared.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 初始管理员 seed 配置。
 *
 * <p>绑定 {@code application.yml} 的 {@code seed.admin.*} 段；两个字段都必须在启动时校验非空且长度 ≥ 12，
 * 否则 Spring Boot 启动失败（{@code SeedAdminValidator} 在 {@code @PostConstruct} 抛 {@link IllegalStateException}）。
 *
 * <p>M1-1 落地配置绑定与缺失拒绝；M1-2 才会读取这两个字段并写入 {@code app_user}（需要 BCrypt 哈希逻辑）。
 *
 * <p>放在 {@code shared} 包：跨 {@code identity}（写入）与运维（注入环境变量）共享，不依赖具体业务模块。
 *
 * @param username 初始管理员登录名；非空且 trim 后长度 ≥ 1
 * @param rawPassword 初始管理员明文密码；trim 后长度 ≥ 12，BCrypt 哈希后立即 {@code Arrays.fill('\0')}
 */
@ConfigurationProperties(prefix = "seed.admin")
@Validated
public record SeedAdminProperties(
        @NotBlank @Size(min = 1, max = 64) String username,
        @NotBlank @Size(min = 12, max = 128) String rawPassword) {

    public SeedAdminProperties {
        // canonical 化：去掉首尾空白；若全部为空白则留给 @NotBlank 拒绝
        if (username != null) {
            username = username.trim();
        }
    }
}
