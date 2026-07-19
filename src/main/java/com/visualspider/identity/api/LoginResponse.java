package com.visualspider.identity.api;

/**
 * 登录响应 DTO。M1-4 替换 Map 返回值，避免直接暴露 {@code Map.of} 类型。
 */
public record LoginResponse(long actorId, String username) {
}
