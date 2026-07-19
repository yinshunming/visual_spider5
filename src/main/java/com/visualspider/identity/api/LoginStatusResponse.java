package com.visualspider.identity.api;

/**
 * 登录状态响应 DTO。
 */
public record LoginStatusResponse(
        boolean authenticated,
        Long actorId,
        String username,
        String role) {

    public static LoginStatusResponse anonymous() {
        return new LoginStatusResponse(false, null, null, null);
    }
}
