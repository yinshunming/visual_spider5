package com.visualspider.shared.api;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 统一错误响应 DTO（spec §D7）。
 *
 * <p>{@code fieldPath} 仅在字段级错误（{@code @Valid} / Bean Validation）时非空。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
        String code,
        String message,
        String fieldPath) {

    public static ApiError of(BusinessErrorCode code) {
        return new ApiError(code.code(), code.userMessage(), null);
    }

    public static ApiError of(BusinessErrorCode code, String message) {
        return new ApiError(code.code(), message, null);
    }

    public static ApiError of(BusinessErrorCode code, String message, String fieldPath) {
        return new ApiError(code.code(), message, fieldPath);
    }
}
