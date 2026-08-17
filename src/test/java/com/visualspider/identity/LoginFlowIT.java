package com.visualspider.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

/**
 * M1-2 集成测试：admin 登录 → /api/identity/me 返回 admin。
 *
 * <p>依赖真实 PostgreSQL（{@code -Ppg-it -Dpg.it.url=...}）。
 * 使用 {@code webEnvironment = RANDOM_PORT} 启动真实 Tomcat，避免 MockMvc 与 WebSocket config 冲突。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("it")
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.baseline-on-migrate=true",
        "logging.level.org.springframework.security=DEBUG",
        "logging.level.org.springframework.web=DEBUG"
})
class LoginFlowIT {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("admin 登录后 /me 返回 admin 用户")
    void adminLoginThenMe() throws Exception {
        String baseUrl = "http://localhost:" + port;

        // 1. POST /api/auth/login（CSRF 豁免）— 提取 Set-Cookie 用于后续请求
        HttpHeaders loginHeaders = new HttpHeaders();
        loginHeaders.setContentType(MediaType.APPLICATION_JSON);
        String loginBody = "{\"username\":\"test-admin\",\"password\":\"test-password-12\"}";
        ResponseEntity<String> loginResp = restTemplate.exchange(
                baseUrl + "/api/auth/login", HttpMethod.POST,
                new HttpEntity<>(loginBody, loginHeaders), String.class);
        assertThat(loginResp.getStatusCode().value()).isEqualTo(200);

        // 从 login 响应中提取 JSESSIONID cookie 并设置到后续 GET 请求
        HttpHeaders meHeaders = new HttpHeaders();
        List<String> cookies = loginResp.getHeaders().get(HttpHeaders.SET_COOKIE);
        if (cookies != null) {
            cookies.stream()
                    .filter(c -> c.startsWith("JSESSIONID="))
                    .findFirst()
                    .ifPresent(cookie -> {
                        int eq = cookie.indexOf('=');
                        int semi = cookie.indexOf(';');
                        String value = cookie.substring(eq + 1, semi > 0 ? semi : cookie.length());
                        meHeaders.add(HttpHeaders.COOKIE, "JSESSIONID=" + value);
                    });
        }

        // 2. GET /api/identity/me
        ResponseEntity<String> me = restTemplate.exchange(
                baseUrl + "/api/identity/me", HttpMethod.GET,
                new HttpEntity<>(meHeaders), String.class);
        assertThat(me.getStatusCode().value()).isEqualTo(200);
        JsonNode payload = objectMapper.readTree(me.getBody());
        assertThat(payload.get("role").asText()).isEqualTo("ADMIN");
    }
}
