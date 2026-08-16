package com.visualspider.visualbrowser.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.visualspider.extraction.spi.ExtractionPreview;
import com.visualspider.extraction.spi.PreviewResult;
import com.visualspider.extraction.spi.PreviewResult.FieldOutcome;
import com.visualspider.identity.domain.ActorId;
import com.visualspider.identity.spi.IdentityAccess;
import com.visualspider.task.domain.FieldDefinition;
import com.visualspider.task.domain.FieldSource;
import com.visualspider.task.domain.ListItemRule;
import com.visualspider.task.domain.ResultType;
import com.visualspider.task.domain.SelectorType;
import com.visualspider.task.domain.TaskDefinition;
import com.visualspider.task.domain.TaskMode;
import com.visualspider.task.domain.TrimPolicy;
import com.visualspider.task.domain.UniqueKeyField;
import com.visualspider.task.domain.Viewport;
import com.visualspider.task.domain.WaitPolicy;
import com.visualspider.visualbrowser.VisualSession;
import com.visualspider.visualbrowser.internal.DefaultVisualSessionManager;
import com.visualspider.visualbrowser.internal.EditingBuffer;
import com.visualspider.visualbrowser.internal.SelectorValidationService;
import com.visualspider.visualbrowser.internal.VisualSessionNotFoundException;
import com.visualspider.visualbrowser.spi.SessionLifecycleState;
import com.visualspider.extraction.spi.CandidateListItemInferrer;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link VisualSessionController} 单元测试（#36 / spec §D11）。
 *
 *  <p>覆盖新增 {@code POST /preview-list} 端点：
 *  <ul>
 *    <li>happy path：定义非空 + 生命周期 ACTIVE → 委派 {@code legacy.previewList} 拿 {@code ListPreviewResult}</li>
 *    <li>definition=null → IllegalArgumentException</li>
 *    <li>CLOSED 生命周期 → VisualSessionNotFoundException</li>
 *    <li>legacySession 缺失 → VisualSessionNotFoundException</li>
 *    <li>maxItems cap=20 透传给底层（按 spec §D9）</li>
 *  </ul>
 *
 *  <p>既有 {@code preview} / {@code infer} 路径已由 {@code VisualSessionSelectIT} /
 *  {@code VisualSessionValidateIT} 等端到端 IT 覆盖，本单测只补 preview-list。
 */
@ExtendWith(MockitoExtension.class)
class VisualSessionControllerTest {

    @Mock private IdentityAccess identityAccess;
    @Mock private DefaultVisualSessionManager manager;
    @Mock private SelectorValidationService selectorValidationService;
    @Mock private ExtractionPreview extractionPreview;
    @Mock private EditingBuffer editingBuffer;
    @Mock private CandidateListItemInferrer inferrer;
    @Mock private VisualSession legacy;

    private VisualSessionController controller;

    @BeforeEach
    void setUp() {
        controller = new VisualSessionController(identityAccess, manager,
                mock(com.visualspider.task.spi.TaskCatalog.class),
                selectorValidationService, extractionPreview, editingBuffer, inferrer);
    }

    @Test
    @DisplayName("previewList：LIST 定义 + ACTIVE session → 委派 legacy.previewList(definition, extraction, 20)")
    void previewListHappyPath() {
        ActorId actor = new ActorId(7L);
        when(identityAccess.currentActor()).thenReturn(actor);
        when(manager.requireOwnedBy("s1", actor)).thenReturn(spiSession("s1", SessionLifecycleState.ACTIVE));
        when(manager.legacySession("s1")).thenReturn(Optional.of(legacy));
        TaskDefinition def = listDefinition();
        PreviewResult item = new PreviewResult(
                List.of(new FieldOutcome("title", "Alpha", "Alpha", false)), List.of());
        ExtractionPreview.ListPreviewResult expected = new ExtractionPreview.ListPreviewResult(
                List.of(item, item, item), 3, List.of());
        when(legacy.previewList(eq(def), eq(extractionPreview), eq(20))).thenReturn(expected);

        ListPreviewResponse actual = controller.previewList("s1",
                new VisualSessionController.PreviewRequest(def));

        // 薄包装：总条数与各字段 outcome 透传
        assertThat(actual.totalMatchCount()).isEqualTo(3);
        assertThat(actual.previews()).hasSize(3);
        assertThat(actual.previews().get(0).fieldOutcomes().get(0).cleanedValue()).isEqualTo("Alpha");
        verify(legacy).previewList(eq(def), eq(extractionPreview), eq(20));
    }

    @Test
    @DisplayName("previewList：definition=null → IllegalArgumentException，不调 legacy")
    void previewListNullDefinitionRejected() {
        when(identityAccess.currentActor()).thenReturn(new ActorId(7L));
        when(manager.requireOwnedBy("s1", new ActorId(7L)))
                .thenReturn(spiSession("s1", SessionLifecycleState.ACTIVE));

        assertThatThrownBy(() -> controller.previewList("s1",
                new VisualSessionController.PreviewRequest(null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("definition");
        verify(legacy, never()).previewList(any(), any(), anyInt());
    }

    @Test
    @DisplayName("previewList：session CLOSED → VisualSessionNotFoundException")
    void previewListClosedSessionRejected() {
        ActorId actor = new ActorId(7L);
        when(identityAccess.currentActor()).thenReturn(actor);
        when(manager.requireOwnedBy("s1", actor))
                .thenReturn(spiSession("s1", SessionLifecycleState.CLOSED));

        assertThatThrownBy(() -> controller.previewList("s1",
                new VisualSessionController.PreviewRequest(listDefinition())))
                .isInstanceOf(VisualSessionNotFoundException.class);
        verify(legacy, never()).previewList(any(), any(), anyInt());
    }

    @Test
    @DisplayName("previewList：legacySession 缺失 → VisualSessionNotFoundException")
    void previewListLegacyMissingRejected() {
        ActorId actor = new ActorId(7L);
        when(identityAccess.currentActor()).thenReturn(actor);
        when(manager.requireOwnedBy("s1", actor)).thenReturn(spiSession("s1", SessionLifecycleState.ACTIVE));
        when(manager.legacySession("s1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.previewList("s1",
                new VisualSessionController.PreviewRequest(listDefinition())))
                .isInstanceOf(VisualSessionNotFoundException.class);
        verify(legacy, never()).previewList(any(), any(), anyInt());
    }

    // ---------- helpers ----------

    private static com.visualspider.visualbrowser.spi.VisualSession spiSession(
            String id, SessionLifecycleState state) {
        Instant now = Instant.now();
        return new com.visualspider.visualbrowser.spi.VisualSession(
                id, 1L, new ActorId(1L), now, now, state);
    }

    private static TaskDefinition listDefinition() {
        FieldDefinition f = new FieldDefinition("title", FieldSource.VISIBLE_TEXT,
                ".title", null, SelectorType.CSS, ResultType.TEXT, TrimPolicy.TRIM, null, true);
        return new TaskDefinition(
                2,
                new TaskMode.List(),
                "https://example.com/list",
                Viewport.DEFAULT,
                new WaitPolicy(0),
                null,
                new ListItemRule("tbody > tr", SelectorType.CSS),
                List.of(new UniqueKeyField("title")),
                List.of(f));
    }
}