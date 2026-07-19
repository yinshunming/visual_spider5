package com.visualspider.task.internal;

import com.visualspider.identity.domain.ActorId;
import com.visualspider.shared.api.BusinessErrorCode;
import com.visualspider.task.domain.FieldDefinition;
import com.visualspider.task.domain.ReadinessReport;
import com.visualspider.task.domain.TaskDefinition;
import com.visualspider.task.domain.Viewport;
import com.visualspider.task.spi.TaskReadiness;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * {@link TaskReadiness} 的默认实现（M1-3 最小集）。
 *
 * <p>校验失败时返回包含 {@code code} + {@code message} + 可选 {@code fieldPath} 的错误列表；
 * code 与 M1-4 错误码 enum 对齐。
 */
@Service
public class TaskReadinessImpl implements TaskReadiness {

    private static final int EXPECTED_SCHEMA_VERSION = 1;

    @Override
    public ReadinessReport validate(TaskDefinition draft) {
        List<ReadinessReport.ReadinessError> errors = new ArrayList<>();

        if (draft == null) {
            errors.add(error(BusinessErrorCode.TASK_INVALID_DEFINITION,
                    BusinessErrorCode.TASK_INVALID_DEFINITION.userMessage(), null));
            return ReadinessReport.failure(errors);
        }
        if (draft.schemaVersion() != EXPECTED_SCHEMA_VERSION) {
            errors.add(error(BusinessErrorCode.TASK_UNSUPPORTED_SCHEMA,
                    "暂不支持的 schema 版本：" + draft.schemaVersion() + "（M1 固定为 1）",
                    "schemaVersion"));
        }
        if (draft.mode() == null) {
            errors.add(error(BusinessErrorCode.TASK_INVALID_MODE,
                    BusinessErrorCode.TASK_INVALID_MODE.userMessage(), "mode"));
        }
        if (draft.startUrl() == null || draft.startUrl().isBlank()) {
            errors.add(error(BusinessErrorCode.TASK_INVALID_URL,
                    "起始 URL 不能为空", "startUrl"));
        } else {
            try {
                URI uri = URI.create(draft.startUrl());
                String scheme = uri.getScheme();
                if (scheme == null || !(scheme.equals("http") || scheme.equals("https"))) {
                    errors.add(error(BusinessErrorCode.TASK_INVALID_URL,
                            "起始 URL 必须为 http(s)：" + draft.startUrl(),
                            "startUrl"));
                } else if (uri.getHost() == null || uri.getHost().isBlank()) {
                    errors.add(error(BusinessErrorCode.TASK_INVALID_URL,
                            "起始 URL 缺少主机：" + draft.startUrl(),
                            "startUrl"));
                }
            } catch (IllegalArgumentException e) {
                errors.add(error(BusinessErrorCode.TASK_INVALID_URL,
                        "起始 URL 解析失败：" + e.getMessage(),
                        "startUrl"));
            }
        }
        if (draft.viewport() == null || draft.viewport().width() != Viewport.DEFAULT.width()
                || draft.viewport().height() != Viewport.DEFAULT.height()) {
            errors.add(error(BusinessErrorCode.TASK_INVALID_VIEWPORT,
                    BusinessErrorCode.TASK_INVALID_VIEWPORT.userMessage(),
                    "viewport"));
        }

        if (draft.fields() != null) {
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < draft.fields().size(); i++) {
                FieldDefinition f = draft.fields().get(i);
                if (f == null || f.name() == null || f.name().isBlank()) {
                    errors.add(error(BusinessErrorCode.TASK_INVALID_FIELD_NAME,
                            "字段名不能为空", "fields[" + i + "].name"));
                    continue;
                }
                if (!seen.add(f.name())) {
                    errors.add(error(BusinessErrorCode.TASK_DUPLICATE_FIELD,
                            "字段名重复：" + f.name(), "fields[" + i + "].name"));
                }
            }
        }

        return errors.isEmpty() ? ReadinessReport.success() : ReadinessReport.failure(errors);
    }

    @Override
    public ReadinessReport validateForRun(long taskId, ActorId actor) {
        throw new UnsupportedOperationException("TaskReadiness.validateForRun M3 启用");
    }

    private static ReadinessReport.ReadinessError error(BusinessErrorCode code, String message, String fieldPath) {
        return new ReadinessReport.ReadinessError(code.code(), message, fieldPath);
    }
}
