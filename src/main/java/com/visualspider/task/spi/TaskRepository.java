package com.visualspider.task.spi;

import com.visualspider.task.domain.TaskDefinition;
import com.visualspider.task.domain.TaskDraft;
import com.visualspider.task.domain.TaskSummary;
import java.util.List;
import java.util.Optional;

/**
 * collection_task 表数据访问。
 */
public interface TaskRepository {

    /**
     * 插入草稿，返回新 id。
     *
     * @param ownerId app_user.id
     * @param definition JSONB
     */
    long insert(long ownerId, String name, TaskDefinition definition);

    Optional<TaskDraft> findById(long id);

    /** admin 列出全部；collector 列出 owner_id = ? 的（M1 实现可支持 owner=null 表示 admin 全量）。 */
    List<TaskSummary> listByOwner(Long ownerId);

    /**
     * 乐观锁更新：UPDATE … WHERE id=? AND version=? 命中 0 行 → 返回 false；
     * 命中 1 行 → version+1，返回 true。
     */
    boolean updateDraft(long id, TaskDefinition definition, long expectedVersion);

    /** 删除：WHERE id=? AND owner_id=?（admin 单独路径）。 */
    boolean deleteById(long id, long expectedOwnerId);
}
