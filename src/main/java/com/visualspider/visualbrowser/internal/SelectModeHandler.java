package com.visualspider.visualbrowser.internal;

import com.visualspider.visualbrowser.PlaywrightControl;
import com.visualspider.visualbrowser.SelectionRecord;
import com.visualspider.visualbrowser.ValidationResult;
import com.visualspider.visualbrowser.ViewportMapper;
import java.util.concurrent.CompletableFuture;

/**
 * 选择模式处理器（M2-2 #18）：
 *
 * <ul>
 *   <li>select：按坐标用 {@code document.elementFromPoint} 重新查询 DOM，返回
 *       {@link SelectionRecord}；不保存 {@code ElementHandle}，不触发原页面点击。</li>
 *   <li>validate：参数化校验 {@code querySelectorAll} / {@code document.evaluate}。</li>
 * </ul>
 */
public final class SelectModeHandler {

    private final PlaywrightControl control;

    public SelectModeHandler(PlaywrightControl control) {
        this.control = control;
    }

    public CompletableFuture<SelectionRecord> inspect(int x, int y, int clientWidth, int clientHeight) {
        int[] mapped = ViewportMapper.toRemote(x, y, clientWidth, clientHeight);
        if (mapped == null) {
            return CompletableFuture.completedFuture(null);
        }
        return control.inspectElement(mapped[0], mapped[1]);
    }

    public CompletableFuture<ValidationResult> validate(String selector, String type) {
        if (selector == null || type == null) {
            CompletableFuture<ValidationResult> cf = new CompletableFuture<>();
            cf.complete(new ValidationResult(false, 0, "selector 与 type 必填", java.util.List.of()));
            return cf;
        }
        return control.validateSelector(selector, type);
    }
}
