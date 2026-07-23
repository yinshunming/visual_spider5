package com.visualspider.visualbrowser.spi;

import com.visualspider.identity.domain.ActorId;
import java.util.Optional;

/**
 * 配置会话管理 SPI（M2-1 #17 / spec §D2）。
 *
 * <p>每用户每任务最多 1 个会话；同一 task 二次 open 返回现有 sessionId（不创建新会话）。
 * 所有查询 / 命令必须重新校验 actor 与 taskId 所有权，不依赖前端隐藏。
 */
public interface VisualSessionManager {

    /**
     * 打开会话或返回当前用户在该 task 上的现有会话。
     *
     * @throws com.visualspider.shared.api.ApiException-like 包装异常
     *         （由实现层 / 调用方捕获并映射到 {@code BusinessErrorCode}）
     */
    VisualSession open(long taskId, ActorId actor);

    /** 获取当前用户在指定 task 上的会话（不存在返回 {@link Optional#empty()}）。 */
    Optional<VisualSession> find(long taskId, ActorId actor);

    /** 通过 sessionId 查询；actor 不匹配时不抛错（仅返回 empty），命令路径才校验。 */
    Optional<VisualSession> findBySessionId(String sessionId);

    /** 校验会话存在且属于 actor；否则抛 {@code VisualSessionNotFoundException} 或 {@code VisualSessionNotOwnerException}。 */
    VisualSession requireOwnedBy(String sessionId, ActorId actor);

    /** 心跳：空闲计时重置。不存在 / 非所有者不抛错（no-op）。 */
    void heartbeat(String sessionId, ActorId actor);

    /**
     * 关闭会话并归还 lane。
     *
     * @param reason 用于日志/审计，不写到客户端。
     */
    void close(String sessionId, ActorId actor, String reason);

    /** 当前活跃（lifecycle != CLOSED）会话数。 */
    int activeCount();

    /** 供 {@code SessionLifecycleTicker} 同步驱动扫描的快照列表。 */
    java.util.List<com.visualspider.visualbrowser.internal.SessionLifecycleTicker.ActiveSnapshot> snapshotActive();
}
