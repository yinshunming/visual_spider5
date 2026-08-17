package com.visualspider.visualbrowser.spi;

import com.visualspider.identity.domain.ActorId;
import java.time.Instant;

/**
 * 配置会话 SPI：不变量元数据快照（spec §D2）。
 *
 * <p>会话状态变化通过 {@link #lifecycle()} 字段读取最新副本，不修改现有实例；
 * 运行时 Playwright 对象、线程与浏览器 lane 由 {@link Lease} 隐藏，
 * 不暴露到 SPI。
 */
public record VisualSession(
        String sessionId,
        long taskId,
        ActorId owner,
        Instant openedAt,
        Instant lastActivityAt,
        SessionLifecycleState lifecycle) {

    public VisualSession {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }
        if (taskId <= 0) {
            throw new IllegalArgumentException("taskId 必须 > 0");
        }
        if (owner == null) {
            throw new IllegalArgumentException("owner 不能为空");
        }
        if (openedAt == null) {
            throw new IllegalArgumentException("openedAt 不能为空");
        }
        if (lastActivityAt == null) {
            throw new IllegalArgumentException("lastActivityAt 不能为空");
        }
        if (lifecycle == null) {
            throw new IllegalArgumentException("lifecycle 不能为空");
        }
    }

    /** 返回该会话的"复制 + 新 lastActivityAt"。 */
    public VisualSession withActivity(Instant now) {
        return new VisualSession(sessionId, taskId, owner, openedAt, now, lifecycle);
    }

    /** 返回该会话的"复制 + 新 lifecycle"。 */
    public VisualSession withLifecycle(SessionLifecycleState next) {
        return new VisualSession(sessionId, taskId, owner, openedAt, lastActivityAt, next);
    }
}
