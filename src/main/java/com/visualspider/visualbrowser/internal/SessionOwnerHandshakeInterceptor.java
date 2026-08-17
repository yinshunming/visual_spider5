package com.visualspider.visualbrowser.internal;

import com.visualspider.identity.domain.ActorId;
import com.visualspider.identity.domain.exceptions.NotAuthenticatedException;
import com.visualspider.shared.security.ActorPrincipal;
import com.visualspider.visualbrowser.spi.VisualSessionManager;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
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
 * WebSocket 握手身份校验（M2-1 #17）。
 *
 * <p>校验：
 * <ol>
 *   <li>JSESSIONID cookie + 已认证 SecurityContext → {@link ActorId}</li>
 *   <li>URL path 中提取 sessionId，已存在且 owner 等于 actor</li>
 *   <li>query 参数 {@code csrfToken} 与 {@code XSRF-TOKEN} cookie 精确一致</li>
 *   <li>请求 Origin 与 same-origin 匹配</li>
 * </ol>
 *
 * <p>不通过则返回 false，写 403，attrs 不写入；不允许客户端伪造 task。
 */
@Component
public class SessionOwnerHandshakeInterceptor implements HandshakeInterceptor {

    private static final Logger LOG = LoggerFactory.getLogger(SessionOwnerHandshakeInterceptor.class);
    public static final String ATTR_ACTOR = "visual.actor";
    public static final String ATTR_SESSION_ID = "visual.sessionId";

    private final VisualSessionManager manager;

    public SessionOwnerHandshakeInterceptor(VisualSessionManager manager) {
        this.manager = manager;
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

        // 2. sessionId 来自 path
        String sessionId = extractSessionIdFromPath(http.getRequestURI());
        if (sessionId == null || sessionId.isBlank()) {
            return deny(response, "Missing sessionId");
        }

        var owned = manager.findBySessionId(sessionId)
                .orElse(null);
        if (owned == null) {
            return deny(response, "Session not found");
        }
        if (!owned.owner().equals(actor)) {
            // admin 也无法接管别人的 session：spec §17 / #17 决策
            return deny(response, "Session owner mismatch");
        }

        // 3. CSRF
        String cookieToken = findCookieValue(http, "XSRF-TOKEN");
        String queryToken = extractSingleQueryParam(http.getQueryString(), "csrfToken");
        if (cookieToken == null || queryToken == null || !cookieToken.equals(queryToken)) {
            return deny(response, "CSRF token mismatch");
        }

        // 4. Same-Origin
        String origin = request.getHeaders().getFirst(HttpHeaders.ORIGIN);
        String scheme = request.getURI().getScheme();
        int port = request.getURI().getPort();
        String reqUri = (scheme == null ? "" : scheme) + "://" + request.getURI().getHost() +
                (port == -1 ? "" : ":" + port);
        if (origin == null) {
            return deny(response, "Origin required");
        }
        if (!OriginMatcher.matches(origin, reqUri)) {
            return deny(response, "Origin mismatch");
        }

        attributes.put(ATTR_ACTOR, actor);
        attributes.put(ATTR_SESSION_ID, sessionId);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        if (exception != null && LOG.isDebugEnabled()) {
            LOG.debug("ws handshake exception", exception);
        }
    }

    private static ActorId resolveActor() {
        SecurityContext ctx = SecurityContextHolder.getContext();
        if (ctx == null) {
            throw NotAuthenticatedException.becauseSessionMissing();
        }
        var auth = ctx.getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof ActorPrincipal principal)) {
            throw NotAuthenticatedException.becauseSessionMissing();
        }
        return principal.actorId();
    }

    private static boolean deny(ServerHttpResponse response, String logReason) {
        response.setStatusCode(HttpStatus.FORBIDDEN);
        LOG.debug("ws handshake rejected: {}", logReason);
        return false;
    }

    /** path = {@code /ws/visual-sessions/{sessionId}} 末尾即 sessionId。 */
    static String extractSessionIdFromPath(String uri) {
        if (uri == null) {
            return null;
        }
        int idx = uri.lastIndexOf('/');
        if (idx < 0 || idx == uri.length() - 1) {
            return null;
        }
        String segment = uri.substring(idx + 1);
        // 不允许路径穿越字符
        if (segment.contains("..") || segment.contains("/") || segment.contains("\\")) {
            return null;
        }
        return segment;
    }

    /**
     * 单值 query 参数解析：禁止重复（任一参数出现 2 次或缺少 csrfToken 直接 false）。
     */
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

    /** 把 Cookie list 转成 Map（仅用于诊断；正常请求走 findCookieValue） */
    static Map<String, String> cookiesOf(HttpServletRequest http) {
        Map<String, String> map = new LinkedHashMap<>();
        Cookie[] cookies = http.getCookies();
        if (cookies == null) {
            return map;
        }
        for (Cookie cookie : cookies) {
            map.put(cookie.getName(), cookie.getValue());
        }
        return map;
    }

    /** 检查存在 HttpSession，但本产品 session 仅校验身份与 CSRF；保留供未来切到 Spring Session 使用。 */
    @SuppressWarnings("unused")
    private static boolean hasSessionId(HttpServletRequest http) {
        HttpSession session = http.getSession(false);
        return session != null;
    }
}
