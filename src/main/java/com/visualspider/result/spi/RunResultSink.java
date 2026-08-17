package com.visualspider.result.spi;

import java.util.List;

/**
 * 运行结果写入 SPI（M3 spec §D12 / M4 spec §D6）。
 *
 * <p>{@link #appendBatch} 同事务追加结果行与事件，并返回 {@link BatchOutcome}
 * （M4 起 raw/dedup/inserted/failed 四计数）。M3 单页一次调用写 1 条结果 + 若干事件，
 * dedup = 0 / inserted = 1；M4 list 多次调用，单批可能含多条 record + dedup。
 *
 * <p>本接口同时把 {@code collection_run.record_count_raw/dedup/final/fail}
 * 累加（spec §D6 实现路径：sink 一次性 UPDATE，调用方不再单独更新）。
 */
public interface RunResultSink {

    /**
     * 追加一批结果行与事件到指定运行，并累加运行计数。
     *
     * <p>不存在的 run -> {@link RunAccessDeniedException}（视为不存在）。
     *
     * @param runId   目标运行 id
     * @param results 本批结果行（按 {@code sequenceNo} 单调递增；非空时不能含重复 {@code sequenceNo}）
     * @param events  本批事件（可空；非空时 {@code message} 由 {@link RunEventInput} 校验非空）
     * @return 该批次的 raw / dedup / inserted / failed 计数
     */
    BatchOutcome appendBatch(long runId, List<ResultRecord> results, List<RunEventInput> events);
}