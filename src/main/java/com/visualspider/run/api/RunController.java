package com.visualspider.run.api;

import com.visualspider.identity.domain.ActorId;
import com.visualspider.identity.spi.IdentityAccess;
import com.visualspider.result.spi.ResultRecord;
import com.visualspider.result.spi.RunEvent;
import com.visualspider.result.spi.RunEventQuery;
import com.visualspider.result.spi.RunExport;
import com.visualspider.result.spi.RunResultQuery;
import com.visualspider.result.spi.RunStats;
import com.visualspider.run.spi.Page;
import com.visualspider.run.spi.RunCoordinator;
import com.visualspider.run.spi.RunDetail;
import com.visualspider.run.spi.RunFilter;
import com.visualspider.run.spi.RunSummary;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * 运行 REST API 入口（M3-5 #27 / spec §D17）。
 *
 * <p>所有端点 admin 全局可见；collector 仅自己资源。CSRF 由 Spring Security 统一拦截。
 * Controller 仅做参数接收 / 校验 / 响应转换；所有权 / 状态机由 {@link RunCoordinator} +
 * {@link RunResultQuery} + {@link RunEventQuery} + {@link RunExport} 负责。
 *
 * <table>
 *   <caption>D17 端点对照</caption>
 *   <tr><th>Method</th><th>Path</th><th>用途</th></tr>
 *   <tr><td>POST</td><td>{@code /api/runs}</td><td>启动运行</td></tr>
 *   <tr><td>GET</td><td>{@code /api/runs}</td><td>分页列表</td></tr>
 *   <tr><td>GET</td><td>{@code /api/runs/{id}}</td><td>详情</td></tr>
 *   <tr><td>POST</td><td>{@code /api/runs/{id}/cancel}</td><td>取消</td></tr>
 *   <tr><td>GET</td><td>{@code /api/runs/{id}/results}</td><td>分页结果</td></tr>
 *   <tr><td>GET</td><td>{@code /api/runs/{id}/results/export}</td><td>流式导出</td></tr>
 *   <tr><td>GET</td><td>{@code /api/runs/{id}/snapshot}</td><td>快照</td></tr>
 *   <tr><td>GET</td><td>{@code /api/runs/{id}/events}</td><td>分页事件</td></tr>
 * </table>
 */
@RestController
@RequestMapping("/api/runs")
@Validated
public class RunController {

    private final RunCoordinator coordinator;
    private final RunResultQuery resultQuery;
    private final RunEventQuery eventQuery;
    private final RunExport runExport;
    private final IdentityAccess identityAccess;

    public RunController(RunCoordinator coordinator,
                         RunResultQuery resultQuery,
                         RunEventQuery eventQuery,
                         RunExport runExport,
                         IdentityAccess identityAccess) {
        this.coordinator = coordinator;
        this.resultQuery = resultQuery;
        this.eventQuery = eventQuery;
        this.runExport = runExport;
        this.identityAccess = identityAccess;
    }

    // ---------- start ----------

    @PostMapping
    public ResponseEntity<RunStartResponse> start(@RequestBody RunStartRequest request) {
        if (request == null || request.taskId() == null || request.taskId() <= 0) {
            throw new IllegalArgumentException("taskId 必须为正整数");
        }
        ActorId actor = identityAccess.currentActor();
        RunSummary summary = coordinator.start(request.taskId(), actor);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new RunStartResponse(summary.runId(), summary.status().name(),
                        summary.createdAt()));
    }

    // ---------- list ----------

    @GetMapping
    public RunListResponse list(@RequestParam(required = false) String status,
                                @RequestParam(defaultValue = "1") @Min(1) int page,
                                @RequestParam(defaultValue = "20") @Min(1) int size) {
        ActorId actor = identityAccess.currentActor();
        com.visualspider.run.spi.RunState parsed = parseStatus(status);
        RunFilter filter = new RunFilter(parsed, page, size);
        Page<RunSummary> result = coordinator.list(actor, filter);
        return new RunListResponse(result.items(), result.total(), result.page(), result.size());
    }

    // ---------- get ----------

    @GetMapping("/{runId}")
    public RunDetail get(@PathVariable @Positive long runId) {
        ActorId actor = identityAccess.currentActor();
        return coordinator.get(runId, actor);
    }

    // ---------- cancel ----------

    @PostMapping("/{runId}/cancel")
    public ResponseEntity<Void> cancel(@PathVariable @Positive long runId) {
        ActorId actor = identityAccess.currentActor();
        coordinator.cancel(runId, actor);
        return ResponseEntity.accepted().build();
    }

    // ---------- results ----------

    @GetMapping("/{runId}/results")
    public RunResultsResponse results(@PathVariable @Positive long runId,
                                      @RequestParam(defaultValue = "1") @Min(1) int page,
                                      @RequestParam(defaultValue = "50") @Min(1) int size) {
        ActorId actor = identityAccess.currentActor();
        com.visualspider.result.spi.Page<ResultRecord> result =
                resultQuery.page(runId, actor, page, size);
        return new RunResultsResponse(result.items(), result.total(), result.page(), result.size());
    }

    // ---------- results/export（流式）----------

    /**
     * 流式导出：返回 {@link StreamingResponseBody} 直接写 {@code HttpServletResponse}，
     * 不在磁盘生成临时文件（spec §D13）。{@code format=csv|json}；其余值 400。
     */
    @GetMapping("/{runId}/results/export")
    public ResponseEntity<StreamingResponseBody> exportResults(@PathVariable @Positive long runId,
                                                               @RequestParam("format") String format) {
        String f = format == null ? "" : format.toLowerCase();
        if (!"csv".equals(f) && !"json".equals(f)) {
            throw new IllegalArgumentException("format 必须为 csv 或 json");
        }
        String finalFormat = f;
        ActorId actor = identityAccess.currentActor();
        StreamingResponseBody body = out -> writeExport(runId, actor, finalFormat, out);
        String fileName = "run-" + runId + "." + finalFormat;
        HttpHeaders headers = new HttpHeaders();
        headers.setContentDisposition(
                org.springframework.http.ContentDisposition.attachment().filename(fileName).build());
        if ("csv".equals(finalFormat)) {
            headers.setContentType(MediaType.parseMediaType("text/csv; charset=UTF-8"));
        } else {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        return ResponseEntity.ok().headers(headers).body(body);
    }

    private void writeExport(long runId, ActorId actor, String format, OutputStream out)
            throws IOException {
        if ("csv".equals(format)) {
            runExport.writeCsv(runId, actor, out);
        } else {
            runExport.writeJson(runId, actor, out);
        }
        out.flush();
    }

    // ---------- snapshot ----------

    @GetMapping("/{runId}/snapshot")
    public RunSnapshotResponse snapshot(@PathVariable @Positive long runId) {
        ActorId actor = identityAccess.currentActor();
        RunDetail detail = coordinator.get(runId, actor);
        if (detail.snapshotMeta() == null || detail.snapshotMeta().definition() == null) {
            // 数据完整性：detail 必须带 snapshot meta；缺则视为不存在
            throw new com.visualspider.run.internal.RunNotFoundException(runId);
        }
        return new RunSnapshotResponse(runId, detail.taskId(),
                detail.snapshotMeta().name(),
                detail.snapshotMeta().mode().code(),
                detail.snapshotMeta().schemaVersion(),
                detail.snapshotMeta().taskVersion(),
                detail.snapshotMeta().definition());
    }

    // ---------- events ----------

    @GetMapping("/{runId}/events")
    public RunEventsResponse events(@PathVariable @Positive long runId,
                                    @RequestParam(defaultValue = "1") @Min(1) int page,
                                    @RequestParam(defaultValue = "100") @Min(1) int size) {
        ActorId actor = identityAccess.currentActor();
        com.visualspider.result.spi.Page<RunEvent> result =
                eventQuery.pageEvents(runId, actor, page, size);
        return new RunEventsResponse(result.items().stream()
                        .map(RunEventDto::of)
                        .toList(),
                result.total(), result.page(), result.size());
    }

    // ---------- helpers ----------

    private static com.visualspider.run.spi.RunState parseStatus(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return com.visualspider.run.spi.RunState.valueOf(s);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("status 取值不合法: " + s);
        }
    }

    /** 静态 helper：UTF-8 字节长度（导出 fallback 路径用）。 */
    @SuppressWarnings("unused")
    static int utf8Length(String s) {
        return s == null ? 0 : s.getBytes(StandardCharsets.UTF_8).length;
    }

    /** 暴露给测试：stats 端点（M3 暂未列入 §D17 表格，保留便于以后追加）。 */
    @SuppressWarnings("unused")
    RunStats stats(long runId) {
        return resultQuery.stats(runId, identityAccess.currentActor());
    }
}
