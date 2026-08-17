package com.visualspider.run.internal.testutil;

import com.visualspider.extraction.spi.ExtractionPreview;
import com.visualspider.extraction.spi.PreviewResult;
import com.visualspider.task.domain.TaskDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 假 {@link ExtractionPreview}：记录每一次 preview 调用，并按调用顺序返回预设结果（M3-3 #25）。
 *
 * <p>默认返回"成功 + 给定 cleanedValue 映射"。需要模拟"提取异常 → 重试"时，可在某次调用
 * 之前通过 {@link #queueThrowable(Throwable)} 入栈一个异常，下一次 preview 会抛。
 */
public class TestExtractionPreview implements ExtractionPreview {

    private final List<PreviewResult> queuedResults = new ArrayList<>();
    private final List<Throwable> queuedThrows = new ArrayList<>();
    private final List<TaskDefinition> callsDefinition = new ArrayList<>();
    private final List<DomState> callsDomState = new ArrayList<>();

    /** 入栈一个结果（按调用顺序消费）。 */
    public void queueResult(PreviewResult result) {
        queuedResults.add(result);
    }

    /** 入栈一个需要抛出的异常（在最近排队的 result 之前先消费异常）。 */
    public void queueThrowable(Throwable t) {
        queuedThrows.add(t);
    }

    /** 便捷方法：构造一个 fields=cleanedValue 的成功结果。 */
    public static PreviewResult successResult(Map<String, String> cleanedByField) {
        List<PreviewResult.FieldOutcome> outcomes = new ArrayList<>();
        cleanedByField.forEach((name, cleaned) ->
                outcomes.add(new PreviewResult.FieldOutcome(
                        name, cleaned, cleaned, cleaned == null || cleaned.isBlank())));
        return new PreviewResult(outcomes, List.of());
    }

    public int callCount() {
        return callsDefinition.size();
    }

    public List<TaskDefinition> callsDefinition() {
        return List.copyOf(callsDefinition);
    }

    public List<DomState> callsDomState() {
        return List.copyOf(callsDomState);
    }

    @Override
    public PreviewResult preview(TaskDefinition definition, DomState domState) {
        callsDefinition.add(definition);
        callsDomState.add(domState);
        if (!queuedThrows.isEmpty()) {
            Throwable t = queuedThrows.remove(0);
            if (t instanceof RuntimeException re) {
                throw re;
            }
            if (t instanceof Error err) {
                throw err;
            }
            throw new RuntimeException(t);
        }
        if (queuedResults.isEmpty()) {
            return successResult(Map.of());
        }
        return queuedResults.remove(0);
    }

    @Override
    public ListPreviewResult previewList(TaskDefinition definition, DomState domState, int maxItems) {
        // 测试 stub：M4 测试通常直接验证 preview/previewList 共用诊断；previewList 返回单条预览即可
        var pr = preview(definition, domState);
        return new ListPreviewResult(List.of(pr), 1, pr.diagnostics());
    }
}
