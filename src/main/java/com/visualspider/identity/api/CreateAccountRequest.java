package com.visualspider.identity.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 创建账号请求 DTO。
 *
 * <p>角色字符串 {@code ADMIN} / {@code COLLECTOR}；密码强类型为 String，
 * controller 立即转 char[] 后清零（避免 service 层中间态）。
 */
public record CreateAccountRequest(
        @NotBlank @Size(max = 64) String username,
        @NotBlank String password,
        @NotBlank @Pattern(regexp = "ADMIN|COLLECTOR") String role) {
}
