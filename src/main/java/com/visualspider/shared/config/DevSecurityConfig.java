package com.visualspider.shared.config;

import com.visualspider.identity.domain.ActorId;
import com.visualspider.identity.domain.ActorRole;
import com.visualspider.shared.security.ActorPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * dev profile 专用：所有请求自动以 admin 身份登录，便于手工测试前端。
 *
 * <p><b>仅 dev profile 生效</b>；{@code SecurityConfig} 已用 {@code @Profile("!dev")} 排除，
 * dev profile 下两条 SecurityFilterChain 不会并存。
 *
 * <p>硬编码 {@code ActorId=1, role=Admin, username="admin"}：dev profile 下
 * {@code SeedAdminInitializer} 不跑，{@code app_user} 表为空，不依赖 DB 也能完成认证，
 * 其他模块通过 {@code IdentityAccess} 拿到 {@code ActorId} 正常工作。
 *
 * <p>安全注意：<b>禁止部署 dev profile 到任何对外环境</b>。仅供本地手工测试。
 */
@Configuration
@Profile("dev")
public class DevSecurityConfig {

    private static final Logger LOG = LoggerFactory.getLogger(DevSecurityConfig.class);

    /**
     * dev profile 替代 {@code SecurityConfig#passwordEncoder()}；prod profile 下由默认
     * {@code SecurityConfig} 提供同名 bean，避免重复定义。
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    SecurityFilterChain devSecurityFilterChain(HttpSecurity http) throws Exception {
        return http
                .securityContext(c -> c.securityContextRepository(new HttpSessionSecurityContextRepository()))
                .addFilterBefore(new DevAutoAdminLoginFilter(),
                        UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(a -> a.anyRequest().authenticated())
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                // dev profile 关闭 CSRF，省去前端 axios interceptor 同步 XSRF-TOKEN 的成本。
                // 生产/集成测试仍走默认 CookieCsrfTokenRepository + EagerCsrfTokenAttributeHandler。
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .build();
    }

    /**
     * 未认证请求自动塞入 admin Authentication；同 session 后续请求直接复用，
     * 模拟真实登录后的会话状态。
     */
    static final class DevAutoAdminLoginFilter extends OncePerRequestFilter {

        private static final long ADMIN_ACTOR_ID = 1L;
        private static final String ADMIN_USERNAME = "admin";

        private final SecurityContextHolderStrategy holderStrategy =
                SecurityContextHolder.getContextHolderStrategy();
        private final SecurityContextRepository contextRepository =
                new HttpSessionSecurityContextRepository();

        @Override
        protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                throws ServletException, IOException {
            SecurityContext ctx = holderStrategy.getContext();
            Authentication existing = ctx == null ? null : ctx.getAuthentication();
            if (existing == null || !existing.isAuthenticated()) {
                ActorPrincipal principal = new ActorPrincipal(
                        new ActorId(ADMIN_ACTOR_ID), ADMIN_USERNAME, new ActorRole.Admin());
                Authentication auth = principal.toSpringAuthentication();
                SecurityContext newCtx = holderStrategy.createEmptyContext();
                newCtx.setAuthentication(auth);
                holderStrategy.setContext(newCtx);
                contextRepository.saveContext(newCtx, req, res);
                LOG.warn("[DEV ONLY] auto-login as '{}' (id={}) for {} {}",
                        ADMIN_USERNAME, ADMIN_ACTOR_ID, req.getMethod(), req.getRequestURI());
            }
            chain.doFilter(req, res);
        }
    }
}