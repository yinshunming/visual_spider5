package com.visualspider.task.spi;

import com.visualspider.identity.domain.ActorId;
import com.visualspider.task.domain.TaskDefinition;
import com.visualspider.task.domain.TaskDraft;
import com.visualspider.task.domain.TaskSummary;
import com.visualspider.task.domain.exceptions.StaleTaskVersionException;
import com.visualspider.task.domain.exceptions.TaskNotFoundException;
import java.util.List;
import org.springframework.security.access.AccessDeniedException;

/**
 * 任务目录 SPI。CRUD + 乐观锁（spec §D4）。
 *
 * <p>所有权规则：admin 通过；collector 仅访问 owner_id 匹配的任务。
 */
public interface TaskCatalog {

    /**
     * 创建草稿。
     *
     * @param draft 任务定义（M1 暂不消费 task.ready / fields 完整性，仅记账）
     * @param name 任务名（M1 唯一性不强制）
     * @param actor 调用者
     * @return 新任务 id
     */
    long createDraft(TaskDefinition draft, String name, ActorId actor);

    /**
     * 列出当前用户可见的任务（M1：admin 全部；collector 仅 owner）。
     */
    List<TaskSummary> listMine(ActorId actor);

    /**
     * 读取完整任务；admin 通过；owner 通过；其它抛 {@link AccessDeniedException}；不存在抛 {@link TaskNotFoundException}。
     */
    TaskDraft read(long taskId, ActorId actor);

    /**
     * 保存草稿（乐观锁）。
     *
     * @param expectedVersion 当前客户端持有的版本号；不一致抛 {@link StaleTaskVersionException}
     */
    TaskDraft saveDraft(long taskId, TaskDefinition draft, long expectedVersion, ActorId actor);

    /**
     * 删除任务；admin 通过；owner 通过；其它抛 {@link AccessDeniedException}。
     */
    void delete(long taskId, ActorId actor);
}
