package com.visualspider.run.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.visualspider.identity.domain.ActorId;
import com.visualspider.identity.domain.ActorRole;
import com.visualspider.identity.spi.IdentityAccess;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.mock.web.MockCookie;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.socket.WebSocketHandler;

/**
 * {@link RunOwnerHandshakeInterceptor} 端到端握手校验（#27 / spec §D16）。
 *
 * <p>覆盖：
 * <ul>
 *   <li>admin 可访问任意 run（与配置会话策略相反，spec §D17）</li>
 *   <li>collector owner 匹配通过</li>
 *   <li>非 owner 非 admin 拒</li>
 *   <li>run 不存在拒</li>
 *   <li>CSRF cookie/query 不一致拒</li>
 *   <li>Origin 不匹配拒</li>
 *   <li>未登录拒</li>
 *   <li>通过后 attrs 写入 actor + runId</li>
 * </ul>
 */
class RunOwnerHandshakeInterceptorTest {

    private RunRepository repository;
    private IdentityAccess identityAccess;
    private RunOwnerHandshakeInterceptor interceptor;

    @BeforeEach
    void setUp() {
        repository = mock(RunRepository.class);
        identityAccess = mock(IdentityAccess.class);
        interceptor = new RunOwnerHandshakeInterceptor(identityAccess, repository);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ---------- helpers ----------

    private ServerHttpRequest makeRequest(String uri, String queryString, String origin,
                                          List<MockCookie> cookies) {
        MockHttpServletRequest mock = new MockHttpServletRequest();
        mock.setScheme("http");
        mock.setServerName("localhost");
        mock.setServerPort(8080);
        mock.setRequestURI(uri);
        if (queryString != null) {
            mock.setQueryString(queryString);
        }
        if (cookies != null) {
            mock.setCookies(cookies.toArray(new MockCookie[0]));
        }
        ServletServerHttpRequest wrapper = new ServletServerHttpRequest(mock);
        // 用 setHeader 模拟 Origin
        if (origin != null) {
            wrapper.getHeaders().set(HttpHeaders.ORIGIN, origin);
        }
        return wrapper;
    }

    private ServerHttpResponse responseStub() {
        ServerHttpResponse resp = mock(ServerHttpResponse.class);
        org.mockito.Mockito.doAnswer(inv -> {
            return null;
        }).when(resp).setStatusCode(org.mockito.ArgumentMatchers.any());
        return resp;
    }

    private void authenticateAs(ActorId actor, boolean admin) {
        ActorRole role = admin ? new ActorRole.Admin() : new ActorRole.Collector();
        var principal = new com.visualspider.shared.security.ActorPrincipal(actor, "user", role);
        var auth = new com.visualspider.shared.security.ActorAuthentication(principal);
        SecurityContextHolder.getContext().setAuthentication(auth);
        when(identityAccess.isAdmin()).thenReturn(admin);
    }

    private RunRepository.RunRecord runRecord(long runId, long ownerId) {
        return new RunRepository.RunRecord(runId, 1L, ownerId, com.visualspider.run.spi.RunState.RUNNING,
                null, false, 0, 0, 0, null,
                java.time.OffsetDateTime.now(), java.time.OffsetDateTime.now(), null);
    }

    // ---------- tests ----------

    @Test
    void adminCanAccessAnyRun() {
        authenticateAs(new ActorId(99L), true);
        when(repository.findById(7L)).thenReturn(Optional.of(runRecord(7L, 1L)));

        var attrs = new HashMap<String, Object>();
        boolean ok = interceptor.beforeHandshake(
                makeRequest("/ws/runs/7", "csrfToken=t", "http://localhost:8080",
                        List.of(new MockCookie("XSRF-TOKEN", "t"))),
                responseStub(),
                mock(WebSocketHandler.class),
                attrs);

        assertThat(ok).isTrue();
        assertThat(attrs.get(RunOwnerHandshakeInterceptor.ATTR_ACTOR)).isEqualTo(new ActorId(99L));
        assertThat(attrs.get(RunOwnerHandshakeInterceptor.ATTR_RUN_ID)).isEqualTo(7L);
    }

    @Test
    void collectorOwnerMatches() {
        authenticateAs(new ActorId(1L), false);
        when(repository.findById(7L)).thenReturn(Optional.of(runRecord(7L, 1L)));

        var attrs = new HashMap<String, Object>();
        boolean ok = interceptor.beforeHandshake(
                makeRequest("/ws/runs/7", "csrfToken=t", "http://localhost:8080",
                        List.of(new MockCookie("XSRF-TOKEN", "t"))),
                responseStub(), mock(WebSocketHandler.class), attrs);

        assertThat(ok).isTrue();
    }

    @Test
    void collectorNonOwnerDenied() {
        authenticateAs(new ActorId(2L), false);
        when(repository.findById(7L)).thenReturn(Optional.of(runRecord(7L, 1L)));

        var attrs = new HashMap<String, Object>();
        boolean ok = interceptor.beforeHandshake(
                makeRequest("/ws/runs/7", "csrfToken=t", "http://localhost:8080",
                        List.of(new MockCookie("XSRF-TOKEN", "t"))),
                responseStub(), mock(WebSocketHandler.class), attrs);

        assertThat(ok).isFalse();
        assertThat(attrs).doesNotContainKey(RunOwnerHandshakeInterceptor.ATTR_RUN_ID);
    }

    @Test
    void missingRunDenied() {
        authenticateAs(new ActorId(99L), true);
        when(repository.findById(anyLong())).thenReturn(Optional.empty());

        var attrs = new HashMap<String, Object>();
        boolean ok = interceptor.beforeHandshake(
                makeRequest("/ws/runs/7", "csrfToken=t", "http://localhost:8080",
                        List.of(new MockCookie("XSRF-TOKEN", "t"))),
                responseStub(), mock(WebSocketHandler.class), attrs);

        assertThat(ok).isFalse();
    }

    @Test
    void csrfMismatchDenied() {
        authenticateAs(new ActorId(1L), false);
        when(repository.findById(7L)).thenReturn(Optional.of(runRecord(7L, 1L)));

        var attrs = new HashMap<String, Object>();
        boolean ok = interceptor.beforeHandshake(
                makeRequest("/ws/runs/7", "csrfToken=cookie-t", "http://localhost:8080",
                        List.of(new MockCookie("XSRF-TOKEN", "server-t"))),
                responseStub(), mock(WebSocketHandler.class), attrs);

        assertThat(ok).isFalse();
    }

    @Test
    void originMismatchDenied() {
        authenticateAs(new ActorId(1L), false);
        when(repository.findById(7L)).thenReturn(Optional.of(runRecord(7L, 1L)));

        var attrs = new HashMap<String, Object>();
        boolean ok = interceptor.beforeHandshake(
                makeRequest("/ws/runs/7", "csrfToken=t", "http://evil.com",
                        List.of(new MockCookie("XSRF-TOKEN", "t"))),
                responseStub(), mock(WebSocketHandler.class), attrs);

        assertThat(ok).isFalse();
    }

    @Test
    void missingOriginDenied() {
        authenticateAs(new ActorId(1L), false);
        when(repository.findById(7L)).thenReturn(Optional.of(runRecord(7L, 1L)));

        var attrs = new HashMap<String, Object>();
        boolean ok = interceptor.beforeHandshake(
                makeRequest("/ws/runs/7", "csrfToken=t", null,
                        List.of(new MockCookie("XSRF-TOKEN", "t"))),
                responseStub(), mock(WebSocketHandler.class), attrs);

        assertThat(ok).isFalse();
    }

    @Test
    void unauthenticatedDenied() {
        // No SecurityContextHolder.setAuthentication()
        when(repository.findById(7L)).thenReturn(Optional.of(runRecord(7L, 1L)));

        var attrs = new HashMap<String, Object>();
        boolean ok = interceptor.beforeHandshake(
                makeRequest("/ws/runs/7", "csrfToken=t", "http://localhost:8080",
                        List.of(new MockCookie("XSRF-TOKEN", "t"))),
                responseStub(), mock(WebSocketHandler.class), attrs);

        assertThat(ok).isFalse();
    }

    @Test
    void invalidRunIdInPathDenied() {
        authenticateAs(new ActorId(1L), false);

        var attrs = new HashMap<String, Object>();
        boolean ok = interceptor.beforeHandshake(
                makeRequest("/ws/runs/not-a-number", "csrfToken=t", "http://localhost:8080",
                        List.of(new MockCookie("XSRF-TOKEN", "t"))),
                responseStub(), mock(WebSocketHandler.class), attrs);

        assertThat(ok).isFalse();
    }
}
