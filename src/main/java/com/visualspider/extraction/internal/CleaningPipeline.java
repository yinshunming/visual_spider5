package com.visualspider.extraction.internal;

import com.visualspider.extraction.spi.ExtractionDiagnostic;
import com.visualspider.extraction.spi.ExtractionDiagnostic.DiagnosticCode;
import com.visualspider.task.domain.FieldDefinition;
import com.visualspider.task.domain.ResultType;
import java.math.BigDecimal;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 字段清洗管道（M2-3 #19）：trim → regex (capture group) → type convert → empty detection。
 *
 * <p>失败时仍保留 rawValue，附诊断 enum。preview 与 run 共用同一实现，确保预览可用则可跑。
 */
@Component
public final class CleaningPipeline {

    public Result clean(String rawValue, FieldDefinition field, ResultCollector collector) {
        Result result = new Result();
        result.rawValue = rawValue;
        result.cleanedValue = rawValue;
        String step = rawValue;

        if (step == null || step.isBlank()) {
            result.isEmpty = true;
            result.cleanedValue = null;
            collector.add(field, DiagnosticCode.FIELD_EMPTY, "原值为空");
            return result;
        }

        if (field.trim() == com.visualspider.task.domain.TrimPolicy.TRIM) {
            step = step.trim();
        }

        if (field.regex() != null && !field.regex().isBlank()) {
            Pattern pattern;
            try {
                pattern = Pattern.compile(field.regex());
            } catch (RuntimeException ex) {
                result.isEmpty = true;
                result.cleanedValue = null;
                collector.add(field, DiagnosticCode.REGEX_NO_MATCH, "正则不合法: " + ex.getMessage());
                return result;
            }
            Matcher matcher = pattern.matcher(step);
            if (matcher.find()) {
                if (matcher.groupCount() >= 1 && matcher.group(1) != null) {
                    step = matcher.group(1);
                } else {
                    step = matcher.group(0);
                }
            } else {
                // 全部不匹配 → null + REGEX_NO_MATCH（raw value 保留）
                collector.add(field, DiagnosticCode.REGEX_NO_MATCH, "正则未匹配");
                result.rawValue = rawValue;
                result.cleanedValue = null;
                result.isEmpty = true;
                return result;
            }
        }

        step = convertType(step, field, collector);
        result.cleanedValue = step;
        return finalizeEmpty(result, step);
    }

    private String convertType(String value, FieldDefinition field, ResultCollector collector) {
        ResultType type = field.resultType();
        if (type == null) {
            return value;
        }
        return switch (type) {
            case TEXT -> value;
            case NUMBER -> {
                try {
                    new BigDecimal(value);
                    yield value;
                } catch (NumberFormatException ex) {
                    collector.add(field, DiagnosticCode.TYPE_CONVERSION_FAILED, "非数字: " + ex.getMessage());
                    yield value; // raw 保留
                }
            }
            case URL -> {
                try {
                    URI uri = URI.create(value);
                    String scheme = uri.getScheme();
                    if (scheme == null
                            || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                        collector.add(field, DiagnosticCode.TYPE_CONVERSION_FAILED, "scheme 非 http(s)");
                        yield value;
                    }
                    yield value;
                } catch (IllegalArgumentException ex) {
                    collector.add(field, DiagnosticCode.TYPE_CONVERSION_FAILED, "URL 非法: " + ex.getMessage());
                    yield value;
                }
            }
        };
    }

    private Result finalizeEmpty(Result r, String value) {
        if (value == null || value.isBlank()) {
            r.isEmpty = true;
            r.cleanedValue = null;
        }
        return r;
    }

    public static final class Result {
        public String rawValue;
        public String cleanedValue;
        public boolean isEmpty;
    }

    /** 收集诊断：模块内简单 list 聚合，避免 {@code ExtractionPreviewImpl} 重复实现。 */
    public static final class ResultCollector {
        private final List<ExtractionDiagnostic> all = new ArrayList<>();

        public void add(FieldDefinition field, DiagnosticCode code, String message) {
            all.add(new ExtractionDiagnostic(
                    code, field == null ? null : field.name(), message, null));
        }

        public List<ExtractionDiagnostic> immutable() {
            return List.copyOf(all);
        }
    }
}
