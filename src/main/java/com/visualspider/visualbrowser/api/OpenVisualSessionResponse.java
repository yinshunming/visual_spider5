package com.visualspider.visualbrowser.api;

import com.visualspider.visualbrowser.spi.SessionLifecycleState;
import com.visualspider.visualbrowser.spi.VisualSession;
import java.time.Instant;

public record OpenVisualSessionResponse(
        String sessionId,
        long taskId,
        Instant openedAt,
        Instant lastActivityAt,
        SessionLifecycleState lifecycle) {
    public static OpenVisualSessionResponse from(VisualSession session) {
        return new OpenVisualSessionResponse(
                session.sessionId(),
                session.taskId(),
                session.openedAt(),
                session.lastActivityAt(),
                session.lifecycle());
    }
}
