package com.visualspider.visualbrowser.internal;

import com.visualspider.identity.domain.ActorId;
import com.visualspider.task.spi.TaskCatalog;
import com.visualspider.visualbrowser.SsrfRouteGuard;
import com.visualspider.visualbrowser.VisualSession;
import com.visualspider.visualbrowser.spi.TargetUrlPolicy;
import org.springframework.stereotype.Component;

/**
 * 为配置会话实例化旧 spike {@link VisualSession} 的工厂（M2-1 #17 / M2-3 #19 / M6-1）。
 *
 * <p>读 {@link TaskCatalog#read} 得到 task definition.startUrl，再走 {@link TargetUrlPolicy}
 * 校验后构造旧 {@code VisualSession}；URL 不合法抛出对应业务异常。
 * 由 {@code DefaultVisualSessionManager.open} 与 WS handler 复用同一 legacy 实例。
 *
 * <p>M6-1：构造时把 {@link SsrfRouteGuard} 绑到 BrowserContext，配置会话的所有请求
 * 走 SSRF 校验（默认拦截回环 / 私有 / 元数据；{@code it} / {@code dev} profile 可豁免
 * 回环用于本地 fixture）。
 */
@Component
public class LegacySessionFactory {

    private final TaskCatalog taskCatalog;
    private final TargetUrlPolicy targetUrlPolicy;
    private final SsrfRouteGuard ssrfRouteGuard;

    public LegacySessionFactory(TaskCatalog taskCatalog,
                                TargetUrlPolicy targetUrlPolicy,
                                SsrfRouteGuard ssrfRouteGuard) {
        if (taskCatalog == null) {
            throw new IllegalArgumentException("taskCatalog 不能为空");
        }
        if (targetUrlPolicy == null) {
            throw new IllegalArgumentException("targetUrlPolicy 不能为空");
        }
        if (ssrfRouteGuard == null) {
            throw new IllegalArgumentException("ssrfRouteGuard 不能为空");
        }
        this.taskCatalog = taskCatalog;
        this.targetUrlPolicy = targetUrlPolicy;
        this.ssrfRouteGuard = ssrfRouteGuard;
    }

    public VisualSession create(ActorId actor, long taskId, String sessionId) {
        var draft = taskCatalog.read(taskId, actor);
        String startUrl = draft.definition().startUrl();
        if (startUrl != null && !startUrl.isBlank()) {
            targetUrlPolicy.validate(startUrl);
        }
        String resolved = startUrl == null || startUrl.isBlank() ? "http://localhost/" : startUrl;
        return new VisualSession(sessionId, resolved, ssrfRouteGuard::install);
    }
}