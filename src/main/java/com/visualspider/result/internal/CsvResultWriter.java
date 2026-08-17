package com.visualspider.result.internal;

import com.visualspider.identity.domain.ActorId;
import com.visualspider.result.spi.ResultRecord;
import com.visualspider.result.spi.RunExport;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * CSV 流式导出（M3 spec §D13）。
 *
 * <p>直接写 {@link OutputStream}（典型为 {@code HttpServletResponse.getOutputStream()}），
 * keyset 游标每批 {@value #BATCH_SIZE} 行；不在磁盘生成临时文件。
 *
 * <p>表头从运行固化快照的 {@code TaskDefinition.fields.name} 生成；
 * 每行按字段顺序输出 {@code cleanedValue}，缺失键输出空串。
 * 含逗号 / 引号 / 换行的字段用 RFC 4180 规则引用（双引号包裹、内部引号 doubled）。
 */
@Component
public class CsvResultWriter {

    /** keyset 游标每批上限（spec §D13 / T5）。 */
    public static final int BATCH_SIZE = 1000;

    private final JdbcRunResultRepository repository;

    public CsvResultWriter(JdbcRunResultRepository repository) {
        this.repository = repository;
    }

    /**
     * 写入 CSV。
     *
     * @throws com.visualspider.result.spi.RunAccessDeniedException 非 owner 且非 admin
     * @throws IOException 写流出错
     */
    public void writeCsv(long runId, ActorId actor, OutputStream out) throws IOException {
        if (out == null) {
            throw new IllegalArgumentException("OutputStream 不能为空");
        }
        // fieldNames 内部已做 verifyAccess：非 owner/non-admin 抛 RunAccessDeniedException
        List<String> fieldNames = repository.fieldNames(runId, actor);
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
        writer.write(joinCsv(fieldNames));
        writer.write('\n');
        int afterSeq = -1;
        while (true) {
            List<ResultRecord> batch = repository.nextBatch(runId, afterSeq, BATCH_SIZE);
            if (batch.isEmpty()) {
                break;
            }
            for (ResultRecord r : batch) {
                writer.write(toCsvRow(r, fieldNames));
                writer.write('\n');
            }
            afterSeq = batch.get(batch.size() - 1).sequenceNo();
            writer.flush();
        }
        writer.flush();
    }

    static String toCsvRow(ResultRecord record, List<String> fieldNames) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fieldNames.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            String v = record.data().get(fieldNames.get(i));
            appendEscaped(sb, v);
        }
        return sb.toString();
    }

    static String joinCsv(List<String> fields) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            appendEscaped(sb, fields.get(i));
        }
        return sb.toString();
    }

    private static void appendEscaped(StringBuilder sb, String value) {
        if (value == null) {
            return;
        }
        boolean needsQuote = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == ',' || c == '"' || c == '\n' || c == '\r') {
                needsQuote = true;
                break;
            }
        }
        if (!needsQuote) {
            sb.append(value);
            return;
        }
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"') {
                sb.append('"').append('"');
            } else {
                sb.append(c);
            }
        }
        sb.append('"');
    }
}