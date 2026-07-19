package com.visualspider.task.domain;

import java.util.List;

/**
 * 校验结果。
 *
 * <p>M1-3 仅产出失败列表；不区分 warning/error。
 * 失败时 {@code ready == false} + {@code errors} 非空；
 * 成功时 {@code ready == true} + {@code errors} 空。
 *
 * @param ready 是否可继续（READY 状态）
 * @param errors 错误条目；每条含 {@code code}（业务错误码字符串）+ {@code message}（中文用户消息）+ 可选 {@code fieldPath}
 */
public record ReadinessReport(
        boolean ready,
        List<ReadinessError> errors) {

    public static ReadinessReport success() {
        return new ReadinessReport(true, List.of());
    }

    public static ReadinessReport failure(List<ReadinessError> errors) {
        return new ReadinessReport(false, List.copyOf(errors));
    }

    public record ReadinessError(String code, String message, String fieldPath) {
    }
}
