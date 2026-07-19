package com.visualspider.shared.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;

/**
 * Spring Security {@link org.springframework.security.core.Authentication} 实现，
 * principal 为 {@link ActorPrincipal}。
 *
 * <p>登录成功后由 {@code identity.SessionAuthenticationService} 创建并存入 SecurityContext。
 */
public final class ActorAuthentication extends AbstractAuthenticationToken {

    private final ActorPrincipal principal;

    public ActorAuthentication(ActorPrincipal principal) {
        super(principal.authorities());
        this.principal = principal;
        super.setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return ""; // 不存明文密码
    }

    @Override
    public Object getPrincipal() {
        return principal;
    }

    @Override
    public String getName() {
        return principal.username();
    }

    public ActorPrincipal actorPrincipal() {
        return principal;
    }
}
