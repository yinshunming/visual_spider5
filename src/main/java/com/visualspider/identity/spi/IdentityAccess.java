package com.visualspider.identity.spi;

import com.visualspider.identity.domain.ActorId;
import com.visualspider.identity.domain.ActorRole;
import com.visualspider.identity.domain.exceptions.NotAuthenticatedException;

/**
 * 身份与所有权访问 SPI。
 *
 * <p>M2 配置会话所有权矩阵的入口；其它模块接收已认证的 {@link ActorId}，
 * 不直接读 SecurityContext。
 *
 * <p>策略矩阵：
 * <ul>
 *   <li>{@link #canAccessTask(long, ActorId)}：admin 通过；owner 通过；其它 collector 拒绝；停用账号拒绝；未认证抛异常</li>
 *   <li>{@link #canAccessUser(long, ActorId)}：admin 通过；自己通过；其它拒绝</li>
 *   <li>{@link #canRunAnyTask()}：admin 通过；collector 拒绝（仅 admin 可触发跨租户任务）</li>
 * </ul>
 */
public interface IdentityAccess {

    /**
     * 当前已认证用户的 id。
     *
     * @throws NotAuthenticatedException 未登录 / session 过期
     */
    ActorId currentActor();

    /**
     * 当前已认证用户的角色。
     *
     * @throws NotAuthenticatedException 未登录
     */
    ActorRole currentRole();

    /**
     * 是否 admin。
     */
    boolean isAdmin();

    /**
     * 是否可访问 task：admin 通过；owner 通过；其它拒绝。
     */
    boolean canAccessTask(long taskOwnerId, ActorId actor);

    /**
     * 是否可访问 user 资源：admin 通过；自己通过；其它拒绝。
     */
    boolean canAccessUser(long targetUserId, ActorId actor);

    /**
     * 是否可触发任何任务（admin 通过；collector 拒绝）。
     */
    boolean canRunAnyTask();
}
