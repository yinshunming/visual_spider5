package com.visualspider.run.internal;

import com.visualspider.identity.domain.ActorId;
import com.visualspider.identity.domain.exceptions.NotAuthenticatedException;
import com.visualspider.identity.spi.IdentityAccess;
import com.visualspider.shared.security.ActorPrincipal;
import com.visualspider.visualbrowser.internal.OriginMatcher;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * 运行进度 WebSocket 握手校验（M3-5 #27 / spec §D16 / D17）。
 *
 * <p>镜像 {@code SessionOwnerHandshakeInterceptor}，但所有权规则不同：
 * <ul>
 *   <li>配置会话：admin 不可接管（spec §17）</li>
 *   <li>运行：<b>admin 可访问任意 run</b>（spec §D16 / D17 / product-spec §4.1）</li>
 * </ul>
 *
 * <p>校验顺序：
 * <ol>
 *   <li>JSESSIONID cookie + 已认证 SecurityContext -&gt; {@link ActorId}</li>
 *   <li>URL path 中提取 runId（{@code /ws/runs/{runId}} 末尾；必须是正整数 long）</li>
 *   <li>run 存在（{@link RunRepository#findById}）</li>
 *   <li>owner == actor 或 actor 是 admin</li>
 *   <li>CSRF：{@code XSRF-TOKEN} cookie == query {@code csrfToken}</li>
 *   <li>Same-Origin：Origin header 与 requestUri 同 scheme/host/port</li>
 * </ol>
 *
 * <p>不通过则 403 拒；attrs 写 {@link #ATTR_ACTOR} + {@link #ATTR_RUN_ID}。
 */
@Component
public class RunOwnerHandshakeInterceptor implements HandshakeInterceptor {

    private static final Logger LOG = LoggerFactory.getLogger(RunOwnerHandshakeInterceptor.class);

    public static final String ATTR_ACTOR = "run.actor";
    public static final String ATTR_RUN_ID = "run.runId";

    private final IdentityAccess identityAccess;
    private final RunRepository repository;

    public RunOwnerHandshakeInterceptor(IdentityAccess identityAccess, RunRepository repository) {
        this.identityAccess = identityAccess;
        this.repository = repository;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        if (!(request instanceof ServletServerHttpRequest servletRequest)) {
            return deny(response, "No servlet request");
        }
        HttpServletRequest http = servletRequest.getServletRequest();

        // 1. 已登录
        ActorId actor;
        try {
            actor = resolveActor();
        } catch (NotAuthenticatedException ex) {
            return deny(response, "Auth required");
        }
        if (actor == null) {
            return deny(response, "Auth required");
        }

        // 2. runId 来自 path
        Long runId = extractRunIdFromPath(http.getRequestURI());
        if (runId == null) {
            return deny(response, "Missing runId");
        }

        // 3. run 存在 + 4. owner / admin
        RunRepository.RunRecord rec = repository.findById(runId).orElse(null);
        if (rec == null) {
            return deny(response, "Run not found");
        }
        if (!isOwnerOrAdmin(rec, actor)) {
            return deny(response, "Run owner mismatch");
        }

        // 5. CSRF
        String cookieToken = findCookieValue(http, "XSRF-TOKEN");
        String queryToken = extractSingleQueryParam(http.getQueryString(), "csrfToken");
        if (cookieToken == null || queryToken == null || !cookieToken.equals(queryToken)) {
            return deny(response, "CSRF token mismatch");
        }

        // 6. Same-Origin
        String origin = request.getHeaders().getFirst(HttpHeaders.ORIGIN);
        String scheme = request.getURI().getScheme();
        int port = request.getURI().getPort();
        String reqUri = (scheme == null ? "" : scheme) + "://" + request.getURI().getHost()
                + (port == -1 ? "" : ":" + port);
        if (origin == null) {
            return deny(response, "Origin required");
        }
        if (!OriginMatcher.matches(origin, reqUri)) {
            return deny(response, "Origin mismatch");
        }

        attributes.put(ATTR_ACTOR, actor);
        attributes.put(ATTR_RUN_ID, runId);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        if (exception != null && LOG.isDebugEnabled()) {
            LOG.debug("ws run handshake exception", exception);
        }
    }

    private boolean isOwnerOrAdmin(RunRepository.RunRecord rec, ActorId actor) {
        if (identityAccess.isAdmin()) {
            return true;
        }
        return rec.ownerId() == actor.value();
    }

    private static ActorId resolveActor() {
        SecurityContext ctx = SecurityContextHolder.getContext();
        if (ctx == null) {
            throw NotAuthenticatedException.becauseSessionMissing();
        }
        var auth = ctx.getAuthentication();
        if (auth == null || !auth.isAuthenticated()
                || !(auth.getPrincipal() instanceof ActorPrincipal principal)) {
            throw NotAuthenticatedException.becauseSessionMissing();
        }
        return principal.actorId();
    }

    private static boolean deny(ServerHttpResponse response, String logReason) {
        response.setStatusCode(HttpStatus.FORBIDDEN);
        LOG.debug("ws run handshake rejected: {}", logReason);
        return false;
    }

    /**
     * path = {@code /ws/runs/{runId}} 末尾即 runId（必须是正整数 long）。
     * 非数字 / 空 / 路径穿越字符 -&gt; null。
     */
    static Long extractRunIdFromPath(String uri) {
        if (uri == null) {
            return null;
        }
        int idx = uri.lastIndexOf('/');
        if (idx < 0 || idx == uri.length() - 1) {
            return null;
        }
        String segment = uri.substring(idx + 1);
        if (segment.contains("..") || segment.contains("/") || segment.contains("\\")) {
            return null;
        }
        try {
            long v = Long.parseLong(segment);
            if (v <= 0) {
                return null;
            }
            return v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 单值 query 参数解析：禁止重复（任一参数出现 2 次或缺少 csrfToken 返回 null）。 */
    static String extractSingleQueryParam(String queryString, String name) {
        if (queryString == null || queryString.isEmpty()) {
            return null;
        }
        String found = null;
        for (String pair : queryString.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = pair.substring(0, eq);
            if (!key.equals(name)) {
                continue;
            }
            String value = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            if (found != null && !found.equals(value)) {
                return null;
            }
            found = value;
        }
        return found;
    }

    private static String findCookieValue(HttpServletRequest http, String name) {
        Cookie[] cookies = http.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
