package com.visualspider.visualbrowser.internal;

import com.visualspider.visualbrowser.InputCommand;
import com.visualspider.visualbrowser.InputSequencer;
import com.visualspider.visualbrowser.PlaywrightControl;
import com.visualspider.visualbrowser.ViewportMapper;
import java.util.concurrent.CompletableFuture;

/**
 * 浏览模式处理器（M2-2 #18）：在 lane 线程上发出真实输入。
 *
 * <p>与 spike {@code VisualSession.handle} 同语义：坐标按远程视口换算、序号单调守卫、
 * 越界 / 过期 / 非法参数一律拒绝。
 */
public final class BrowseModeHandler {

    private final PlaywrightControl control;
    private final InputSequencer sequencer;

    public BrowseModeHandler(PlaywrightControl control, InputSequencer sequencer) {
        this.control = control;
        this.sequencer = sequencer;
    }

    public CompletableFuture<Boolean> handle(InputCommand command) {
        if (!sequencer.accept(command.sequence())) {
            return CompletableFuture.completedFuture(false);
        }
        switch (command.type()) {
            case InputCommand.TYPE_CLICK: {
                int[] mapped = ViewportMapper.toRemote(
                        command.x() == null ? -1 : command.x(),
                        command.y() == null ? -1 : command.y(),
                        command.clientWidth(), command.clientHeight());
                if (mapped == null) {
                    return CompletableFuture.completedFuture(false);
                }
                return control.click(mapped[0], mapped[1]).thenApply(v -> true);
            }
            case InputCommand.TYPE_WHEEL: {
                if (command.deltaX() == null || command.deltaY() == null) {
                    return CompletableFuture.completedFuture(false);
                }
                return control.wheel(command.deltaX(), command.deltaY()).thenApply(v -> true);
            }
            case InputCommand.TYPE_KEY: {
                if (command.key() == null) {
                    return CompletableFuture.completedFuture(false);
                }
                return control.type(command.key()).thenApply(v -> true);
            }
            case InputCommand.TYPE_NAVIGATE: {
                if (command.url() == null) {
                    return CompletableFuture.completedFuture(false);
                }
                return control.navigate(command.url()).thenApply(v -> true);
            }
            case InputCommand.TYPE_BACK:
                return control.goBack().thenApply(v -> true);
            case InputCommand.TYPE_FORWARD:
                return control.goForward().thenApply(v -> true);
            case InputCommand.TYPE_RELOAD:
                return control.reload().thenApply(v -> true);
            default:
                return CompletableFuture.completedFuture(false);
        }
    }
}
