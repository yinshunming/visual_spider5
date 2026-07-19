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
}
