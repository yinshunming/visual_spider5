package com.visualspider.task.internal;

import com.visualspider.identity.domain.ActorId;
import com.visualspider.identity.spi.IdentityAccess;
import com.visualspider.task.domain.ReadinessReport;
import com.visualspider.task.domain.TaskDefinition;
import com.visualspider.task.domain.TaskDraft;
import com.visualspider.task.domain.TaskStatus;
import com.visualspider.task.domain.TaskSummary;
import com.visualspider.task.domain.exceptions.StaleTaskVersionException;
import com.visualspider.task.domain.exceptions.TaskInvalidDefinitionException;
import com.visualspider.task.domain.exceptions.TaskNotFoundException;
import com.visualspider.task.spi.TaskCatalog;
import com.visualspider.task.spi.TaskReadiness;
import com.visualspider.task.spi.TaskRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

/**
 * {@link TaskCatalog} 默认实现。
 *
 * <p>所有 query 携带所有权约束：admin 通过 {@link IdentityAccess#canAccessTask} 短路；
 * collector 仅访问 owner_id 匹配的任务。
 */
@Service
public class TaskCatalogImpl implements TaskCatalog {

    private static final Logger LOG = LoggerFactory.getLogger(TaskCatalogImpl.class);

    private final TaskRepository repository;
    private final IdentityAccess identityAccess;
    private final TaskReadiness readiness;
    /** M5 spec §D3：reader 兜底 + writer 统一走 upgrader（V2 定义静默升 V3 再校验/持久化）。 */
    private final TaskSchemaUpgrader schemaUpgrader;

    public TaskCatalogImpl(TaskRepository repository, IdentityAccess identityAccess,
                           TaskReadiness readiness, TaskSchemaUpgrader schemaUpgrader) {
        this.repository = repository;
        this.identityAccess = identityAccess;
        this.readiness = readiness;
        this.schemaUpgrader = schemaUpgrader;
    }

    @Override
    public long createDraft(TaskDefinition draft, String name, ActorId actor) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("任务名不能为空");
        }
        TaskDefinition upgraded = schemaUpgrader.upgradeIfNeeded(draft);
        ReadinessReport report = readiness.validate(upgraded);
        if (!report.ready()) {
            throw new TaskInvalidDefinitionException(report.errors());
        }
        long id = repository.insert(actor.value(), name, upgraded);
        LOG.info("createDraft: id={} ownerId={} name={}", id, actor.value(), name);
        return id;
    }

    @Override
    public List<TaskSummary> listMine(ActorId actor) {
        if (identityAccess.canRunAnyTask()) {
            // admin：列出全部
            return repository.listByOwner(null);
        }
        return repository.listByOwner(actor.value());
    }

    @Override
    public TaskDraft read(long taskId, ActorId actor) {
        TaskDraft draft = repository.findById(taskId).orElseThrow(() -> new TaskNotFoundException(taskId));
        if (!identityAccess.canAccessTask(draft.ownerId(), actor)) {
            throw new AccessDeniedException("无权访问该任务");
        }
        // M5 spec §D3：reader 兜底——启动 hook 未跑/未命中时，V2 任务在内存升 V3 再返回。
        return schemaUpgrader.upgradeIfNeeded(draft);
    }

    @Override
    public TaskDraft saveDraft(long taskId, TaskDefinition draft, long expectedVersion, ActorId actor) {
        TaskDraft existing = repository.findById(taskId).orElseThrow(() -> new TaskNotFoundException(taskId));
        if (!identityAccess.canAccessTask(existing.ownerId(), actor)) {
            throw new AccessDeniedException("无权访问该任务");
        }
        if (existing.version() != expectedVersion) {
            throw new StaleTaskVersionException(taskId, expectedVersion, existing.version());
        }
        if (existing.status() != TaskStatus.DRAFT) {
            throw new IllegalStateException("非 DRAFT 状态不允许编辑");
        }
        TaskDefinition upgraded = schemaUpgrader.upgradeIfNeeded(draft);
        ReadinessReport report = readiness.validate(upgraded);
        if (!report.ready()) {
            throw new TaskInvalidDefinitionException(report.errors());
        }
        boolean updated = repository.updateDraft(taskId, upgraded, expectedVersion);
        if (!updated) {
            // 并发场景：UPDATE 0 行（版本已被另一线程修改）
            TaskDraft now = repository.findById(taskId).orElseThrow(() -> new TaskNotFoundException(taskId));
            throw new StaleTaskVersionException(taskId, expectedVersion, now.version());
        }
        TaskDraft updated2 = repository.findById(taskId).orElseThrow(() -> new TaskNotFoundException(taskId));
        // 校验通过 → 状态推进 DRAFT → READY（spec §D2 / §D10）。
        // M4-7 (#37) 发现：之前只有校验从未写 status=READY，task 永远卡 DRAFT，RunCoordinator 拒起 run。
        boolean markedReady = repository.markReady(taskId, updated2.version());
        if (!markedReady) {
            TaskDraft now = repository.findById(taskId).orElseThrow(() -> new TaskNotFoundException(taskId));
            throw new StaleTaskVersionException(taskId, updated2.version(), now.version());
        }
        TaskDraft ready = repository.findById(taskId).orElseThrow(() -> new TaskNotFoundException(taskId));
        LOG.info("saveDraft: id={} newVersion={} status={}", taskId, ready.version(), ready.status());
        return ready;
    }

    @Override
    public void delete(long taskId, ActorId actor) {
        TaskDraft existing = repository.findById(taskId).orElseThrow(() -> new TaskNotFoundException(taskId));
        if (!identityAccess.canAccessTask(existing.ownerId(), actor)) {
            throw new AccessDeniedException("无权访问该任务");
        }
        if (identityAccess.canRunAnyTask()) {
            // admin：直接删
            boolean ok = repository.deleteById(taskId, existing.ownerId());
            if (!ok) {
                throw new IllegalStateException("delete 影响 0 行");
            }
        } else {
            // collector：WHERE owner_id 校验
            boolean ok = repository.deleteById(taskId, actor.value());
            if (!ok) {
                throw new IllegalStateException("delete 影响 0 行");
            }
        }
        LOG.info("delete: id={} actor={}", taskId, actor.value());
    }
}
