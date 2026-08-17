package com.visualspider.visualbrowser.api;

import com.visualspider.identity.domain.ActorId;
import com.visualspider.identity.spi.IdentityAccess;
import com.visualspider.task.spi.TaskCatalog;
import com.visualspider.extraction.spi.CandidateListItemInferrer;
import com.visualspider.extraction.spi.ExtractionPreview;
import com.visualspider.extraction.spi.InferredCandidateListItem;
import com.visualspider.extraction.spi.PreviewResult;
import com.visualspider.task.domain.TaskDefinition;
import com.visualspider.visualbrowser.ViewportMapper;
import com.visualspider.visualbrowser.internal.DefaultVisualSessionManager;
import com.visualspider.visualbrowser.internal.EditingBuffer;
import com.visualspider.visualbrowser.internal.SelectorValidationService;
import com.visualspider.visualbrowser.internal.TaskNotOpenableException;
import com.visualspider.visualbrowser.internal.VisualSessionNotFoundException;
import com.visualspider.visualbrowser.spi.SessionLifecycleState;
import com.visualspider.visualbrowser.spi.VisualSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 可视会话 REST 边界（M2-1 #17 / M2-3 #19 / M4-6 #36 扩 preview-list）。
 *
 * <p>仅做参数接收与权限转交；所有权 / 状态 / 容量校验由 manager 负责。
 * CSRF 由 Spring Security 统一拦截。preview 不写库，仅在当前 lane/Page 上执行。
 *
 * <p>三类端点（{@code preview} / {@code infer} / {@code preview-list}）共享
 * {@link #requireOpenOwned(String)} 前置检查：actor + 所有权 + 非 CLOSED。
 */
@RestController
@RequestMapping("/api/visual-sessions")
public class VisualSessionController {

    private final IdentityAccess identityAccess;
    private final DefaultVisualSessionManager manager;
    private final TaskCatalog taskCatalog;
    private final SelectorValidationService selectorValidationService;
    private final ExtractionPreview extractionPreview;
    private final EditingBuffer editingBuffer;
    private final CandidateListItemInferrer inferrer;

    public VisualSessionController(IdentityAccess identityAccess,
                                   DefaultVisualSessionManager manager,
                                   TaskCatalog taskCatalog,
                                   SelectorValidationService selectorValidationService,
                                   ExtractionPreview extractionPreview,
                                   EditingBuffer editingBuffer,
                                   CandidateListItemInferrer inferrer) {
        this.identityAccess = identityAccess;
        this.manager = manager;
        this.taskCatalog = taskCatalog;
        this.selectorValidationService = selectorValidationService;
        this.extractionPreview = extractionPreview;
        this.editingBuffer = editingBuffer;
        this.inferrer = inferrer;
    }

    @PostMapping
    public ResponseEntity<OpenVisualSessionResponse> open(@RequestBody OpenRequest request) {
        ActorId actor = identityAccess.currentActor();
        // 读取 task 并校验所有权 + READY/DRAFT：通过 TaskCatalog.read 内部权限。
        var draft = taskCatalog.read(request.taskId(), actor);
        com.visualspider.task.domain.TaskStatus status = draft.status();
        if (status != com.visualspider.task.domain.TaskStatus.DRAFT
                && status != com.visualspider.task.domain.TaskStatus.READY) {
            throw new TaskNotOpenableException(request.taskId(), status);
        }
        VisualSession session = manager.open(request.taskId(), actor);
        return ResponseEntity.status(HttpStatus.CREATED).body(OpenVisualSessionResponse.from(session));
    }

    @GetMapping("/{sessionId}")
    public OpenVisualSessionResponse get(@PathVariable @NotNull String sessionId) {
        ActorId actor = identityAccess.currentActor();
        VisualSession session = manager.requireOwnedBy(sessionId, actor);
        return OpenVisualSessionResponse.from(session);
    }

    @PostMapping("/{sessionId}/heartbeat")
    public ResponseEntity<Void> heartbeat(@PathVariable @NotNull String sessionId) {
        ActorId actor = identityAccess.currentActor();
        manager.heartbeat(sessionId, actor);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Void> close(@PathVariable @NotNull String sessionId) {
        ActorId actor = identityAccess.currentActor();
        manager.close(sessionId, actor, "USER_CLOSE");
        return ResponseEntity.noContent().build();
    }

    /** 编辑缓冲：保存会话内编辑的字段定义；触发 5 秒防抖自动保存（#20）。 */
    @PutMapping("/{sessionId}")
    public ResponseEntity<Void> patchBuffer(@PathVariable @NotNull String sessionId,
                                            @RequestBody PreviewRequest request) {
        ActorId actor = identityAccess.currentActor();
        VisualSession owned = requireOpenOwned(sessionId, actor);
        TaskDefinition definition = request.definition();
        if (definition == null) {
            throw new IllegalArgumentException("definition 不能为空");
        }
        editingBuffer.update(sessionId, actor, definition);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/{sessionId}/selectors/validate")
    public ValidateSelectorsResponse validateSelectors(@PathVariable @NotNull String sessionId,
                                                       @RequestBody ValidateSelectorsRequest request) {
        ActorId actor = identityAccess.currentActor();
        VisualSession owned = requireOpenOwned(sessionId, actor);
        // 在 session 绑定的 lane/Page 上校验（per-session PlaywrightControl），与 preview 同路径
        var legacy = manager.legacySession(sessionId)
                .orElseThrow(() -> new VisualSessionNotFoundException(sessionId));
        List<ValidateSelectorsResponse.SelectorOutcome> outcomes = new ArrayList<>();
        for (ValidateSelectorsRequest.SelectorEntry entry : request.selectors()) {
            String type = entry.type() == null ? "css" : entry.type();
            try {
                var result = selectorValidationService.validateOne(entry.selector(), type, legacy.control());
                outcomes.add(new ValidateSelectorsResponse.SelectorOutcome(
                        entry.selector(), type, result.valid(),
                        result.count(),
                        result.error(),
                        result.elements()));
            } catch (com.visualspider.visualbrowser.internal.InvalidSelectorException ex) {
                outcomes.add(new ValidateSelectorsResponse.SelectorOutcome(
                        entry.selector(), type, false, 0,
                        ex.getMessage(), List.of()));
            }
        }
        return new ValidateSelectorsResponse(outcomes);
    }

    @PostMapping("/{sessionId}/preview")
    public PreviewResult preview(@PathVariable @NotNull String sessionId,
                                 @RequestBody PreviewRequest request) {
        ActorId actor = identityAccess.currentActor();
        requireOpenOwned(sessionId, actor);
        TaskDefinition definition = request.definition();
        if (definition == null) {
            throw new IllegalArgumentException("definition 不能为空");
        }
        var legacy = manager.legacySession(sessionId)
                .orElseThrow(() -> new VisualSessionNotFoundException(sessionId));
        // 在 lane 线程上对当前 Page 执行预览；不写库（spec §D11 / §D13）。
        return legacy.preview(definition, extractionPreview);
    }

    /**
     * 候选列表项推断（M4-2 #32 / spec §D3）：按视口坐标采集 DOM 摘要，调用 inferrer 输出
     * {@link InferredCandidateListItem}。前端用此驱动 list mode 配置流程。
     *
     * <p>仅做参数校验 + 所有权转交；不写库。坐标越界 / 元素未命中由 {@code PlaywrightControl}
     * 与 {@link ViewportMapper} 抛业务异常，由 {@code GlobalExceptionHandler} 映射稳定错误码。
     */
    @PostMapping("/{sessionId}/infer")
    public InferResponse infer(@PathVariable @NotNull String sessionId,
                               @RequestBody @Valid InferRequest request) {
        ActorId actor = identityAccess.currentActor();
        requireOpenOwned(sessionId, actor);
        int[] r = ViewportMapper.toRemote(request.x(), request.y(),
                request.clientWidth(), request.clientHeight());
        if (r == null) {
            throw new IllegalArgumentException("坐标越界: clientW=" + request.clientWidth()
                    + " clientH=" + request.clientHeight());
        }
        var legacy = manager.legacySession(sessionId)
                .orElseThrow(() -> new VisualSessionNotFoundException(sessionId));
        // captureDomSnapshot 在 lane 线程上 evaluate，不保存 ElementHandle（spec §D3）；
        // 阻塞语义与 legacy.preview 一致（不向 Web 线程泄漏 CompletableFuture）。
        com.visualspider.extraction.spi.DomSnapshot snap = legacy.captureDomSnapshot(r[0], r[1]);
        InferredCandidateListItem result = inferrer.infer(snap);
        return InferResponse.from(result);
    }

    /**
     * 列表模式受限预览（M4-6 #36 / spec §D9 / §D11）：
     * 在当前 Page 上对 {@code TaskDefinition} 的 {@code listItemRule} 取最多 20 条
     * preview，逐项 scope 到子树内提取字段。
     *
     * <p>与 {@link #preview} 共享 lane 线程模型；不写库。maxItems 写死 20（spec §D9 上限），
     * 重复请求由 {@code ExtractionPreviewImpl} 内部 cap 兜底。响应薄包装为
     * {@link ListPreviewResponse}（与 {@link InferResponse} 同模式）。
     */
    @PostMapping("/{sessionId}/preview-list")
    public ListPreviewResponse previewList(@PathVariable @NotNull String sessionId,
                                           @RequestBody PreviewRequest request) {
        ActorId actor = identityAccess.currentActor();
        requireOpenOwned(sessionId, actor);
        TaskDefinition definition = request.definition();
        if (definition == null) {
            throw new IllegalArgumentException("definition 不能为空");
        }
        var legacy = manager.legacySession(sessionId)
                .orElseThrow(() -> new VisualSessionNotFoundException(sessionId));
        return ListPreviewResponse.from(legacy.previewList(definition, extractionPreview, 20));
    }

    /**
     * 三类端点共用前置：actor + 所有权 + 非 CLOSED。CLOSED 视作已 not-found（与既有行为一致）。
     */
    private VisualSession requireOpenOwned(String sessionId, ActorId actor) {
        VisualSession owned = manager.requireOwnedBy(sessionId, actor);
        if (owned.lifecycle() == SessionLifecycleState.CLOSED) {
            throw new VisualSessionNotFoundException(sessionId);
        }
        return owned;
    }

    public record OpenRequest(@Positive long taskId) {}

    public record PreviewRequest(TaskDefinition definition) {}
}
