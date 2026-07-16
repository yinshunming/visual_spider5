package com.visualspider.spike.m0;

import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 配置会话管理：创建/关闭会话。M0 spike 不做每用户一会话限制与所有权（M2）。
 */
@Component
public final class VisualSessionManager {
    private final ConcurrentMap<String, VisualSession> sessions = new ConcurrentHashMap<>();

    public VisualSession open(String startUrl) {
        String id = UUID.randomUUID().toString();
        VisualSession session = new VisualSession(id, startUrl);
        sessions.put(id, session);
        return session;
    }

    public VisualSession get(String id) {
        return sessions.get(id);
    }

    public void close(String id) {
        VisualSession removed = sessions.remove(id);
        if (removed != null) {
            removed.close();
        }
    }

    public int size() {
        return sessions.size();
    }
}
