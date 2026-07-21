package com.visualspider.shared.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

/**
 * Spring Security 完整配置（M1-2）。
 *
 * <p>要点（spec §D6）：
 * <ul>
 *   <li>{@code /actuator/health}、{@code /error}、静态资源、{@code /ws/**} 放行</li>
 *   <li>{@code POST /api/auth/login} 放行（登录端点免 CSRF）</li>
 *   <li>{@code POST /api/auth/logout} 放行其它 /api/** 仍需认证</li>
 *   <li>CSRF 使用 {@code CookieCsrfTokenRepository.withHttpOnlyFalse()}，前端 axios interceptor 读 cookie 头写入 header</li>
 *   <li>BCrypt cost=12；session IF_REQUIRED；30 分钟超时</li>
 *   <li>Cookie HttpOnly + SameSite=Lax（不设 Secure，HTTP 部署限制）</li>
 * </ul>
 *
 * <p>CSRF BREACH 保护：默认 {@code XorCsrfTokenRequestAttributeHandler} 仅在
 * {@code CsrfToken} 被访问时才写 cookie。我们的前端 axios interceptor 在每次请求时读
 * cookie 头写入 header，需要 CSRF token cookie 在每次响应都写出来。
 * 通过 {@link CsrfTokenRequestAttributeHandler} 子类化 + 禁用 BREACH 让 CsrfFilter
 * 写出 cookie。
 *
 * <p>测试模式：{@code security.test.disable-csrf=true} 时关闭 CSRF（仅用于 *IT 装配）。
 */
@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    /**
     * 禁用 BREACH 保护的 CSRF request attribute handler：让 CsrfFilter 在每个响应
     * 都立即写出 XSRF-TOKEN cookie，前端无需先 GET 一次触发 token 生成。
     */
    static final class EagerCsrfTokenAttributeHandler extends CsrfTokenRequestAttributeHandler {
        EagerCsrfTokenAttributeHandler() {
            // null 表示禁用 BREACH 保护；token 不会用 ^ 前缀异或
            setCsrfRequestAttributeName(null);
        }
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, Environment env) throws Exception {
        boolean disableCsrf = env.getProperty("security.test.disable-csrf", boolean.class, false);
        HttpSecurity configured = http;
        if (disableCsrf) {
            configured = configured.csrf(AbstractHttpConfigurer::disable);
        } else {
            configured = configured.csrf(csrf -> csrf
                    .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                    .csrfTokenRequestHandler(new EagerCsrfTokenAttributeHandler())
                    .ignoringRequestMatchers(
                            new AntPathRequestMatcher("/api/auth/login"),
                            new AntPathRequestMatcher("/api/auth/logout")));
        }
        return configured
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                        .maximumSessions(1))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                new AntPathRequestMatcher("/actuator/health"),
                                new AntPathRequestMatcher("/actuator/health/**"),
                                new AntPathRequestMatcher("/error"),
                                new AntPathRequestMatcher("/"),
                                new AntPathRequestMatcher("/index.html"),
                                new AntPathRequestMatcher("/favicon.ico"),
                                new AntPathRequestMatcher("/assets/**"),
                                new AntPathRequestMatcher("/ws/**"),
                                new AntPathRequestMatcher("/api/auth/login"),
                                new AntPathRequestMatcher("/api/auth/logout"))
                        .permitAll()
                        .anyRequest().authenticated())
                .build();
    }
}
