package com.visualspider.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
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

/**
 * M1-2 集成测试：采集人员调 admin 端点 403。
 *
 * <p>依赖真实 PostgreSQL。手工从 login 响应中提取 JSESSIONID + XSRF-TOKEN cookie，
 * 透传给后续 POST 请求（CSRF required）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("it")
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.baseline-on-migrate=true",
        "security.test.disable-csrf=true"
})
class AccountAdminIT {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    private record Session(String jsessionId, String xsrfToken) {}

    private Session loginAndCaptureCookies(String username, String password) {
        // 测试装配 security.test.disable-csrf=true 关掉 CSRF；只需要 JSESSIONID。
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}";
        ResponseEntity<String> resp = restTemplate.exchange(
                "http://localhost:" + port + "/api/auth/login", HttpMethod.POST,
                new HttpEntity<>(body, h), String.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);

        List<String> cookies = resp.getHeaders().get(HttpHeaders.SET_COOKIE);
        assertThat(cookies).as("Set-Cookie headers").isNotEmpty();
        String jsession = extractCookie(cookies, "JSESSIONID");
        return new Session(jsession, null);
    }

    private String extractCookie(List<String> cookies, String name) {
        return cookies.stream()
                .filter(c -> c.startsWith(name + "="))
                .findFirst()
                .map(c -> {
                    int eq = c.indexOf('=');
                    int semi = c.indexOf(';');
                    return c.substring(eq + 1, semi > 0 ? semi : c.length());
                })
                .orElseThrow(() -> new IllegalStateException("Cookie not found: " + name));
    }

    private ResponseEntity<String> postWithSession(String path, String body, Session session) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.add(HttpHeaders.COOKIE, "JSESSIONID=" + session.jsessionId());
        return restTemplate.exchange(
                "http://localhost:" + port + path, HttpMethod.POST,
                new HttpEntity<>(body, h), String.class);
    }

    @Test
    @DisplayName("collector 调用 POST /api/admin/users 返回 403")
    void collectorCannotCreateUser() {
        String collector = "alice-" + System.currentTimeMillis();
        String collectorPwd = "alice-pwd-12+chars";

        // 1. admin 登录拿 session + csrf
        Session admin = loginAndCaptureCookies("test-admin", "test-password-12");

        // 2. admin 创建 collector（应 201）
        String createBody = String.format(
                "{\"username\":\"%s\",\"password\":\"%s\",\"role\":\"COLLECTOR\"}", collector, collectorPwd);
        ResponseEntity<String> createResp = postWithSession("/api/admin/users", createBody, admin);
        assertThat(createResp.getStatusCode().value()).isEqualTo(201);

        // 3. collector 登录拿 session
        Session alice = loginAndCaptureCookies(collector, collectorPwd);

        // 4. collector 调 admin 端点 → 403
        ResponseEntity<String> denied = postWithSession("/api/admin/users", createBody, alice);
        assertThat(denied.getStatusCode().value()).isEqualTo(403);
    }
}
