package com.visualspider.run.internal;

import com.visualspider.run.spi.Page;
import com.visualspider.run.spi.RunDetail;
import com.visualspider.run.spi.RunFilter;
import com.visualspider.run.spi.RunProgress;
import com.visualspider.run.spi.RunState;
import com.visualspider.run.spi.RunSummary;
import com.visualspider.run.spi.StopReason;
import com.visualspider.task.domain.TaskSnapshot;
import java.util.List;
import java.util.Optional;

/**
 * collection_run 表数据访问 SPI（M3 spec §D5）。
 *
 * <p>封装所有 collection_run SQL；上层 {@code RunCoordinator} / {@code RunDispatcher}
 * 不直接接触 JDBC。
 *
 * <p>所有查询默认带 {@code owner_id}（admin 全局见实现）；M3-2 仅放最窄接口，
 * M3-3 {@code result} 模块复用同形态。
 */
public interface RunRepository {

    /**
     * 启动事务内：count-check + insert WAITING run 记录。
     *
     * <p>实现必须是单条 SQL（{@code SELECT count(*) ... WHERE owner_id=? AND status IN (...) }）
     * + 同事务 {@code INSERT}。调用方必须外层持 in-JVM 锁。
     *
     * @return 当前用户已有的 WAITING+RUNNING 数量
     */
    int countActiveByOwner(long ownerId);

    /**
     * 插入一条 WAITING run 记录，绑定 snapshot。
     *
     * @return 新 run id
     */
    long insertWaiting(long taskId, long ownerId, TaskSnapshot snapshot);

    /** 按 id 查询 run + snapshot；返回空表示不存在。 */
    Optional<RunRecord> findById(long runId);

    /**
     * 取最旧一条 WAITING run 并 CAS 翻 RUNNING。
     *
     * @return CAS 命中返回新值（status=RUNNING, started_at set）；未命中返回 {@link Optional#empty()}
     */
    Optional<RunRecord> claimOldestWaiting();

    /** 写入 cancel_requested + 终态（cancel 协作式；执行器读取后写终态）。 */
    boolean markCancelRequested(long runId);

    /**
     * 排队中取消（spec §D10 迁移图 WAITING -> CANCELLED）：原子翻 status=CANCELLED +
     * stop_reason=USER_CANCEL + finished_at=now()。仅当 status='WAITING' 时命中，返回影响行数。
     */
    int markCancelledIfWaiting(long runId);

    /** 写终态：status + stop_reason + finished_at + 计数（执行器在收尾调用）。 */
    boolean markTerminal(long runId, RunState status, StopReason stopReason);

    /** 启动恢复：把所有 WAITING + RUNNING 翻 INTERRUPTED + APP_INTERRUPTED；返回影响行数。 */
    int markAllActiveInterrupted();

    /** 列出当前用户可见的 run（admin 全局；collector 仅 owner）。 */
    List<RunSummary> listByOwner(Long ownerId, RunFilter filter);

    /** 分页封装：list + count。 */
    Page<RunSummary> pageByOwner(Long ownerId, RunFilter filter);

    /** 取运行最新进度（status + counts + current_url + stage）。 */
    Optional<RunProgress> loadProgress(long runId);

    /** 取详情（含 snapshot meta + counts + status）。 */
    Optional<RunDetail> loadDetail(long runId);

    /**
     * 数据访问 record：内部使用，避免在接口签名上反复罗列字段。
     * 上层 SPI 见 {@link RunSummary} / {@link RunDetail}。
     */
    record RunRecord(
            long runId,
            long taskId,
            long ownerId,
            RunState status,
            StopReason stopReason,
            boolean cancelRequested,
            int pageCount,
            int recordCountFinal,
            int failCount,
            TaskSnapshot snapshot,
            java.time.OffsetDateTime createdAt,
            java.time.OffsetDateTime startedAt,
            java.time.OffsetDateTime finishedAt) {
    }
}