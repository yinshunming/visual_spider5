package com.visualspider.result.internal;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.visualspider.identity.domain.ActorId;
import com.visualspider.result.spi.ResultRecord;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * JSON 流式导出（M3 spec §D13）。
 *
 * <p>直接写 {@link OutputStream}（典型为 {@code HttpServletResponse.getOutputStream()}），
 * keyset 游标每批 {@value #BATCH_SIZE} 行；用 Jackson {@link JsonGenerator} 流式写
 * 顶层数组 {@code [{...}, {...}]}；不在磁盘生成临时文件。
 *
 * <p>每个元素为 {@link ResultRecord#data()} 的字段映射（顺序为 {@code LinkedHashMap}
 * 插入顺序，即写入时字段顺序）；UTF-8、无缩进、HTML 安全。
 */
@Component
public class JsonResultWriter {

    /** keyset 游标每批上限（spec §D13 / T5）。 */
    public static final int BATCH_SIZE = 1000;

    private static final JsonFactory FACTORY = new JsonFactory();

    private final JdbcRunResultRepository repository;

    public JsonResultWriter(JdbcRunResultRepository repository) {
        this.repository = repository;
    }

    /**
     * 写入 JSON 数组流。
     *
     * @throws com.visualspider.result.spi.RunAccessDeniedException 非 owner 且非 admin
     * @throws IOException 写流出错
     */
    public void writeJson(long runId, ActorId actor, OutputStream out) throws IOException {
        if (out == null) {
            throw new IllegalArgumentException("OutputStream 不能为空");
        }
        // 提前做所有权校验：非 owner 且非 admin 抛 RunAccessDeniedException
        repository.verifyAccess(runId, actor);
        try (JsonGenerator gen = FACTORY.createGenerator(out)) {
            // Jackson 2.x 默认无缩进；HTML_SAFE/QUOTE_FIELD_NAMES 等默认值符合 CSV 导出需求
            gen.writeStartArray();
            int afterSeq = -1;
            while (true) {
                List<ResultRecord> batch = repository.nextBatch(runId, afterSeq, BATCH_SIZE);
                if (batch.isEmpty()) {
                    break;
                }
                for (ResultRecord r : batch) {
                    gen.writeStartObject();
                    for (Map.Entry<String, String> entry : r.data().entrySet()) {
                        gen.writeStringField(entry.getKey(), entry.getValue());
                    }
                    gen.writeEndObject();
                }
                afterSeq = batch.get(batch.size() - 1).sequenceNo();
                gen.flush();
            }
            gen.writeEndArray();
            gen.flush();
        }
    }
}