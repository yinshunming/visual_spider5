package com.visualspider.identity.domain;

/**
 * 账号聚合根的不可变快照。
 *
 * <p>来自 {@code app_user} 表查询；不含明文密码。
 * M1-2 暴露给 {@code AccountAdministration} 与 REST DTO；
 * M1+ 不修改 username（避免外部引用悬挂）；修改密码走单独方法。
 *
 * @param id 数据库主键
 * @param username 登录名（不可变）
 * @param role ADMIN / COLLECTOR
 * @param status ACTIVE / DISABLED
 */
public record UserAccount(
        ActorId id,
        String username,
        ActorRole role,
        UserStatus status) {
}
