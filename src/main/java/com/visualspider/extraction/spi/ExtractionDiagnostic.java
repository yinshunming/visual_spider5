package com.visualspider.extraction.spi;

/**
 * 提取诊断（M2-3 #19）：每个 {@link PreviewResult.FieldOutcome} 或全屏预览可携带多条诊断。
 *
 * <p>仅在 {@code ExtractionPreview} 与未来 run pipeline 中使用；不影响 {@code BusinessErrorCode}。
 */
public record ExtractionDiagnostic(DiagnosticCode code,
                                    String fieldName,
                                    String userMessage,
                                    String detailPath) {

    public enum DiagnosticCode {
        SELECTOR_SYNTAX_INVALID,
        ZERO_MATCH,
        MULTIPLE_MATCH,
        REGEX_NO_MATCH,
        TYPE_CONVERSION_FAILED,
        ATTRIBUTE_MISSING,
        FIELD_EMPTY
    }
}
