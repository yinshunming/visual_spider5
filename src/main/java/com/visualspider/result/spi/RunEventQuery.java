package com.visualspider.result.spi;

import com.visualspider.identity.domain.ActorId;

/**
 * 运行事件分页查询 SPI（M3 spec §D17 / D19）。
 *
 * <p>{@link RunCoordinator} / {@code RunProgressBroadcaster} 不读事件；本接口专供
 * {@code RunController} 暴露的 {@code GET /api/runs/{runId}/events} 端点 +
 * WS 推送的增量事件拉取使用。
 *
 * <p>所有权：管理员全局；采集人员仅自己运行。非 owner 且非 admin 抛
 * {@link RunAccessDeniedException}（不回显存在性）。
 *
 * <p>{@code afterEventId} 用于 WS 增量游标：{@code <=0} 表示从头开始；{@code >0} 表示
 * 仅返回 {@code id > afterEventId} 的行（升序）。{@link #page(runId, actor, page, size)}
 * 与 {@code page}/{@code size} 用于 REST 分页。
 */
public interface RunEventQuery {

    /**
     * REST 模式分页：事件按 {@code id} 升序。
     *
     * @param runId 运行 id
     * @param actor 调用者（admin 全局；其它仅 owner）
     * @param page  1 起始页码（{@code <=0} 视为 1）
     * @param size  每页大小（{@code <=0} 视为 1；{@code >1000} 视为 1000）
     */
    Page<RunEvent> pageEvents(long runId, ActorId actor, int page, int size);

    /**
     * WS 模式增量游标：仅返回 {@code id > afterEventId} 的行；{@code <=0} 视为 {@code 0}，
     * 返回该运行从最早事件起的全部行；用于握手后补齐 WS 断线期间累积事件。
     */
    java.util.List<RunEvent> after(long runId, ActorId actor, long afterEventId);
}
