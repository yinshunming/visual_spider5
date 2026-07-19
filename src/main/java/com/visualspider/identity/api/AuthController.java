package com.visualspider.identity.api;

import com.visualspider.identity.internal.SessionAuthenticationService;
import com.visualspider.identity.spi.Authentication;
import com.visualspider.identity.spi.IdentityAccess;
import com.visualspider.shared.security.ActorAuthentication;
import com.visualspider.shared.security.ActorPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证 REST 端点：登录 / 登出 / 当前登录状态。
 *
 * <p>错误响应（{@code AuthenticationFailedException} 等）由 {@code shared.GlobalExceptionHandler}
 * 统一映射到 {@code ApiError}。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final Authentication authentication;
    private final IdentityAccess identityAccess;
    private final SessionAuthenticationService sessionAuth;

    public AuthController(
            Authentication authentication,
            IdentityAccess identityAccess,
            SessionAuthenticationService sessionAuth) {
        this.authentication = authentication;
        this.identityAccess = identityAccess;
        this.sessionAuth = sessionAuth;
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest) {
        char[] pwd = request.password().toCharArray();
        authentication.login(request.username(), pwd);
        sessionAuth.persistToSession(httpRequest);
        return ResponseEntity.ok(new LoginResponse(
                identityAccess.currentActor().value(),
                request.username()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest httpRequest) {
        var actor = currentActorOrNull();
        authentication.logout(actor);
        sessionAuth.invalidateCurrentSession(httpRequest);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }

    @GetMapping("/login-status")
    public ResponseEntity<LoginStatusResponse> loginStatus() {
        var principalOpt = currentPrincipalOrNull();
        if (principalOpt.isEmpty()) {
            return ResponseEntity.ok(LoginStatusResponse.anonymous());
        }
        ActorPrincipal p = principalOpt.get();
        return ResponseEntity.ok(new LoginStatusResponse(
                true, p.actorId().value(), p.username(), p.isAdmin() ? "ADMIN" : "COLLECTOR"));
    }

    private static java.util.Optional<ActorPrincipal> currentPrincipalOrNull() {
        SecurityContext context = SecurityContextHolder.getContext();
        if (context == null) {
            return java.util.Optional.empty();
        }
        var auth = context.getAuthentication();
        if (auth instanceof ActorAuthentication a) {
            return java.util.Optional.of(a.actorPrincipal());
        }
        return java.util.Optional.empty();
    }

    private static com.visualspider.identity.domain.ActorId currentActorOrNull() {
        return currentPrincipalOrNull().map(ActorPrincipal::actorId).orElse(null);
    }
}
