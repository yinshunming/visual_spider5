package com.visualspider.run.spi;

import com.visualspider.identity.domain.ActorId;

/**
 * 运行协调 SPI（M3 spec §D2 / ADR-0006）。
 *
 * <p>start / cancel / get / list / progress。
 * 所有权规则：admin 全局；collector 仅自己。
 */
public interface RunCoordinator {

    /**
     * 从 READY 任务创建运行（WAITING）；同事务生成快照 + 并发检查。
     *
     * @throws com.visualspider.run.internal.TaskNotReadyException 任务未通过校验
     * @throws com.visualspider.run.internal.UserRunLimitException 同用户已有 W+R
     * @throws org.springframework.security.access.AccessDeniedException 非 owner / 非 admin
     */
    RunSummary start(long taskId, ActorId actor);

    /**
     * 协作式取消；终态运行不可取消。
     *
     * @throws com.visualspider.run.internal.RunNotFoundException 运行不存在 / 无权
     * @throws com.visualspider.run.internal.RunNotCancellableException 终态 run
     */
    void cancel(long runId, ActorId actor);

    /** 详情：状态/计数/阶段/当前URL/快照元数据。 */
    RunDetail get(long runId, ActorId actor);

    /** 分页列表（owner 范围；admin 全局）。 */
    Page<RunSummary> list(ActorId actor, RunFilter filter);

    /** WS 握手后下发当前进度快照。 */
    RunProgress progress(long runId, ActorId actor);
}