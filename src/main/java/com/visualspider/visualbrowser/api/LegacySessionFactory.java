package com.visualspider.visualbrowser.api;

import com.visualspider.identity.domain.ActorId;
import com.visualspider.task.spi.TaskCatalog;
import com.visualspider.visualbrowser.VisualSession;
import com.visualspider.visualbrowser.internal.BasicTargetUrlPolicy;
import com.visualspider.visualbrowser.spi.VisualSessionManager;
import org.springframework.stereotype.Component;

/**
 * 为新建 WS 连接实例化旧 spike {@link VisualSession} 的工厂（M2-1 #17）。
 *
 * <p>读 {@link TaskCatalog#read} 得到 task definition.startUrl，再走 {@link BasicTargetUrlPolicy}
 * 校验后构造旧 {@code VisualSession}；URL 不合法抛出 {@link com.visualspider.shared.api.BusinessErrorCode}
 * 对应业务异常，由 {@code GlobalExceptionHandler} 翻译。
 */
@Component
public class LegacySessionFactory {

    private final VisualSessionManager manager;
    private final TaskCatalog taskCatalog;
    private final BasicTargetUrlPolicy targetUrlPolicy;

    public LegacySessionFactory(VisualSessionManager manager, TaskCatalog taskCatalog,
                                BasicTargetUrlPolicy targetUrlPolicy) {
        this.manager = manager;
        this.taskCatalog = taskCatalog;
        this.targetUrlPolicy = targetUrlPolicy;
    }

    public VisualSession create(ActorId actor, String sessionId) {
        var meta = manager.findBySessionId(sessionId).orElseThrow();
        long taskId = meta.taskId();
        var draft = taskCatalog.read(taskId, actor);
        String startUrl = draft.definition().startUrl();
        if (startUrl != null && !startUrl.isBlank()) {
            targetUrlPolicy.validate(startUrl);
        }
        String resolved = startUrl == null || startUrl.isBlank() ? "http://localhost/" : startUrl;
        return new VisualSession(sessionId, resolved);
    }
}
