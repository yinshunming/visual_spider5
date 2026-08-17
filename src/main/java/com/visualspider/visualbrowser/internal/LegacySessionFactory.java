package com.visualspider.visualbrowser.internal;

import com.visualspider.identity.domain.ActorId;
import com.visualspider.task.spi.TaskCatalog;
import com.visualspider.visualbrowser.VisualSession;
import com.visualspider.visualbrowser.internal.BasicTargetUrlPolicy;
import org.springframework.stereotype.Component;

/**
 * 为配置会话实例化旧 spike {@link VisualSession} 的工厂（M2-1 #17 / M2-3 #19）。
 *
 * <p>读 {@link TaskCatalog#read} 得到 task definition.startUrl，再走 {@link BasicTargetUrlPolicy}
 * 校验后构造旧 {@code VisualSession}；URL 不合法抛出对应业务异常。
 * 由 {@code DefaultVisualSessionManager.open} 与 WS handler 复用同一 legacy 实例。
 */
@Component
public class LegacySessionFactory {

    private final TaskCatalog taskCatalog;
    private final BasicTargetUrlPolicy targetUrlPolicy;

    public LegacySessionFactory(TaskCatalog taskCatalog, BasicTargetUrlPolicy targetUrlPolicy) {
        this.taskCatalog = taskCatalog;
        this.targetUrlPolicy = targetUrlPolicy;
    }

    public VisualSession create(ActorId actor, long taskId, String sessionId) {
        var draft = taskCatalog.read(taskId, actor);
        String startUrl = draft.definition().startUrl();
        if (startUrl != null && !startUrl.isBlank()) {
            targetUrlPolicy.validate(startUrl);
        }
        String resolved = startUrl == null || startUrl.isBlank() ? "http://localhost/" : startUrl;
        return new VisualSession(sessionId, resolved);
    }
}
