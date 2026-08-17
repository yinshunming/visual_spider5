package com.visualspider.task.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.visualspider.identity.domain.ActorId;
import com.visualspider.identity.spi.IdentityAccess;
import com.visualspider.task.domain.TaskDraft;
import com.visualspider.task.domain.TaskSnapshot;
import com.visualspider.task.spi.TaskCatalog;
import com.visualspider.task.spi.TaskSnapshotFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

/**
 * {@link TaskSnapshotFactory} M3 真实实现（spec §D4）。
 *
 * <p>读取 task 当前定义（带 owner/admin 所有权校验），构造不可变
 * {@link TaskSnapshot}（含完整 {@link com.visualspider.task.domain.TaskDefinition} + version）。
 *
 * <p>不写库；运行创建事务由 {@code RunCoordinator.start} 在同事务把 snapshot
 * 序列化为 JSONB 存入 {@code collection_run.snapshot}（M3 spec §D4）。
 *
 * <p>运行期间编辑原任务只改 {@code collection_task}，不动 {@code collection_run.snapshot}；
 * 当前/历史运行不受影响。
 */
@Service
public class JdbcTaskSnapshotFactory implements TaskSnapshotFactory {

    private final TaskCatalog catalog;
    private final IdentityAccess identityAccess;
    // 保留 ObjectMapper 以便未来扩展（如深拷贝 / 规范化）。当前实现：record 不可变，直接复用引用。
    @SuppressWarnings("unused")
    private final ObjectMapper objectMapper;

    public JdbcTaskSnapshotFactory(TaskCatalog catalog, IdentityAccess identityAccess, ObjectMapper objectMapper) {
        this.catalog = catalog;
        this.identityAccess = identityAccess;
        this.objectMapper = objectMapper;
    }

    @Override
    public TaskSnapshot snapshot(long taskId, ActorId actor) {
        TaskDraft draft = catalog.read(taskId, actor); // 不存在 → TaskNotFoundException；非 owner → AccessDeniedException
        if (!identityAccess.canAccessTask(draft.ownerId(), actor)) {
            throw new AccessDeniedException("无权访问该任务");
        }
        return new TaskSnapshot(
                draft.id(),
                draft.ownerId(),
                draft.name(),
                draft.mode(),
                draft.schemaVersion(),
                draft.version(),
                draft.definition());
    }
}