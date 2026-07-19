package com.visualspider.task.api;

import com.visualspider.identity.spi.IdentityAccess;
import com.visualspider.task.domain.TaskDraft;
import com.visualspider.task.domain.TaskSummary;
import com.visualspider.task.spi.TaskCatalog;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 任务 REST 端点（spec §T1 / Acceptance）。
 *
 * <ul>
 *   <li>{@code POST /api/tasks}：创建草稿</li>
 *   <li>{@code GET /api/tasks}：列出我的</li>
 *   <li>{@code GET /api/tasks/{id}}：读取完整</li>
 *   <li>{@code PUT /api/tasks/{id}}：保存草稿（body 含 {@code expectedVersion}）</li>
 *   <li>{@code DELETE /api/tasks/{id}}：删除</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskCatalog catalog;
    private final IdentityAccess identityAccess;

    public TaskController(TaskCatalog catalog, IdentityAccess identityAccess) {
        this.catalog = catalog;
        this.identityAccess = identityAccess;
    }

    @PostMapping
    public ResponseEntity<TaskDraft> create(@Valid @RequestBody CreateTaskRequest request) {
        var actor = identityAccess.currentActor();
        long id = catalog.createDraft(request.definition(), request.name(), actor);
        TaskDraft draft = catalog.read(id, actor);
        return ResponseEntity.created(URI.create("/api/tasks/" + id)).body(draft);
    }

    @GetMapping
    public ResponseEntity<List<TaskSummary>> list() {
        var actor = identityAccess.currentActor();
        return ResponseEntity.ok(catalog.listMine(actor));
    }

    @GetMapping("/{id}")
    public ResponseEntity<TaskDraft> read(@PathVariable("id") long id) {
        return ResponseEntity.ok(catalog.read(id, identityAccess.currentActor()));
    }

    @PutMapping("/{id}")
    public ResponseEntity<TaskDraft> save(
            @PathVariable("id") long id,
            @Valid @RequestBody SaveTaskRequest request) {
        return ResponseEntity.ok(catalog.saveDraft(
                id, request.definition(), request.expectedVersion(), identityAccess.currentActor()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") long id) {
        catalog.delete(id, identityAccess.currentActor());
        return ResponseEntity.noContent().build();
    }
}
