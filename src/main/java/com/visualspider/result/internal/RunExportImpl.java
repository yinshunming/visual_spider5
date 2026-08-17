package com.visualspider.result.internal;

import com.visualspider.identity.domain.ActorId;
import com.visualspider.result.spi.RunExport;
import java.io.IOException;
import java.io.OutputStream;
import org.springframework.stereotype.Component;

/**
 * {@link RunExport} 默认实现（M3 spec §D12 / §D13）。
 *
 * <p>将 {@link #writeCsv} / {@link #writeJson} 委托给 {@link CsvResultWriter} /
 * {@link JsonResultWriter} 两个独立的格式化器；{@link JdbcRunResultRepository} 持有
 * keyset 游标与所有权校验。Controller 层（{@code run.api.RunController}，后续 issue）
 * 直接注入本 bean，将 {@code HttpServletResponse.getOutputStream()} 透传即可。
 */
@Component
public class RunExportImpl implements RunExport {

    private final CsvResultWriter csvWriter;
    private final JsonResultWriter jsonWriter;

    public RunExportImpl(CsvResultWriter csvWriter, JsonResultWriter jsonWriter) {
        this.csvWriter = csvWriter;
        this.jsonWriter = jsonWriter;
    }

    @Override
    public void writeCsv(long runId, ActorId actor, OutputStream out) throws IOException {
        csvWriter.writeCsv(runId, actor, out);
    }

    @Override
    public void writeJson(long runId, ActorId actor, OutputStream out) throws IOException {
        jsonWriter.writeJson(runId, actor, out);
    }
}