package com.visualspider.identity.api;

import com.visualspider.identity.domain.ActorRole;
import com.visualspider.identity.spi.AccountAdministration;
import com.visualspider.identity.spi.IdentityAccess;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin 账号管理 REST 端点。
 *
 * <p>{@code AccountAdministration} 内部校验 admin 角色；非 admin 调用时抛 {@code AccessDeniedException}（403）。
 */
@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {

    private final AccountAdministration admin;
    private final IdentityAccess identityAccess;

    public AdminUserController(AccountAdministration admin, IdentityAccess identityAccess) {
        this.admin = admin;
        this.identityAccess = identityAccess;
    }

    @PostMapping
    public ResponseEntity<CreateAccountResponse> createAccount(@Valid @RequestBody CreateAccountRequest request) {
        char[] pwd = request.password().toCharArray();
        ActorRole role = "ADMIN".equals(request.role())
                ? new ActorRole.Admin()
                : new ActorRole.Collector();
        long id = admin.createAccount(request.username(), pwd, role, identityAccess.currentActor());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new CreateAccountResponse(id, request.username(), request.role()));
    }

    @PostMapping("/{id}/disable")
    public ResponseEntity<Void> disable(@PathVariable("id") long id) {
        admin.disableAccount(id, identityAccess.currentActor());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/enable")
    public ResponseEntity<Void> enable(@PathVariable("id") long id) {
        admin.enableAccount(id, identityAccess.currentActor());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/reset-password")
    public ResponseEntity<Void> resetPassword(
            @PathVariable("id") long id,
            @Valid @RequestBody ResetPasswordRequest request) {
        char[] pwd = request.password().toCharArray();
        admin.resetPassword(id, pwd, identityAccess.currentActor());
        return ResponseEntity.noContent().build();
    }
}
