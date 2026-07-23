package com.visualspider.visualbrowser.api;

import com.visualspider.identity.domain.ActorId;
import com.visualspider.identity.spi.IdentityAccess;
import com.visualspider.task.spi.TaskCatalog;
import com.visualspider.visualbrowser.internal.TaskNotOpenableException;
import com.visualspider.visualbrowser.spi.VisualSession;
import com.visualspider.visualbrowser.spi.VisualSessionManager;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 可视会话 REST 边界（M2-1 #17）。
 *
 * <p>仅做参数接收与权限转交；所有权 / 状态 / 容量校验由 {@link VisualSessionManager} 负责。
 * CSRF 由 Spring Security 统一拦截。
 */
@RestController
@RequestMapping("/api/visual-sessions")
public class VisualSessionController {

    private final IdentityAccess identityAccess;
    private final VisualSessionManager manager;
    private final TaskCatalog taskCatalog;

    public VisualSessionController(IdentityAccess identityAccess,
                                   VisualSessionManager manager,
                                   TaskCatalog taskCatalog) {
        this.identityAccess = identityAccess;
        this.manager = manager;
        this.taskCatalog = taskCatalog;
    }

    @PostMapping
    public ResponseEntity<OpenVisualSessionResponse> open(@RequestBody OpenRequest request) {
        ActorId actor = identityAccess.currentActor();
        // 读取 task 并校验所有权 + READY/DRAFT：通过 TaskCatalog.read 内部权限。
        var draft = taskCatalog.read(request.taskId(), actor);
        com.visualspider.task.domain.TaskStatus status = draft.status();
        if (status != com.visualspider.task.domain.TaskStatus.DRAFT
                && status != com.visualspider.task.domain.TaskStatus.READY) {
            throw new TaskNotOpenableException(request.taskId(), status);
        }
        VisualSession session = manager.open(request.taskId(), actor);
        return ResponseEntity.status(HttpStatus.CREATED).body(OpenVisualSessionResponse.from(session));
    }

    @GetMapping("/{sessionId}")
    public OpenVisualSessionResponse get(@PathVariable @NotNull String sessionId) {
        ActorId actor = identityAccess.currentActor();
        VisualSession session = manager.requireOwnedBy(sessionId, actor);
        return OpenVisualSessionResponse.from(session);
    }

    @PostMapping("/{sessionId}/heartbeat")
    public ResponseEntity<Void> heartbeat(@PathVariable @NotNull String sessionId) {
        ActorId actor = identityAccess.currentActor();
        manager.heartbeat(sessionId, actor);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Void> close(@PathVariable @NotNull String sessionId) {
        ActorId actor = identityAccess.currentActor();
        manager.close(sessionId, actor, "USER_CLOSE");
        return ResponseEntity.noContent().build();
    }

    public record OpenRequest(@Positive long taskId) {}
}
