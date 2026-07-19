package com.visualspider.identity.domain.exceptions;

/**
 * 登录失败（用户名或密码错误、账号停用）。HTTP 401。
 *
 * <p>故意不区分"用户名不存在"与"密码错误"，避免用户名枚举。
 */
public class AuthenticationFailedException extends RuntimeException {
    public AuthenticationFailedException(String message) {
        super(message);
    }

    public static AuthenticationFailedException invalidCredentials() {
        return new AuthenticationFailedException("用户名或密码错误");
    }
}
