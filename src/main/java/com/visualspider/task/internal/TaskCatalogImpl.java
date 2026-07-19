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

    public TaskCatalogImpl(TaskRepository repository, IdentityAccess identityAccess, TaskReadiness readiness) {
        this.repository = repository;
        this.identityAccess = identityAccess;
        this.readiness = readiness;
    }

    @Override
    public long createDraft(TaskDefinition draft, String name, ActorId actor) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("任务名不能为空");
        }
        ReadinessReport report = readiness.validate(draft);
        if (!report.ready()) {
            throw new TaskInvalidDefinitionException(report.errors());
        }
        long id = repository.insert(actor.value(), name, draft);
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
        return draft;
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
        ReadinessReport report = readiness.validate(draft);
        if (!report.ready()) {
            throw new TaskInvalidDefinitionException(report.errors());
        }
        boolean updated = repository.updateDraft(taskId, draft, expectedVersion);
        if (!updated) {
            // 并发场景：UPDATE 0 行（版本已被另一线程修改）
            TaskDraft now = repository.findById(taskId).orElseThrow(() -> new TaskNotFoundException(taskId));
            throw new StaleTaskVersionException(taskId, expectedVersion, now.version());
        }
        TaskDraft updated2 = repository.findById(taskId).orElseThrow(() -> new TaskNotFoundException(taskId));
        LOG.info("saveDraft: id={} newVersion={}", taskId, updated2.version());
        return updated2;
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
