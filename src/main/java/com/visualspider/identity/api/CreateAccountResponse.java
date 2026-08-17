package com.visualspider.identity.api;

/**
 * 创建账号响应 DTO。M1-4 替换 Map 返回值。
 */
public record CreateAccountResponse(long id, String username, String role) {
}
