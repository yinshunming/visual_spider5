package com.visualspider.identity.internal;

import com.visualspider.identity.domain.ActorId;
import com.visualspider.identity.domain.ActorRole;
import com.visualspider.identity.domain.UserAccount;
import com.visualspider.identity.domain.UserStatus;
import com.visualspider.identity.domain.exceptions.NotAuthenticatedException;
import com.visualspider.identity.spi.AppUserRepository;
import com.visualspider.identity.spi.IdentityAccess;
import com.visualspider.shared.security.ActorAuthentication;
import com.visualspider.shared.security.ActorPrincipal;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.stereotype.Service;

/**
 * {@link IdentityAccess} 的默认实现：从 SecurityContext 读取当前登录 principal。
 *
 * <p>通过 SecurityContextHolderStrategy 解耦测试与 Servlet 容器；
 * 模块其它部分只接收已认证 {@link ActorId}，不在 service 层再做 SecurityContext 检查。
 */
@Service
public class IdentityAccessImpl implements IdentityAccess {

    private final SecurityContextHolderStrategy holderStrategy = SecurityContextHolder.getContextHolderStrategy();
    private final AppUserRepository userRepository;

    public IdentityAccessImpl(AppUserRepository userRepository) {
        this.userRepository = userRepository;
    }

    private ActorPrincipal requirePrincipal() {
        SecurityContext context = holderStrategy.getContext();
        if (context == null) {
            throw NotAuthenticatedException.becauseSessionMissing();
        }
        var authentication = context.getAuthentication();
        if (!(authentication instanceof ActorAuthentication actorAuth)) {
            throw NotAuthenticatedException.becauseSessionMissing();
        }
        if (!actorAuth.isAuthenticated()) {
            throw NotAuthenticatedException.becauseSessionMissing();
        }
        return actorAuth.actorPrincipal();
    }

    @Override
    public ActorId currentActor() {
        return requirePrincipal().actorId();
    }

    @Override
    public ActorRole currentRole() {
        return requirePrincipal().role();
    }

    @Override
    public boolean isAdmin() {
        try {
            return requirePrincipal().isAdmin();
        } catch (NotAuthenticatedException e) {
            return false;
        }
    }

    @Override
    public boolean canAccessTask(long taskOwnerId, ActorId actor) {
        if (actor == null) {
            return false;
        }
        ActorPrincipal principal;
        try {
            principal = requirePrincipal();
        } catch (NotAuthenticatedException e) {
            return false;
        }
        // spec §D3：停用账号拒绝（即使 admin 也不能访问；admin 仅用于可见性，不绕过状态校验）
        UserAccount account = userRepository.findById(principal.actorId().value()).orElse(null);
        if (account != null && account.status() == UserStatus.DISABLED) {
            return false;
        }
        if (principal.isAdmin()) {
            return true;
        }
        return principal.actorId().value() == taskOwnerId;
    }

    @Override
    public boolean canAccessUser(long targetUserId, ActorId actor) {
        if (actor == null) {
            return false;
        }
        ActorPrincipal principal;
        try {
            principal = requirePrincipal();
        } catch (NotAuthenticatedException e) {
            return false;
        }
        UserAccount account = userRepository.findById(principal.actorId().value()).orElse(null);
        if (account != null && account.status() == UserStatus.DISABLED) {
            return false;
        }
        if (principal.isAdmin()) {
            return true;
        }
        return principal.actorId().value() == targetUserId;
    }

    @Override
    public boolean canRunAnyTask() {
        return isAdmin();
    }
}
