package com.visualspider.shared.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BusinessErrorCode 单元测试：覆盖每个错误码 code 字符串与 userMessage 非空。
 */
class BusinessErrorCodeTest {

    @Test
    @DisplayName("每个错误码都有非空 code 与 userMessage")
    void allCodesNonEmpty() {
        for (BusinessErrorCode code : BusinessErrorCode.values()) {
            assertThat(code.code()).isNotBlank();
            assertThat(code.userMessage()).isNotBlank();
            assertThat(code.httpStatus()).isPositive();
        }
    }

    @Test
    @DisplayName("错误码字符串唯一")
    void codesUnique() {
        long count = java.util.Arrays.stream(BusinessErrorCode.values())
                .map(BusinessErrorCode::code)
                .distinct()
                .count();
        assertThat(count).isEqualTo(BusinessErrorCode.values().length);
    }

    @Test
    @DisplayName("AUTH_REQUIRED 与 AUTH_INVALID_CREDENTIALS 都是 401")
    void authCodes401() {
        assertThat(BusinessErrorCode.AUTH_REQUIRED.httpStatus()).isEqualTo(401);
        assertThat(BusinessErrorCode.AUTH_INVALID_CREDENTIALS.httpStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("TASK_STALE_VERSION 是 409")
    void taskStaleVersionIs409() {
        assertThat(BusinessErrorCode.TASK_STALE_VERSION.httpStatus()).isEqualTo(409);
        assertThat(BusinessErrorCode.TASK_STALE_VERSION.code()).isEqualTo("TASK_STALE_VERSION");
    }

    @Test
    @DisplayName("SESSION_NOT_FOUND 是 404")
    void sessionNotFoundIs404() {
        assertThat(BusinessErrorCode.SESSION_NOT_FOUND.httpStatus()).isEqualTo(404);
        assertThat(BusinessErrorCode.SESSION_NOT_FOUND.code()).isEqualTo("SESSION_NOT_FOUND");
    }

    @Test
    @DisplayName("SESSION_NOT_OWNER 是 403")
    void sessionNotOwnerIs403() {
        assertThat(BusinessErrorCode.SESSION_NOT_OWNER.httpStatus()).isEqualTo(403);
        assertThat(BusinessErrorCode.SESSION_NOT_OWNER.code()).isEqualTo("SESSION_NOT_OWNER");
    }

    @Test
    @DisplayName("CONFIG_LANE_FULL 是 409")
    void configLaneFullIs409() {
        assertThat(BusinessErrorCode.CONFIG_LANE_FULL.httpStatus()).isEqualTo(409);
        assertThat(BusinessErrorCode.CONFIG_LANE_FULL.code()).isEqualTo("CONFIG_LANE_FULL");
    }

    @Test
    @DisplayName("TASK_NOT_DRAFT 是 409")
    void taskNotDraftIs409() {
        assertThat(BusinessErrorCode.TASK_NOT_DRAFT.httpStatus()).isEqualTo(409);
        assertThat(BusinessErrorCode.TASK_NOT_DRAFT.code()).isEqualTo("TASK_NOT_DRAFT");
    }

    @Test
    @DisplayName("TASK_INVALID_WAIT_POLICY 是 400（M3 spec §D19）")
    void taskInvalidWaitPolicyIs400() {
        assertThat(BusinessErrorCode.TASK_INVALID_WAIT_POLICY.httpStatus()).isEqualTo(400);
        assertThat(BusinessErrorCode.TASK_INVALID_WAIT_POLICY.code())
                .isEqualTo("TASK_INVALID_WAIT_POLICY");
        assertThat(BusinessErrorCode.TASK_INVALID_WAIT_POLICY.userMessage()).isNotBlank();
    }
}
