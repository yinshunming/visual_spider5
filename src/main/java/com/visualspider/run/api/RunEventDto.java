package com.visualspider.run.api;

import com.visualspider.result.spi.RunEvent;
import java.time.Instant;

/**
 * {@link RunEvent} 的 API 层扁平 record（spec §D17）。
 *
 * <p>{@code createdAt} 暴露为 epoch millis，便于前端无歧义解析；如需 ISO 字符串由前端转换。
 */
public record RunEventDto(
        long id,
        long runId,
        String level,
        String stage,
        String url,
        String errorCode,
        String message,
        Instant createdAt) {

    public static RunEventDto of(RunEvent e) {
        return new RunEventDto(
                e.id(),
                e.runId(),
                e.level().name(),
                e.stage(),
                e.url(),
                e.errorCode(),
                e.message(),
                e.createdAt());
    }
}
