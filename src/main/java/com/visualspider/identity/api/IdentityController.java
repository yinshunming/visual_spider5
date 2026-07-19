package com.visualspider.identity.api;

import com.visualspider.identity.domain.exceptions.NotAuthenticatedException;
import com.visualspider.identity.spi.IdentityAccess;
import com.visualspider.shared.security.ActorAuthentication;
import com.visualspider.shared.security.ActorPrincipal;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 当前登录用户身份端点。
 *
 * <p>{@code GET /api/identity/me} 返回 actorId/username/role；
 * 未登录抛 {@link NotAuthenticatedException}（401）。
 */
@RestController
@RequestMapping("/api/identity")
public class IdentityController {

    private final IdentityAccess identityAccess;

    public IdentityController(IdentityAccess identityAccess) {
        this.identityAccess = identityAccess;
    }

    @GetMapping("/me")
    public ResponseEntity<MeResponse> me() {
        // IdentityAccess.currentActor() 在未认证时抛 NotAuthenticatedException
        var actorId = identityAccess.currentActor();
        ActorPrincipal principal = currentPrincipal();
        return ResponseEntity.ok(MeResponse.of(
                actorId.value(),
                principal.username(),
                principal.role()));
    }

    private static ActorPrincipal currentPrincipal() {
        SecurityContext context = SecurityContextHolder.getContext();
        Authentication auth = context == null ? null : context.getAuthentication();
        if (!(auth instanceof ActorAuthentication actorAuth)) {
            throw NotAuthenticatedException.becauseSessionMissing();
        }
        return actorAuth.actorPrincipal();
    }
}
