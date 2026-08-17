package com.visualspider.run.internal.testutil;

import com.visualspider.result.spi.BatchOutcome;
import com.visualspider.result.spi.ResultRecord;
import com.visualspider.result.spi.RunEventInput;
import com.visualspider.result.spi.RunResultSink;
import java.util.ArrayList;
import java.util.List;

/**
 * 假 {@link RunResultSink}：记录所有 appendBatch 调用的 (runId, results, events)
 * 三元组（M3-3 #25 / M4-4 #34）。
 *
 * <p>默认行为：直接 accept；测试无 sink 副作用要求时不需要额外配置。
 */
public class TestRunResultSink implements RunResultSink {

    public record AppendCall(long runId, List<ResultRecord> results, List<RunEventInput> events) {
    }

    private final List<AppendCall> calls = new ArrayList<>();

    public List<AppendCall> calls() {
        return List.copyOf(calls);
    }

    public int callCount() {
        return calls.size();
    }

    public AppendCall lastCall() {
        if (calls.isEmpty()) {
            return null;
        }
        return calls.get(calls.size() - 1);
    }

    public List<ResultRecord> allResults() {
        List<ResultRecord> all = new ArrayList<>();
        for (AppendCall c : calls) {
            all.addAll(c.results());
        }
        return all;
    }

    public List<RunEventInput> allEvents() {
        List<RunEventInput> all = new ArrayList<>();
        for (AppendCall c : calls) {
            all.addAll(c.events());
        }
        return all;
    }

    @Override
    public BatchOutcome appendBatch(long runId, List<ResultRecord> results, List<RunEventInput> events) {
        int raw = results == null ? 0 : results.size();
        calls.add(new AppendCall(runId,
                results == null ? List.of() : List.copyOf(results),
                events == null ? List.of() : List.copyOf(events)));
        return new BatchOutcome(raw, 0, raw, 0);
    }
}
