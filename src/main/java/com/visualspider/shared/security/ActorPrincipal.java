package com.visualspider.shared.security;

import com.visualspider.identity.domain.ActorId;
import com.visualspider.identity.domain.ActorRole;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Collection;
import java.util.List;

/**
 * Authentication principal：登录后存入 SecurityContext。
 *
 * <p>封装 {@link ActorId} + role + username；其它模块通过 {@code IdentityAccess} 间接读取，
 * 不直接读 SecurityContext。
 */
public final class ActorPrincipal {

    public static final String AUTHORITY_ADMIN = "ROLE_ADMIN";
    public static final String AUTHORITY_COLLECTOR = "ROLE_COLLECTOR";

    private final ActorId actorId;
    private final String username;
    private final ActorRole role;

    public ActorPrincipal(ActorId actorId, String username, ActorRole role) {
        this.actorId = actorId;
        this.username = username;
        this.role = role;
    }

    public ActorId actorId() {
        return actorId;
    }

    public String username() {
        return username;
    }

    public ActorRole role() {
        return role;
    }

    public boolean isAdmin() {
        return role instanceof ActorRole.Admin;
    }

    public Collection<? extends GrantedAuthority> authorities() {
        return List.of(new SimpleGrantedAuthority(
                role instanceof ActorRole.Admin ? AUTHORITY_ADMIN : AUTHORITY_COLLECTOR));
    }

    /**
     * 构造 Spring Security {@link Authentication}，用于 {@code SecurityContextHolder.setAuthentication}。
     */
    public Authentication toSpringAuthentication() {
        return new ActorAuthentication(this);
    }
}
