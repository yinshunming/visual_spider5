package com.visualspider.visualbrowser.api;

import com.visualspider.extraction.spi.ExtractionDiagnostic;
import com.visualspider.extraction.spi.ExtractionPreview.ListPreviewResult;
import com.visualspider.extraction.spi.PreviewResult;
import java.util.List;

/**
 * {@code POST /api/visual-sessions/{sessionId}/preview-list} 响应薄包装（M4-6 #36 / spec §D11）。
 *
 * <p>api 包不应穿透 {@link ExtractionPreview} SPI 类型；与既有 {@link InferResponse}
 * 同样的薄包装模式，把 SPI record 转成本包 record，便于：
 * <ul>
 *   <li>前端契约字段名稳定（不依赖 SPI 内部 record 名称）</li>
 *   <li>未来 SPI 演进不影响 API 契约</li>
 * </ul>
 */
public record ListPreviewResponse(List<PreviewResponse> previews,
                                  int totalMatchCount,
                                  List<ExtractionDiagnosticDto> diagnostics) {

    public static ListPreviewResponse from(ListPreviewResult r) {
        List<PreviewResponse> previews = r.previews() == null
                ? List.of()
                : r.previews().stream().map(PreviewResponse::from).toList();
        List<ExtractionDiagnosticDto> diagnostics = r.diagnostics() == null
                ? List.of()
                : r.diagnostics().stream().map(ExtractionDiagnosticDto::from).toList();
        return new ListPreviewResponse(previews, r.totalMatchCount(), diagnostics);
    }

    public record PreviewResponse(List<FieldOutcomeDto> fieldOutcomes, List<ExtractionDiagnosticDto> diagnostics) {
        public static PreviewResponse from(PreviewResult r) {
            List<FieldOutcomeDto> outs = r.fieldOutcomes() == null
                    ? List.of()
                    : r.fieldOutcomes().stream().map(FieldOutcomeDto::from).toList();
            List<ExtractionDiagnosticDto> diags = r.diagnostics() == null
                    ? List.of()
                    : r.diagnostics().stream().map(ExtractionDiagnosticDto::from).toList();
            return new PreviewResponse(outs, diags);
        }
    }

    public record FieldOutcomeDto(String fieldName, String rawValue, String cleanedValue, boolean isEmpty) {
        public static FieldOutcomeDto from(PreviewResult.FieldOutcome o) {
            return new FieldOutcomeDto(o.fieldName(), o.rawValue(), o.cleanedValue(), o.isEmpty());
        }
    }

    public record ExtractionDiagnosticDto(String code, String fieldName, String userMessage) {
        public static ExtractionDiagnosticDto from(ExtractionDiagnostic d) {
            // DiagnosticCode 是 enum；前端契约走 .name() 拿到稳定字符串
            return new ExtractionDiagnosticDto(d.code().name(), d.fieldName(), d.userMessage());
        }
    }
}