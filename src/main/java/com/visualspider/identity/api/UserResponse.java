package com.visualspider.identity.api;

import com.visualspider.identity.domain.ActorRole;
import com.visualspider.identity.domain.UserAccount;

/**
 * 账号响应 DTO。
 *
 * <p>不暴露 password_hash；M1-2 仅用于 admin 端点。
 */
public record UserResponse(
        long id,
        String username,
        String role,
        String status) {

    public static UserResponse from(UserAccount account) {
        String roleStr = account.role() instanceof ActorRole.Admin ? "ADMIN" : "COLLECTOR";
        return new UserResponse(
                account.id().value(),
                account.username(),
                roleStr,
                account.status().name());
    }
}
