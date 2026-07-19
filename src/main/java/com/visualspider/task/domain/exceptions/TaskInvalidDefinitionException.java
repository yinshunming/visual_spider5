package com.visualspider.task.domain.exceptions;

import com.visualspider.task.domain.ReadinessReport;
import java.util.List;

/**
 * 任务定义非法（{@link TaskReadiness#validate} 返回 notReady）。HTTP 400 + 首个错误码。
 */
public class TaskInvalidDefinitionException extends RuntimeException {

    private final List<ReadinessReport.ReadinessError> errors;

    public TaskInvalidDefinitionException(List<ReadinessReport.ReadinessError> errors) {
        super("任务定义非法：errors=" + errors.size());
        this.errors = List.copyOf(errors);
    }

    public List<ReadinessReport.ReadinessError> errors() {
        return errors;
    }

    public ReadinessReport.ReadinessError firstError() {
        return errors.get(0);
    }
}
