package com.visualspider.visualbrowser.internal;

import com.visualspider.identity.domain.ActorId;
import com.visualspider.visualbrowser.InputCommand;
import com.visualspider.visualbrowser.spi.VisualSessionChannel;
import com.visualspider.visualbrowser.spi.VisualSessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link VisualSessionChannel} 生产实现（M2-1 #17）。
 *
 * <p>每条命令路径重新校验 actor 与 session owner，未知 / 关闭 / 越权一律抛业务异常；
 * 由 {@link com.visualspider.shared.api.GlobalExceptionHandler} 映射到稳定错误码。
 *
 * <p>实际的浏览器操作走 {@code CommandExecutor} 抽象；生产由 M2-1 ws handler 注入到
 * Playwright-backed 实现，单测注入返回布尔结果的 fake。
 */
public class DefaultVisualSessionChannel implements VisualSessionChannel {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultVisualSessionChannel.class);

    private final VisualSessionManager manager;
    private final CommandExecutor executor;

    public DefaultVisualSessionChannel(VisualSessionManager manager, CommandExecutor executor) {
        this.manager = manager;
        this.executor = executor;
    }

    @Override
    public void handleCommand(String sessionId, InputCommand command, ActorId actor) {
        if (sessionId == null || command == null || actor == null) {
            return;
        }
        var owned = manager.requireOwnedBy(sessionId, actor);
        if (!sessionId.equals(command.sessionId())) {
            LOG.debug("command rejected: sessionId mismatch");
            throw new VisualSessionNotOwnerException(sessionId);
        }
        if (owned.lifecycle() == com.visualspider.visualbrowser.spi.SessionLifecycleState.CLOSED) {
            throw new VisualSessionNotFoundException(sessionId);
        }
        boolean accepted;
        try {
            accepted = executor.execute(sessionId, command);
        } catch (RuntimeException ex) {
            LOG.warn("command failed: sessionId={} cmd={}", sessionId, command.type(), ex);
            return;
        }
        if (accepted) {
            manager.heartbeat(sessionId, actor);
        }
    }

    /** 命令执行抽象：返回 true 表示浏览器侧接受。 */
    @FunctionalInterface
    public interface CommandExecutor {
        boolean execute(String sessionId, InputCommand command);
    }
}
