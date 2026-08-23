package com.visualspider.result.api;

import com.visualspider.identity.spi.IdentityAccess;
import com.visualspider.result.internal.RetentionCleanupTask;
import com.visualspider.result.internal.SystemSettingRepository;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 系统设置管理 REST 端点（M6-6 / docs/specs/m6.md §D14）。
 *
 * <p>仅暴露 {@code retention.days} 一个 key（其余 6 处预留点留 M7）。
 * admin 角色可 GET / PUT；其他角色 403；未知 key 404。
 *
 * <p>PUT 仅校验 + 写库 + 返回，不触发即时清理（下次调度生效）。
 */
@RestController
@RequestMapping("/api/admin/settings")
public class AdminSettingController {

    private final IdentityAccess identityAccess;
    private final SystemSettingRepository repository;

    public AdminSettingController(IdentityAccess identityAccess, SystemSettingRepository repository) {
        this.identityAccess = identityAccess;
        this.repository = repository;
    }

    @GetMapping("/retention.days")
    public ResponseEntity<Map<String, Object>> getRetentionDays() {
        requireAdmin();
        int current = repository.findInt(RetentionCleanupTask.SETTING_KEY)
                .orElse(RetentionCleanupTask.DEFAULT_RETENTION_DAYS);
        return ResponseEntity.ok(Map.of(
                "key", RetentionCleanupTask.SETTING_KEY,
                "value", current));
    }

    @PutMapping("/retention.days")
    public ResponseEntity<Map<String, Object>> putRetentionDays(@RequestBody RetentionDaysRequest req) {
        requireAdmin();
        if (req == null || req.value() == null) {
            throw new IllegalArgumentException("value 不能为空");
        }
        int v = req.value();
        if (v < RetentionCleanupTask.MIN_DAYS || v > RetentionCleanupTask.MAX_DAYS) {
            throw new IllegalArgumentException(
                    "value 必须在 [" + RetentionCleanupTask.MIN_DAYS + ","
                            + RetentionCleanupTask.MAX_DAYS + "] 范围内");
        }
        repository.upsert(RetentionCleanupTask.SETTING_KEY, String.valueOf(v));
        return ResponseEntity.ok(Map.of(
                "key", RetentionCleanupTask.SETTING_KEY,
                "value", v));
    }

    private void requireAdmin() {
        identityAccess.currentActor();  // 触发未认证异常
        if (!identityAccess.isAdmin()) {
            throw new AccessDeniedException("需要 admin 角色");
        }
    }

    public record RetentionDaysRequest(Integer value) {}
}
