package com.visualspider.shared.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.visualspider.identity.domain.exceptions.AuthenticationFailedException;
import com.visualspider.identity.domain.exceptions.DuplicateUsernameException;
import com.visualspider.identity.domain.exceptions.NotAuthenticatedException;
import com.visualspider.identity.domain.exceptions.UserNotFoundException;
import com.visualspider.identity.domain.exceptions.WeakPasswordException;
import com.visualspider.task.domain.exceptions.StaleTaskVersionException;
import com.visualspider.task.domain.exceptions.TaskNotFoundException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

/**
 * GlobalExceptionHandler 单元测试：每个异常 → 对应 ApiError JSON 形状。
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("AuthenticationFailedException → 401 + AUTH_INVALID_CREDENTIALS")
    void authFailed() {
        ResponseEntity<ApiError> res = handler.handleAuthFailed(AuthenticationFailedException.invalidCredentials());
        assertThat(res.getStatusCode().value()).isEqualTo(401);
        assertThat(res.getBody().code()).isEqualTo("AUTH_INVALID_CREDENTIALS");
        assertThat(res.getBody().message()).isNotBlank();
    }

    @Test
    @DisplayName("NotAuthenticatedException → 401 + AUTH_REQUIRED")
    void notAuth() {
        ResponseEntity<ApiError> res = handler.handleNotAuth(NotAuthenticatedException.becauseSessionMissing());
        assertThat(res.getStatusCode().value()).isEqualTo(401);
        assertThat(res.getBody().code()).isEqualTo("AUTH_REQUIRED");
    }

    @Test
    @DisplayName("StaleTaskVersionException → 409 + TASK_STALE_VERSION")
    void staleTask() {
        ResponseEntity<ApiError> res = handler.handleStaleTask(new StaleTaskVersionException(1L, 0L, 1L));
        assertThat(res.getStatusCode().value()).isEqualTo(409);
        assertThat(res.getBody().code()).isEqualTo("TASK_STALE_VERSION");
    }

    @Test
    @DisplayName("AccessDeniedException → 403 + ACCESS_DENIED")
    void accessDenied() {
        ResponseEntity<ApiError> res = handler.handleAccessDenied(new AccessDeniedException("无权"));
        assertThat(res.getStatusCode().value()).isEqualTo(403);
        assertThat(res.getBody().code()).isEqualTo("ACCESS_DENIED");
    }

    @Test
    @DisplayName("DuplicateUsernameException → 409 + DUPLICATE_USERNAME")
    void duplicateUsername() {
        ResponseEntity<ApiError> res = handler.handleDuplicateUsername(new DuplicateUsernameException("alice"));
        assertThat(res.getStatusCode().value()).isEqualTo(409);
        assertThat(res.getBody().code()).isEqualTo("DUPLICATE_USERNAME");
    }

    @Test
    @DisplayName("WeakPasswordException → 400 + WEAK_PASSWORD")
    void weakPassword() {
        ResponseEntity<ApiError> res = handler.handleWeakPassword(new WeakPasswordException());
        assertThat(res.getStatusCode().value()).isEqualTo(400);
        assertThat(res.getBody().code()).isEqualTo("WEAK_PASSWORD");
    }

    @Test
    @DisplayName("TaskNotFoundException → 404 + RESOURCE_NOT_FOUND")
    void taskNotFound() {
        ResponseEntity<ApiError> res = handler.handleTaskNotFound(new TaskNotFoundException(99L));
        assertThat(res.getStatusCode().value()).isEqualTo(404);
        assertThat(res.getBody().code()).isEqualTo("RESOURCE_NOT_FOUND");
    }

    @Test
    @DisplayName("UserNotFoundException → 404 + RESOURCE_NOT_FOUND")
    void userNotFound() {
        ResponseEntity<ApiError> res = handler.handleUserNotFound(new UserNotFoundException(99L));
        assertThat(res.getStatusCode().value()).isEqualTo(404);
        assertThat(res.getBody().code()).isEqualTo("RESOURCE_NOT_FOUND");
    }

    @Test
    @DisplayName("TaskInvalidDefinitionException 含 TASK_INVALID_WAIT_POLICY → 400 + TASK_INVALID_WAIT_POLICY")
    void taskInvalidDefinitionWaitPolicy() {
        var err = new com.visualspider.task.domain.ReadinessReport.ReadinessError(
                "TASK_INVALID_WAIT_POLICY", "额外等待时间必须 0-5 秒", "waitPolicy.extraWaitSeconds");
        var ex = new com.visualspider.task.domain.exceptions.TaskInvalidDefinitionException(List.of(err));
        ResponseEntity<ApiError> res = handler.handleTaskInvalidDefinition(ex);
        assertThat(res.getStatusCode().value()).isEqualTo(400);
        assertThat(res.getBody().code()).isEqualTo("TASK_INVALID_WAIT_POLICY");
        assertThat(res.getBody().fieldPath()).isEqualTo("waitPolicy.extraWaitSeconds");
    }

    @Test
    @DisplayName("MethodArgumentNotValidException → 400 + VALIDATION_FAILED + fieldPath")
    void beanValidation() {
        var bindingResult = new BeanPropertyBindingResult(new Object(), "obj");
        bindingResult.addError(new FieldError("obj", "username", "must not be blank"));
        var ex = new MethodArgumentNotValidException((org.springframework.core.MethodParameter) null, bindingResult);

        ResponseEntity<ApiError> res = handler.handleBeanValidation(ex);
        assertThat(res.getStatusCode().value()).isEqualTo(400);
        assertThat(res.getBody().code()).isEqualTo("VALIDATION_FAILED");
        assertThat(res.getBody().fieldPath()).isEqualTo("username");
    }

    @Test
    @DisplayName("ConstraintViolationException → 400 + VALIDATION_FAILED + fieldPath")
    void constraintViolation() {
        @SuppressWarnings("unchecked")
        ConstraintViolation<Object> violation = mock(ConstraintViolation.class);
        when(violation.getMessage()).thenReturn("must not be null");
        jakarta.validation.Path path = mock(jakarta.validation.Path.class);
        when(path.toString()).thenReturn("username");
        when(violation.getPropertyPath()).thenReturn(path);

        ResponseEntity<ApiError> res = handler.handleConstraintViolation(
                new ConstraintViolationException("violations", Set.of(violation)));
        assertThat(res.getStatusCode().value()).isEqualTo(400);
        assertThat(res.getBody().code()).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    @DisplayName("其它 RuntimeException → 500 + INTERNAL_ERROR（不暴露堆栈）")
    void runtimeFallback() {
        ResponseEntity<ApiError> res = handler.handleRuntime(new IllegalStateException("secret detail"));
        assertThat(res.getStatusCode().value()).isEqualTo(500);
        assertThat(res.getBody().code()).isEqualTo("INTERNAL_ERROR");
        // 不暴露 ex.getMessage()
        assertThat(res.getBody().message()).doesNotContain("secret detail");
    }

    @Test
    @DisplayName("RunNotFoundException → 404 + RUN_NOT_FOUND")
    void runNotFound() {
        ResponseEntity<ApiError> res = handler.handleRunNotFound(
                new com.visualspider.run.internal.RunNotFoundException(7L));
        assertThat(res.getStatusCode().value()).isEqualTo(404);
        assertThat(res.getBody().code()).isEqualTo("RUN_NOT_FOUND");
    }

    @Test
    @DisplayName("RunNotOwnerException → 403 + RUN_NOT_OWNER")
    void runNotOwner() {
        ResponseEntity<ApiError> res = handler.handleRunNotOwner(
                new com.visualspider.run.internal.RunNotOwnerException(7L));
        assertThat(res.getStatusCode().value()).isEqualTo(403);
        assertThat(res.getBody().code()).isEqualTo("RUN_NOT_OWNER");
    }

    @Test
    @DisplayName("UserRunLimitException → 409 + USER_RUN_LIMIT")
    void userRunLimit() {
        ResponseEntity<ApiError> res = handler.handleUserRunLimit(
                new com.visualspider.run.internal.UserRunLimitException(7L));
        assertThat(res.getStatusCode().value()).isEqualTo(409);
        assertThat(res.getBody().code()).isEqualTo("USER_RUN_LIMIT");
    }

    @Test
    @DisplayName("TaskNotReadyException → 409 + TASK_NOT_READY")
    void taskNotReady() {
        ResponseEntity<ApiError> res = handler.handleTaskNotReady(
                new com.visualspider.run.internal.TaskNotReadyException(7L, "校验未通过"));
        assertThat(res.getStatusCode().value()).isEqualTo(409);
        assertThat(res.getBody().code()).isEqualTo("TASK_NOT_READY");
    }

    @Test
    @DisplayName("RunNotCancellableException → 409 + RUN_NOT_CANCELLABLE")
    void runNotCancellable() {
        ResponseEntity<ApiError> res = handler.handleRunNotCancellable(
                new com.visualspider.run.internal.RunNotCancellableException(
                        7L, com.visualspider.run.spi.RunState.SUCCESS));
        assertThat(res.getStatusCode().value()).isEqualTo(409);
        assertThat(res.getBody().code()).isEqualTo("RUN_NOT_CANCELLABLE");
    }
}
