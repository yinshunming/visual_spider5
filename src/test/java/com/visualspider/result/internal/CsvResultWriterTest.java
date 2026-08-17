package com.visualspider.result.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.visualspider.identity.domain.ActorId;
import com.visualspider.result.spi.ResultRecord;
import com.visualspider.result.spi.RunAccessDeniedException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * CsvResultWriter 单元测试（不依赖 PG，用 {@link StubRepository} 计数）。
 *
 * <p>覆盖：
 * <ul>
 *   <li>10k 行流式输出：每批最多 1000 行；不会一次加载全部到内存（spy {@code nextBatch} 调用次数）</li>
 *   <li>CSV 表头从 {@code fieldNames()} 生成</li>
 *   <li>行按字段顺序输出，空值输出空串</li>
 *   <li>含逗号 / 引号 / 换行的字段按 RFC 4180 引用</li>
 *   <li>非 owner -> {@link RunAccessDeniedException}</li>
 * </ul>
 */
class CsvResultWriterTest {

    private static final List<String> FIELD_NAMES = List.of("title", "url", "score");

    @Test
    @DisplayName("10k 行：每批 1000，nextBatch 约 10 次；输出 10000 行")
    void streamingDoesNotLoadAllRowsAtOnce() throws IOException {
        int total = 10_000;
        StubRepository repo = new StubRepository(FIELD_NAMES, total);
        CsvResultWriter writer = new CsvResultWriter(repo);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writer.writeCsv(1L, new ActorId(1L), out);

        // 1 个初始 verifyAccess (通过 fieldNames) + 11 次 nextBatch (last empty)
        // 因为有 last empty batch: 1000+1000+1000+1000+1000+1000+1000+1000+1000+1000=10000 行
        // 之后 nextBatch 返回 empty list 退出。共 10 次非空 + 1 次空 = 11 次调用
        assertThat(repo.nextBatchCalls).isBetween(10L, 11L);
        assertThat(repo.maxFetchSize).isEqualTo(CsvResultWriter.BATCH_SIZE);

        String csv = out.toString(StandardCharsets.UTF_8);
        long lineCount = csv.chars().filter(c -> c == '\n').count();
        assertThat(lineCount).isEqualTo(total + 1L); // 1 header + 10000 data
    }

    @Test
    @DisplayName("首行 = 表头；首条数据 = sequence_no=0；末条 = sequence_no=N-1")
    void firstAndLastRows() throws IOException {
        int total = 100;
        StubRepository repo = new StubRepository(FIELD_NAMES, total);
        CsvResultWriter writer = new CsvResultWriter(repo);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writer.writeCsv(1L, new ActorId(1L), out);

        String csv = out.toString(StandardCharsets.UTF_8);
        String[] lines = csv.split("\n");
        assertThat(lines[0]).isEqualTo("title,url,score");
        // 第一条数据 seq=0, title="row-0-title", url="https://x/0", score="0"
        assertThat(lines[1]).isEqualTo("row-0-title,https://x/0,0");
        // 末条 seq=99
        assertThat(lines[100]).isEqualTo("row-99-title,https://x/99,99");
    }

    @Test
    @DisplayName("字段缺失 -> 空串；多余字段不输出")
    void missingFieldsOutputEmpty() throws IOException {
        List<String> fields = List.of("a", "b", "c");
        StubRepository repo = new StubRepository(fields, List.of(
                new ResultRecord(0L, 1L, 0, Map.of("a", "1", "c", "3"), null),
                new ResultRecord(0L, 1L, 1, Map.of("b", "2"), null)));
        CsvResultWriter writer = new CsvResultWriter(repo);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writer.writeCsv(1L, new ActorId(1L), out);

        String csv = out.toString(StandardCharsets.UTF_8);
        String[] lines = csv.split("\n");
        assertThat(lines[0]).isEqualTo("a,b,c");
        assertThat(lines[1]).isEqualTo("1,,3"); // b 缺失 -> 空串
        assertThat(lines[2]).isEqualTo(",2,"); // a/c 缺失 -> 空串
    }

    @Test
    @DisplayName("RFC 4180 转义：含逗号 / 引号 / 换行的字段")
    void rfc4180Escaping() {
        ResultRecord r = new ResultRecord(0L, 1L, 0,
                Map.of("a", "hello,world", "b", "she said \"hi\"", "c", "line1\nline2"),
                null);
        String row = CsvResultWriter.toCsvRow(r, List.of("a", "b", "c"));
        assertThat(row).isEqualTo("\"hello,world\",\"she said \"\"hi\"\"\",\"line1\nline2\"");
    }

    @Test
    @DisplayName("joinCsv：表头字段含逗号也正确转义")
    void joinCsvEscaping() {
        String joined = CsvResultWriter.joinCsv(List.of("a", "b,c", "d"));
        assertThat(joined).isEqualTo("a,\"b,c\",d");
    }

    @Test
    @DisplayName("非 owner -> RunAccessDeniedException")
    void accessDeniedPropagates() {
        StubRepository repo = new StubRepository(FIELD_NAMES, 0);
        repo.shouldThrowOnFieldNames = true;
        CsvResultWriter writer = new CsvResultWriter(repo);

        assertThatThrownBy(() -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            writer.writeCsv(1L, new ActorId(99L), out);
        }).isInstanceOf(RunAccessDeniedException.class);
    }

    @Test
    @DisplayName("0 行：仅输出表头；nextBatch 调用 1 次（返回空）")
    void emptyRun() throws IOException {
        StubRepository repo = new StubRepository(FIELD_NAMES, 0);
        CsvResultWriter writer = new CsvResultWriter(repo);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writer.writeCsv(1L, new ActorId(1L), out);
        String csv = out.toString(StandardCharsets.UTF_8);
        assertThat(csv).isEqualTo("title,url,score\n");
        assertThat(repo.nextBatchCalls).isEqualTo(1L);
    }

    // ============================ Stub Repository ============================

    /**
     * 测试替身：继承 {@link JdbcRunResultRepository} 并覆盖导出辅助方法。
     * 通过 {@code super(null, null, null)} 构造（这些字段不被覆盖方法使用）。
     */
    static class StubRepository extends JdbcRunResultRepository {
        private final List<String> fieldNames;
        private final Queue<List<ResultRecord>> batches;
        long nextBatchCalls = 0;
        int maxFetchSize = 0;
        boolean shouldThrowOnFieldNames = false;

        StubRepository(List<String> fieldNames, int totalRows) {
            super(null, null, null);
            this.fieldNames = fieldNames;
            this.batches = new java.util.ArrayDeque<>();
            // 预先按 1000 行一组打包
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
            batches.add(List.of()); // sentinel empty
        }

        StubRepository(List<String> fieldNames, List<ResultRecord> rows) {
            super(null, null, null);
            this.fieldNames = fieldNames;
            this.batches = new java.util.ArrayDeque<>();
            if (!rows.isEmpty()) {
                batches.add(rows);
            }
            batches.add(List.of());
        }

        @Override
        List<String> fieldNames(long runId, ActorId actor) {
            if (shouldThrowOnFieldNames) {
                throw new RunAccessDeniedException(runId);
            }
            return fieldNames;
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

        @Override
        void verifyAccess(long runId, ActorId actor) {
            // no-op
        }
    }
}