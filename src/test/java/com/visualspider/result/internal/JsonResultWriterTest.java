package com.visualspider.result.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.visualspider.identity.domain.ActorId;
import com.visualspider.result.spi.ResultRecord;
import com.visualspider.result.spi.RunAccessDeniedException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * JsonResultWriter 单元测试（不依赖 PG，用 {@link StubRepository} 计数）。
 *
 * <p>覆盖：
 * <ul>
 *   <li>10k 行流式输出：每批 1000；nextBatch 调用次数受控</li>
 *   <li>JSON 顶层数组；每个元素是 {@code data} 字段映射</li>
 *   <li>首末元素与顺序一致</li>
 *   <li>UTF-8 输出（中文 / 引号 / 反斜杠原样）</li>
 *   <li>非 owner -> {@link RunAccessDeniedException}</li>
 * </ul>
 */
class JsonResultWriterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("10k 行：每批 1000，nextBatch 约 10 次；顶层数组 10000 个元素")
    void streamingDoesNotLoadAllRowsAtOnce() throws IOException {
        int total = 10_000;
        StubRepository repo = new StubRepository(total);
        JsonResultWriter writer = new JsonResultWriter(repo);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writer.writeJson(1L, new ActorId(1L), out);

        assertThat(repo.nextBatchCalls).isBetween(10L, 11L);
        assertThat(repo.maxFetchSize).isEqualTo(JsonResultWriter.BATCH_SIZE);

        JsonNode root = MAPPER.readTree(out.toByteArray());
        assertThat(root.isArray()).isTrue();
        assertThat(root.size()).isEqualTo(total);

        JsonNode first = root.get(0);
        assertThat(first.get("title").asText()).isEqualTo("row-0-title");
        assertThat(first.get("url").asText()).isEqualTo("https://x/0");
        assertThat(first.get("score").asText()).isEqualTo("0");

        JsonNode last = root.get(total - 1);
        assertThat(last.get("title").asText()).isEqualTo("row-" + (total - 1) + "-title");
    }

    @Test
    @DisplayName("含中文 / 反斜杠 / 引号的字段原样输出（Jackson 默认转义）")
    void utf8AndEscaping() throws IOException {
        ResultRecord r = new ResultRecord(0L, 1L, 0,
                Map.of("title", "你好", "url", "https://x/?q=\"hi\"&z=1\\2"),
                null);
        StubRepository repo = new StubRepository(List.of(r));
        JsonResultWriter writer = new JsonResultWriter(repo);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writer.writeJson(1L, new ActorId(1L), out);

        String json = out.toString("UTF-8");
        assertThat(json).startsWith("[");
        assertThat(json).endsWith("]");
        assertThat(json).contains("\"你好\"");
        assertThat(json).contains("\"https://x/?q=\\\"hi\\\"&z=1\\\\2\"");
    }

    @Test
    @DisplayName("非 owner -> RunAccessDeniedException")
    void accessDeniedPropagates() {
        StubRepository repo = new StubRepository(0);
        repo.shouldThrowOnVerifyAccess = true;
        JsonResultWriter writer = new JsonResultWriter(repo);

        assertThatThrownBy(() -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            writer.writeJson(1L, new ActorId(99L), out);
        }).isInstanceOf(RunAccessDeniedException.class);
    }

    @Test
    @DisplayName("0 行：输出 []")
    void emptyRun() throws IOException {
        StubRepository repo = new StubRepository(0);
        JsonResultWriter writer = new JsonResultWriter(repo);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writer.writeJson(1L, new ActorId(1L), out);
        assertThat(out.toString("UTF-8")).isEqualTo("[]");
        assertThat(repo.nextBatchCalls).isEqualTo(1L);
    }

    // ============================ Stub Repository ============================

    static class StubRepository extends JdbcRunResultRepository {
        private final Queue<List<ResultRecord>> batches;
        long nextBatchCalls = 0;
        int maxFetchSize = 0;
        boolean shouldThrowOnVerifyAccess = false;

        StubRepository(int totalRows) {
            super(null, null, null);
            this.batches = new java.util.ArrayDeque<>();
            int batchSize = 1000;
            for (int start = 0; start < totalRows; start += batchSize) {
                int end = Math.min(start + batchSize, totalRows);
                List<ResultRecord> batch = new ArrayList<>(end - start);
                for (int i = start; i < end; i++) {
                    Map<String, String> data = new LinkedHashMap<>();
                    data.put("title", "row-" + i + "-title");
                    data.put("url", "https://x/" + i);
                    data.put("score", String.valueOf(i));
                    batch.add(new ResultRecord(0L, 1L, i, data, null));
                }
                batches.add(batch);
            }
            batches.add(List.of());
        }

        StubRepository(List<ResultRecord> rows) {
            super(null, null, null);
            this.batches = new java.util.ArrayDeque<>();
            if (!rows.isEmpty()) {
                batches.add(rows);
            }
            batches.add(List.of());
        }

        @Override
        void verifyAccess(long runId, ActorId actor) {
            if (shouldThrowOnVerifyAccess) {
                throw new RunAccessDeniedException(runId);
            }
        }

        @Override
        List<ResultRecord> nextBatch(long runId, int afterSeq, int limit) {
            nextBatchCalls++;
            if (limit > maxFetchSize) {
                maxFetchSize = limit;
            }
            Iterator<List<ResultRecord>> it = batches.iterator();
            if (!it.hasNext()) {
                return List.of();
            }
            List<ResultRecord> head = it.next();
            batches.remove();
            return head;
        }
    }
}