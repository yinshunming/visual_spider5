package com.visualspider.extraction.spi;

import java.util.List;

/**
 * 预览结果（M2-3 #19）。
 *
 * <p>{@code fieldOutcomes} 与 {@code diagnostics} 共同描述：每个字段同时含原始/最终/是否为空；
 * 错误诊断带 enum 与可选字段名 / 选择器细节。
 */
public record PreviewResult(List<FieldOutcome> fieldOutcomes, List<ExtractionDiagnostic> diagnostics) {

    public record FieldOutcome(String fieldName,
                               String rawValue,
                               String cleanedValue,
                               boolean isEmpty) {}
}
