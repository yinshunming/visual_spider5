package com.visualspider.shared.api;

import com.visualspider.identity.domain.exceptions.AuthenticationFailedException;
import com.visualspider.identity.domain.exceptions.DuplicateUsernameException;
import com.visualspider.identity.domain.exceptions.NotAuthenticatedException;
import com.visualspider.identity.domain.exceptions.UserNotFoundException;
import com.visualspider.identity.domain.exceptions.WeakPasswordException;
import com.visualspider.result.spi.RunAccessDeniedException;
import com.visualspider.run.internal.RunNotCancellableException;
import com.visualspider.run.internal.RunNotFoundException;
import com.visualspider.run.internal.RunNotOwnerException;
import com.visualspider.run.internal.TaskNotReadyException;
import com.visualspider.run.internal.UserRunLimitException;
import com.visualspider.task.domain.exceptions.StaleTaskVersionException;
import com.visualspider.task.domain.exceptions.TaskInvalidDefinitionException;
import com.visualspider.task.domain.exceptions.TaskNotFoundException;
import com.visualspider.visualbrowser.internal.ConfigLaneFullException;
import com.visualspider.visualbrowser.internal.EditingBuffer;
import com.visualspider.visualbrowser.internal.InvalidSelectorException;
import com.visualspider.visualbrowser.internal.VisualSessionNotFoundException;
import com.visualspider.visualbrowser.internal.VisualSessionNotOwnerException;
import jakarta.validation.ConstraintViolationException;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 统一异常映射（spec §D7）。
 *
 * <p>覆盖：
 * <ul>
 *   <li>业务异常 → 业务码 + 用户消息</li>
 *   <li>{@link MethodArgumentNotValidException} / {@link ConstraintViolationException} → 第一个字段错误 + fieldPath</li>
 *   <li>{@link AuthenticationFailedException} → 401 + {@code AUTH_INVALID_CREDENTIALS}</li>
 *   <li>{@link NotAuthenticatedException} → 401 + {@code AUTH_REQUIRED}</li>
 *   <li>{@link StaleTaskVersionException} → 409 + {@code TASK_STALE_VERSION}</li>
 *   <li>{@link AccessDeniedException} → 403 + {@code ACCESS_DENIED}</li>
 *   <li>其它 {@link RuntimeException} → 500 + {@code INTERNAL_ERROR}，响应体不暴露堆栈与 ex.getMessage()</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // ---------- 业务异常（spec 显式列举）----------

    @ExceptionHandler(AuthenticationFailedException.class)
    public ResponseEntity<ApiError> handleAuthFailed(AuthenticationFailedException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiError.of(BusinessErrorCode.AUTH_INVALID_CREDENTIALS));
    }

    @ExceptionHandler(NotAuthenticatedException.class)
    public ResponseEntity<ApiError> handleNotAuth(NotAuthenticatedException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiError.of(BusinessErrorCode.AUTH_REQUIRED));
    }

    @ExceptionHandler(StaleTaskVersionException.class)
    public ResponseEntity<ApiError> handleStaleTask(StaleTaskVersionException ex) {
        LOG.info("stale task version: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of(BusinessErrorCode.TASK_STALE_VERSION));
    }

    @ExceptionHandler(DuplicateUsernameException.class)
    public ResponseEntity<ApiError> handleDuplicateUsername(DuplicateUsernameException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of(BusinessErrorCode.DUPLICATE_USERNAME, ex.getMessage()));
    }

    @ExceptionHandler(WeakPasswordException.class)
    public ResponseEntity<ApiError> handleWeakPassword(WeakPasswordException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.of(BusinessErrorCode.WEAK_PASSWORD));
    }

    @ExceptionHandler(TaskNotFoundException.class)
    public ResponseEntity<ApiError> handleTaskNotFound(TaskNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of(BusinessErrorCode.RESOURCE_NOT_FOUND, ex.getMessage()));
    }

    @ExceptionHandler(TaskInvalidDefinitionException.class)
    public ResponseEntity<ApiError> handleTaskInvalidDefinition(TaskInvalidDefinitionException ex) {
        var first = ex.firstError();
        // 第一条错误用作 envelope；fieldPath 与 code 透传
        BusinessErrorCode mapped = switch (first.code()) {
            case "TASK_INVALID_URL" -> BusinessErrorCode.TASK_INVALID_URL;
            case "TASK_INVALID_VIEWPORT" -> BusinessErrorCode.TASK_INVALID_VIEWPORT;
            case "TASK_DUPLICATE_FIELD" -> BusinessErrorCode.TASK_DUPLICATE_FIELD;
            case "TASK_INVALID_FIELD_NAME" -> BusinessErrorCode.TASK_INVALID_FIELD_NAME;
            case "TASK_UNSUPPORTED_SCHEMA" -> BusinessErrorCode.TASK_UNSUPPORTED_SCHEMA;
            case "TASK_INVALID_MODE" -> BusinessErrorCode.TASK_INVALID_MODE;
            case "TASK_INVALID_WAIT_POLICY" -> BusinessErrorCode.TASK_INVALID_WAIT_POLICY;
            default -> BusinessErrorCode.TASK_INVALID_DEFINITION;
        };
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.of(mapped, first.message(), first.fieldPath()));
    }

    @ExceptionHandler(EditingBuffer.EditBufferConflictException.class)
    public ResponseEntity<ApiError> handleEditBufferConflict(EditingBuffer.EditBufferConflictException ex) {
        LOG.info("edit buffer conflict: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of(BusinessErrorCode.EDIT_BUFFER_CONFLICT));
    }

    @ExceptionHandler(InvalidSelectorException.class)
    public ResponseEntity<ApiError> handleInvalidSelector(InvalidSelectorException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.of(BusinessErrorCode.TASK_INVALID_SELECTOR, ex.getMessage(),
                        ex.kind()));
    }

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ApiError> handleUserNotFound(UserNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of(BusinessErrorCode.RESOURCE_NOT_FOUND, ex.getMessage()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiError.of(BusinessErrorCode.ACCESS_DENIED));
    }

    // ---------- Bean Validation ----------

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleBeanValidation(MethodArgumentNotValidException ex) {
        FieldError fe = ex.getBindingResult().getFieldErrors().stream().findFirst().orElse(null);
        if (fe == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiError.of(BusinessErrorCode.VALIDATION_FAILED));
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.of(BusinessErrorCode.VALIDATION_FAILED,
                        fe.getDefaultMessage() == null ? "校验失败" : fe.getDefaultMessage(),
                        fe.getField()));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraintViolation(ConstraintViolationException ex) {
        String message = ex.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + " " + v.getMessage())
                .collect(Collectors.joining("; "));
        String fieldPath = ex.getConstraintViolations().stream()
                .findFirst()
                .map(v -> v.getPropertyPath().toString())
                .orElse(null);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.of(BusinessErrorCode.VALIDATION_FAILED,
                        message.isEmpty() ? "校验失败" : message,
                        fieldPath));
    }


    @ExceptionHandler(VisualSessionNotFoundException.class)
    public ResponseEntity<ApiError> handleSessionNotFound(VisualSessionNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of(BusinessErrorCode.SESSION_NOT_FOUND, ex.getMessage()));
    }

    @ExceptionHandler(VisualSessionNotOwnerException.class)
    public ResponseEntity<ApiError> handleSessionNotOwner(VisualSessionNotOwnerException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiError.of(BusinessErrorCode.SESSION_NOT_OWNER, ex.getMessage()));
    }

    @ExceptionHandler(ConfigLaneFullException.class)
    public ResponseEntity<ApiError> handleConfigLaneFull(ConfigLaneFullException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of(BusinessErrorCode.CONFIG_LANE_FULL));
    }

@ExceptionHandler(RunAccessDeniedException.class)
    public ResponseEntity<ApiError> handleRunAccessDenied(RunAccessDeniedException ex) {
        // 非 owner 且非 admin 访问 run 结果 -> RESOURCE_NOT_FOUND（不回显存在性，spec §D12）
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of(BusinessErrorCode.RESOURCE_NOT_FOUND, "运行不存在"));
    }

    // ---------- M3 运行异常（spec §D19）----------

    @ExceptionHandler(RunNotFoundException.class)
    public ResponseEntity<ApiError> handleRunNotFound(RunNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of(BusinessErrorCode.RUN_NOT_FOUND, ex.getMessage()));
    }

    @ExceptionHandler(RunNotOwnerException.class)
    public ResponseEntity<ApiError> handleRunNotOwner(RunNotOwnerException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiError.of(BusinessErrorCode.RUN_NOT_OWNER, ex.getMessage()));
    }

    @ExceptionHandler(UserRunLimitException.class)
    public ResponseEntity<ApiError> handleUserRunLimit(UserRunLimitException ex) {
        LOG.info("user run limit: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of(BusinessErrorCode.USER_RUN_LIMIT));
    }

    @ExceptionHandler(TaskNotReadyException.class)
    public ResponseEntity<ApiError> handleTaskNotReady(TaskNotReadyException ex) {
        LOG.info("task not ready: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of(BusinessErrorCode.TASK_NOT_READY, ex.getMessage()));
    }

    @ExceptionHandler(RunNotCancellableException.class)
    public ResponseEntity<ApiError> handleRunNotCancellable(RunNotCancellableException ex) {
        LOG.info("run not cancellable: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of(BusinessErrorCode.RUN_NOT_CANCELLABLE, ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.of(BusinessErrorCode.VALIDATION_FAILED, ex.getMessage()));
    }

    // ---------- 兜底 ----------

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ApiError> handleRuntime(RuntimeException ex) {
        LOG.error("unhandled runtime exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of(BusinessErrorCode.INTERNAL_ERROR));
    }
}
