package com.visualspider.identity.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 登录请求 DTO。
 *
 * <p>密码限定最小长度 1（实际验证在 service 层 WeakPasswordException 路径上完成）。
 * 这里只校验非空，避免 controller 层与服务层重复规则。
 */
public record LoginRequest(
        @NotBlank @Size(max = 64) String username,
        @NotBlank String password) {
}
