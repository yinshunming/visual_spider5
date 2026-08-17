package com.visualspider.shared.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.visualspider.shared.api.GlobalExceptionHandler;
import com.visualspider.visualbrowser.internal.ConfigLaneFullException;
import com.visualspider.visualbrowser.internal.VisualSessionNotFoundException;
import com.visualspider.visualbrowser.internal.VisualSessionNotOwnerException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * GlobalExceptionHandler 对 M2 配置会话异常的映射测试。
 */
class GlobalExceptionHandlerVisualSessionTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("VisualSessionNotFoundException → 404 + SESSION_NOT_FOUND")
    void sessionNotFound() {
        ResponseEntity<ApiError> res = handler.handleSessionNotFound(
                new VisualSessionNotFoundException("s1"));
        assertThat(res.getStatusCode().value()).isEqualTo(404);
        assertThat(res.getBody().code()).isEqualTo("SESSION_NOT_FOUND");
    }

    @Test
    @DisplayName("VisualSessionNotOwnerException → 403 + SESSION_NOT_OWNER")
    void sessionNotOwner() {
        ResponseEntity<ApiError> res = handler.handleSessionNotOwner(
                new VisualSessionNotOwnerException("s1"));
        assertThat(res.getStatusCode().value()).isEqualTo(403);
        assertThat(res.getBody().code()).isEqualTo("SESSION_NOT_OWNER");
    }

    @Test
    @DisplayName("ConfigLaneFullException → 409 + CONFIG_LANE_FULL")
    void configLaneFull() {
        ResponseEntity<ApiError> res = handler.handleConfigLaneFull(
                new ConfigLaneFullException());
        assertThat(res.getStatusCode().value()).isEqualTo(409);
        assertThat(res.getBody().code()).isEqualTo("CONFIG_LANE_FULL");
    }
}
