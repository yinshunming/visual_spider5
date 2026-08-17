package com.visualspider.result.spi;

import com.visualspider.identity.domain.ActorId;

/**
 * 运行结果分页查询 SPI（M3 spec §D12 / D17）。
 *
 * <p>所有权：管理员全局；采集人员仅自己运行。非 owner 且非 admin 抛
 * {@link RunAccessDeniedException}（不回显存在性）。
 *
 * <p>{@link #page} 按 {@code (run_id, sequence_no)} keyset 分页：
 * 计算 {@code startSeq = (page - 1) * size}，以 {@code sequence_no >= startSeq}
 * 作为 keyset 起点；{@code total} 由独立 {@code COUNT(*)} 返回。
 */
public interface RunResultQuery {

    /**
     * 结果分页。
     *
     * @param runId  运行 id
     * @param actor  调用者（admin 全局；其它仅 owner）
     * @param page   1 起始页码（{@code <=0} 视为 1）
     * @param size   每页大小（{@code <=0} 视为 1；{@code >1000} 视为 1000）
     * @return 当前页结果（按 {@code sequence_no} 升序）；总条数 {@code total}
     * @throws RunAccessDeniedException 非 owner 且非 admin
     */
    Page<ResultRecord> page(long runId, ActorId actor, int page, int size);

    /**
     * 运行汇总统计（{@code raw/dedup/final/fail}）。
     *
     * @throws RunAccessDeniedException 非 owner 且非 admin
     */
    RunStats stats(long runId, ActorId actor);
}