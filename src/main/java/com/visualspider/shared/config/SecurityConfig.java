package com.visualspider.shared.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

/**
 * Spring Security 完整配置（M1-2）。
 *
 * <p>要点（spec §D6）：
 * <ul>
 *   <li>{@code /actuator/health}、{@code /error}、静态资源、{@code /ws/**} 放行</li>
 *   <li>{@code POST /api/auth/login} 放行（登录端点免 CSRF）</li>
 *   <li>{@code POST /api/auth/logout}、{@code GET /api/identity/me} 放行其它 /api/** 仍需认证</li>
 *   <li>CSRF 使用 {@code CookieCsrfTokenRepository.withHttpOnlyFalse()}，前端 axios interceptor 读 cookie 头写入 header</li>
 *   <li>BCrypt cost=12；session IF_REQUIRED；30 分钟超时</li>
 *   <li>Cookie HttpOnly + SameSite=Lax（不设 Secure，HTTP 部署限制）</li>
 * </ul>
 */
@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .ignoringRequestMatchers(
                                new AntPathRequestMatcher("/api/auth/login"),
                                new AntPathRequestMatcher("/api/auth/logout")))
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
