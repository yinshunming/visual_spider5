package com.visualspider.identity.api;

import com.visualspider.identity.domain.ActorRole;

/**
 * 当前登录用户响应 DTO。
 *
 * <p>{@code GET /api/identity/me} 返回；actorId 是其它模块接收的稳定身份载体。
 */
public record MeResponse(
        long actorId,
        String username,
        String role) {

    public static MeResponse of(long actorId, String username, ActorRole role) {
        return new MeResponse(
                actorId,
                username,
                role instanceof ActorRole.Admin ? "ADMIN" : "COLLECTOR");
    }
}
