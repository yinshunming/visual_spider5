package com.visualspider.run.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.visualspider.identity.domain.ActorId;
import com.visualspider.identity.spi.IdentityAccess;
import com.visualspider.result.spi.Page;
import com.visualspider.result.spi.ResultRecord;
import com.visualspider.result.spi.RunAccessDeniedException;
import com.visualspider.result.spi.RunEvent;
import com.visualspider.result.spi.RunEventLevel;
import com.visualspider.result.spi.RunEventQuery;
import com.visualspider.result.spi.RunExport;
import com.visualspider.result.spi.RunResultQuery;
import com.visualspider.result.spi.RunStats;
import com.visualspider.run.internal.RunNotCancellableException;
import com.visualspider.run.internal.RunNotFoundException;
import com.visualspider.run.internal.RunNotOwnerException;
import com.visualspider.run.internal.TaskNotReadyException;
import com.visualspider.run.internal.UserRunLimitException;
import com.visualspider.run.spi.RunCoordinator;
import com.visualspider.run.spi.RunDetail;
import com.visualspider.run.spi.RunFilter;
import com.visualspider.run.spi.RunProgress;
import com.visualspider.run.spi.RunState;
import com.visualspider.run.spi.RunSummary;
import com.visualspider.run.spi.StopReason;
import com.visualspider.task.domain.FieldDefinition;
import com.visualspider.task.domain.FieldSource;
import com.visualspider.task.domain.ResultType;
import com.visualspider.task.domain.SelectorType;
import com.visualspider.task.domain.TaskDefinition;
import com.visualspider.task.domain.TaskMode;
import com.visualspider.task.domain.TrimPolicy;
import com.visualspider.task.domain.Viewport;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * {@link RunController} 单元测试（#27 / spec §D17）。
 *
 * <p>覆盖：
 * <ul>
 *   <li>start：传 taskId -&gt; coordinator.start；非法 taskId -&gt; 400</li>
 *   <li>list：调 coordinator.list with filter；status 非法 -&gt; 400</li>
 *   <li>get：调 coordinator.get；非 owner 透传 RunNotFoundException</li>
 *   <li>cancel：调 coordinator.cancel；终态 -&gt; 透传 RunNotCancellableException</li>
 *   <li>results：调 resultQuery.page</li>
 *   <li>export：调 runExport.writeCsv/Json + 非 csv/json -&gt; 400</li>
 *   <li>snapshot：返回 RunSnapshotResponse</li>
 *   <li>events：调 eventQuery.pageEvents</li>
 * </ul>
 */
class RunControllerTest {

    private RunCoordinator coordinator;
    private RunResultQuery resultQuery;
    private RunEventQuery eventQuery;
    private RunExport runExport;
    private IdentityAccess identityAccess;
    private RunController controller;

    @BeforeEach
    void setUp() {
        coordinator = mock(RunCoordinator.class);
        resultQuery = mock(RunResultQuery.class);
        eventQuery = mock(RunEventQuery.class);
        runExport = mock(RunExport.class);
        identityAccess = mock(IdentityAccess.class);
        controller = new RunController(coordinator, resultQuery, eventQuery, runExport,
                identityAccess);
    }

    // ---------- start ----------

    @Test
    void startPassesTaskIdAndActor() {
        when(identityAccess.currentActor()).thenReturn(new ActorId(1L));
        when(coordinator.start(eq(7L), any(ActorId.class))).thenReturn(
                new RunSummary(1L, 7L, 1L, RunState.WAITING,
                        null, false, 0, 0, 0,
                        OffsetDateTime.now(), null, null));

        var resp = controller.start(new RunStartRequest(7L));

        assertThat(resp.getStatusCode().value()).isEqualTo(202);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().runId()).isEqualTo(1L);
        assertThat(resp.getBody().status()).isEqualTo("WAITING");
        verify(coordinator, times(1)).start(eq(7L), any(ActorId.class));
    }

    @Test
    void startWithNullTaskIdThrowsIllegalArgument() {
        assertThatThrownBy(() -> controller.start(new RunStartRequest(null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void startPropagatesUserRunLimit() {
        when(identityAccess.currentActor()).thenReturn(new ActorId(1L));
        when(coordinator.start(anyLong(), any(ActorId.class)))
                .thenThrow(new UserRunLimitException(1L));
        assertThatThrownBy(() -> controller.start(new RunStartRequest(7L)))
                .isInstanceOf(UserRunLimitException.class);
    }

    @Test
    void startPropagatesTaskNotReady() {
        when(identityAccess.currentActor()).thenReturn(new ActorId(1L));
        when(coordinator.start(anyLong(), any(ActorId.class)))
                .thenThrow(new TaskNotReadyException(7L, ""));
        assertThatThrownBy(() -> controller.start(new RunStartRequest(7L)))
                .isInstanceOf(TaskNotReadyException.class);
    }

    // ---------- list ----------

    @Test
    void listPassesFilterAndCastsPage() {
        when(identityAccess.currentActor()).thenReturn(new ActorId(1L));
        when(coordinator.list(any(ActorId.class), any(RunFilter.class)))
                .thenReturn(new com.visualspider.run.spi.Page<>(
                        List.of(summ(1L)), 1L, 1, 20));

        RunListResponse resp = controller.list(null, 1, 20);

        assertThat(resp.total()).isEqualTo(1L);
        assertThat(resp.items()).hasSize(1);
    }

    @Test
    void listWithInvalidStatusThrows() {
        assertThatThrownBy(() -> controller.list("INVALID", 1, 20))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void listWithValidStatusParsesEnum() {
        when(identityAccess.currentActor()).thenReturn(new ActorId(1L));
        when(coordinator.list(any(ActorId.class), any(RunFilter.class)))
                .thenReturn(new com.visualspider.run.spi.Page<>(
                        List.of(), 0L, 1, 20));

        controller.list("RUNNING", 1, 20);

        verify(coordinator, times(1)).list(any(ActorId.class),
                org.mockito.ArgumentMatchers.argThat(
                        f -> f.status() == RunState.RUNNING));
    }

    // ---------- get ----------

    @Test
    void getReturnsDetail() {
        when(identityAccess.currentActor()).thenReturn(new ActorId(1L));
        RunDetail detail = detail(7L, 1L, RunState.SUCCESS);
        when(coordinator.get(eq(7L), any(ActorId.class))).thenReturn(detail);

        RunDetail returned = controller.get(7L);

        assertThat(returned).isSameAs(detail);
    }

    @Test
    void getPropagatesNotFound() {
        when(identityAccess.currentActor()).thenReturn(new ActorId(2L));
        when(coordinator.get(anyLong(), any(ActorId.class)))
                .thenThrow(new RunNotFoundException(1L));
        assertThatThrownBy(() -> controller.get(1L))
                .isInstanceOf(RunNotFoundException.class);
    }

    // ---------- cancel ----------

    @Test
    void cancelReturns202() {
        when(identityAccess.currentActor()).thenReturn(new ActorId(1L));
        var resp = controller.cancel(7L);
        assertThat(resp.getStatusCode().value()).isEqualTo(202);
        verify(coordinator, times(1)).cancel(eq(7L), any(ActorId.class));
    }

    @Test
    void cancelPropagatesNotCancellable() {
        when(identityAccess.currentActor()).thenReturn(new ActorId(1L));
        doThrow(new RunNotCancellableException(7L, RunState.SUCCESS))
                .when(coordinator).cancel(anyLong(), any(ActorId.class));
        assertThatThrownBy(() -> controller.cancel(7L))
                .isInstanceOf(RunNotCancellableException.class);
    }

    // ---------- results ----------

    @Test
    void resultsCallsPage() {
        when(identityAccess.currentActor()).thenReturn(new ActorId(1L));
        when(resultQuery.page(eq(7L), any(ActorId.class), anyInt(), anyInt()))
                .thenReturn(new Page<>(List.of(result(1L, 7L, 1)), 1, 50, 1L));

        RunResultsResponse resp = controller.results(7L, 1, 50);

        assertThat(resp.items()).hasSize(1);
        assertThat(resp.total()).isEqualTo(1L);
        assertThat(resp.page()).isEqualTo(1);
        assertThat(resp.size()).isEqualTo(50);
    }

    @Test
    void resultsPropagatesAccessDenied() {
        when(identityAccess.currentActor()).thenReturn(new ActorId(2L));
        when(resultQuery.page(anyLong(), any(ActorId.class), anyInt(), anyInt()))
                .thenThrow(new RunAccessDeniedException(1L));
        assertThatThrownBy(() -> controller.results(1L, 1, 50))
                .isInstanceOf(RunAccessDeniedException.class);
    }

    // ---------- export ----------

    @Test
    void exportCsvInvokesWriter() throws Exception {
        when(identityAccess.currentActor()).thenReturn(new ActorId(1L));
        org.mockito.Mockito.doAnswer(inv -> {
            ByteArrayOutputStream out = inv.getArgument(2);
            out.write("col\nval\n".getBytes());
            return null;
        }).when(runExport).writeCsv(anyLong(), any(ActorId.class), any(java.io.OutputStream.class));

        var resp = controller.exportResults(7L, "csv");
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getHeaders().getContentType().toString()).contains("text/csv");
        assertThat(resp.getHeaders().getContentDisposition().getFilename()).isEqualTo("run-7.csv");

        StreamingResponseBody body = resp.getBody();
        assertThat(body).isNotNull();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        body.writeTo(out);
        assertThat(out.toString()).isEqualTo("col\nval\n");

        verify(runExport, times(1)).writeCsv(eq(7L), any(ActorId.class),
                any(java.io.OutputStream.class));
    }

    @Test
    void exportJsonInvokesJsonWriter() throws Exception {
        when(identityAccess.currentActor()).thenReturn(new ActorId(1L));

        var resp = controller.exportResults(7L, "json");
        assertThat(resp.getHeaders().getContentDisposition().getFilename())
                .isEqualTo("run-7.json");
        assertThat(resp.getHeaders().getContentType().toString()).contains("application/json");

        StreamingResponseBody body = resp.getBody();
        // 跑空 body（json writer 是 mock 不真正写）：不抛异常即可
        body.writeTo(new ByteArrayOutputStream());
        // 默认 mock 走 no-op；此处只验证流式 body 不抛
    }

    @Test
    void exportRejectsInvalidFormat() {
        assertThatThrownBy(() -> controller.exportResults(7L, "xml"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> controller.exportResults(7L, (String) null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------- snapshot ----------

    @Test
    void snapshotReturnsResponse() {
        when(identityAccess.currentActor()).thenReturn(new ActorId(1L));
        when(coordinator.get(eq(7L), any(ActorId.class))).thenReturn(detail(7L, 1L, RunState.SUCCESS));

        RunSnapshotResponse resp = controller.snapshot(7L);

        assertThat(resp.runId()).isEqualTo(7L);
        assertThat(resp.taskId()).isEqualTo(1L);
        assertThat(resp.definition()).isNotNull();
        assertThat(resp.definition().fields()).hasSize(1);
        assertThat(resp.mode()).isEqualTo("SINGLE_PAGE");
    }

    @Test
    void snapshotMissingDefinitionThrowsNotFound() {
        when(identityAccess.currentActor()).thenReturn(new ActorId(1L));
        TaskDefinition nullDef = null;
        RunDetail d = new RunDetail(7L, 1L, 1L, RunState.SUCCESS,
                StopReason.COMPLETED, false,
                0, 0, 0, 0, 0, null, null,
                OffsetDateTime.now(), null, OffsetDateTime.now(),
                new RunDetail.TaskSnapshotMeta("demo",
                        new TaskMode.SinglePage(), 1, 1L, nullDef));
        when(coordinator.get(anyLong(), any(ActorId.class))).thenReturn(d);

        assertThatThrownBy(() -> controller.snapshot(7L))
                .isInstanceOf(RunNotFoundException.class);
    }

    // ---------- events ----------

    @Test
    void eventsCallsPageEvents() {
        when(identityAccess.currentActor()).thenReturn(new ActorId(1L));
        RunEvent e = new RunEvent(1001L, 7L, RunEventLevel.INFO, "navigate",
                "https://example.com/", null, "ok", Instant.now());
        when(eventQuery.pageEvents(eq(7L), any(ActorId.class), anyInt(), anyInt()))
                .thenReturn(new Page<>(List.of(e), 1, 100, 1L));

        RunEventsResponse resp = controller.events(7L, 1, 100);

        assertThat(resp.items()).hasSize(1);
        assertThat(resp.items().get(0).id()).isEqualTo(1001L);
        assertThat(resp.total()).isEqualTo(1L);
        verify(eventQuery, times(1)).pageEvents(eq(7L),
                any(ActorId.class), anyInt(), anyInt());
    }

    // ---------- helpers ----------

    private RunSummary summ(long id) {
        return new RunSummary(id, 1L, 1L, RunState.SUCCESS,
                StopReason.COMPLETED, false,
                1, 1, 0,
                OffsetDateTime.now(), OffsetDateTime.now(), OffsetDateTime.now());
    }

    private ResultRecord result(long id, long runId, int seq) {
        return new ResultRecord(id, runId, seq, Map.of("title", "Hello"), Instant.now());
    }

    private RunDetail detail(long runId, long taskId, RunState state) {
        TaskDefinition def = new TaskDefinition(1, new TaskMode.SinglePage(),
                "https://example.com", Viewport.DEFAULT, null,
                List.of(new FieldDefinition("title", FieldSource.VISIBLE_TEXT,
                        "h1", null, SelectorType.CSS,
                        ResultType.TEXT, TrimPolicy.TRIM, null, true)));
        return new RunDetail(runId, taskId, 1L, state,
                state == RunState.SUCCESS ? StopReason.COMPLETED : null,
                false,
                1, 1, 1, 1, 0, "https://example.com/", "extract-success",
                OffsetDateTime.now(), OffsetDateTime.now(), OffsetDateTime.now(),
                new RunDetail.TaskSnapshotMeta("demo",
                        new TaskMode.SinglePage(), 1, 1L, def));
    }
}
