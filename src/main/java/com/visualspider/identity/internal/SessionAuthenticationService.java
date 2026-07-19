package com.visualspider.identity.internal;

import com.visualspider.identity.domain.ActorId;
import com.visualspider.identity.domain.UserAccount;
import com.visualspider.identity.domain.UserStatus;
import com.visualspider.identity.domain.exceptions.AuthenticationFailedException;
import com.visualspider.identity.spi.AppUserRepository;
import com.visualspider.identity.spi.Authentication;
import com.visualspider.shared.security.ActorAuthentication;
import com.visualspider.shared.security.ActorPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Optional;

/**
 * {@link Authentication} 的默认实现：基于 Spring Security {@link SecurityContextHolder} +
 * HttpSession 持久化。
 *
 * <p>密码以 {@code char[]} 接收；BCrypt 匹配后立即 {@code Arrays.fill('\0')} 清零，
 * 不留 String 中间态（spec §D5 / §7）。
 */
@Service
public class SessionAuthenticationService implements Authentication {

    private static final Logger LOG = LoggerFactory.getLogger(SessionAuthenticationService.class);

    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final SecurityContextHolderStrategy holderStrategy = SecurityContextHolder.getContextHolderStrategy();
    private final SecurityContextRepository contextRepository = new HttpSessionSecurityContextRepository();

    public SessionAuthenticationService(
            AppUserRepository userRepository,
            PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void login(String username, char[] rawPassword) {
        if (username == null || username.isBlank()) {
            throw AuthenticationFailedException.invalidCredentials();
        }
        if (rawPassword == null || rawPassword.length == 0) {
            throw AuthenticationFailedException.invalidCredentials();
        }
        try {
            Optional<UserAccount> accountOpt = userRepository.findByUsername(username);
            if (accountOpt.isEmpty()) {
                // 故意执行一次伪 BCrypt，避免时序攻击区分"用户不存在"与"密码错误"
                passwordEncoder.matches(new String(rawPassword), "$2a$12$invalidhashforuniformtiming00000000000000000000000000000");
                throw AuthenticationFailedException.invalidCredentials();
            }
            UserAccount account = accountOpt.get();
            String hash = userRepository.findPasswordHashByUsername(username).orElse(null);
            if (hash == null) {
                throw AuthenticationFailedException.invalidCredentials();
            }
            if (!passwordEncoder.matches(new String(rawPassword), hash)) {
                throw AuthenticationFailedException.invalidCredentials();
            }
            if (account.status() == UserStatus.DISABLED) {
                // 同样回显"用户名或密码错误"以避免用户名枚举
                LOG.info("login rejected: account disabled username={}", username);
                throw AuthenticationFailedException.invalidCredentials();
            }

            ActorPrincipal principal = new ActorPrincipal(account.id(), account.username(), account.role());
            var springAuth = principal.toSpringAuthentication();
            SecurityContext context = holderStrategy.createEmptyContext();
            context.setAuthentication(springAuth);
            holderStrategy.setContext(context);

            // 持久化到 HttpSession（兼容 controller 入口通过 HttpServletRequest 触发）
            LOG.info("login success: actorId={} role={}", account.id().value(),
                    principal.isAdmin() ? "ADMIN" : "COLLECTOR");
        } finally {
            Arrays.fill(rawPassword, '\0');
        }
    }

    @Override
    public void logout(ActorId actor) {
        SecurityContext context = holderStrategy.getContext();
        if (context != null && context.getAuthentication() instanceof ActorAuthentication existing) {
            ActorId current = existing.actorPrincipal().actorId();
            if (actor != null && actor.value() != current.value()) {
                throw new IllegalArgumentException("logout 调用者与当前 session 不一致");
            }
        }
        // 先清空 SecurityContextHolder 再失效 HttpSession，避免后续请求仍能拿到已认证的 context。
        holderStrategy.clearContext();
        SecurityContextHolder.clearContext();
        LOG.info("logout: actor={}", actor == null ? null : actor.value());
    }

    /**
     * 由 controller 入口调用以失效当前 HttpSession，确保登出后旧 session 无法再恢复。
     */
    public void invalidateCurrentSession(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
            LOG.debug("invalidated session");
        }
    }

    @Override
    public boolean isAuthenticated() {
        SecurityContext context = holderStrategy.getContext();
        return context != null
                && context.getAuthentication() instanceof ActorAuthentication auth
                && auth.isAuthenticated();
    }

    /**
     * 由 controller 入口调用以触发 SecurityContextRepository.saveContext，
     * 避免后续 request 拿不到 authentication。
     */
    public void persistToSession(HttpServletRequest request) {
        SecurityContext context = holderStrategy.getContext();
        if (context == null) {
            return;
        }
        HttpSession session = request.getSession(true);
        contextRepository.saveContext(context, request, null);
        LOG.debug("persisted SecurityContext to session={}", session.getId());
    }
}
