package com.visualspider.result.spi;

import java.util.List;

/**
 * 运行结果写入 SPI（M3 spec §D12）。
 *
 * <p>{@link #appendBatch} 同事务追加结果行与事件；M3 单页一次调用写 1 条结果 + 若干事件。
 * 批量接口为 M4/M5 多页/多记录前向兼容，{@code sequenceNo} 由调用方连续分配。
 *
 * <p>本接口写入 {@code run_result} 与 {@code run_event}；不对 {@code collection_run}
 * 的计数字段做加法（计数字段由运行引擎在终态写入时更新，spec §D9）。
 */
public interface RunResultSink {

    /**
     * 追加一批结果行与事件到指定运行。
     *
     * <p>不存在的 run -> {@link RunAccessDeniedException}（视为不存在）。
     * 写入为单事务；任一行 SQL 失败整体回滚。
     *
     * @param runId   目标运行 id
     * @param results 本批结果行（按 {@code sequenceNo} 单调递增；非空时不能含重复 {@code sequenceNo}）
     * @param events  本批事件（可空；非空时 {@code message} 由 {@link RunEventInput} 校验非空）
     */
    void appendBatch(long runId, List<ResultRecord> results, List<RunEventInput> events);
}