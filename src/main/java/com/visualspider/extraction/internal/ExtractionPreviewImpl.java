package com.visualspider.extraction.internal;

import com.visualspider.extraction.spi.ExtractionDiagnostic.DiagnosticCode;
import com.visualspider.extraction.spi.ExtractionPreview;
import com.visualspider.extraction.spi.ExtractionPreview.DomState;
import com.visualspider.extraction.spi.ExtractionPreview.Node;
import com.visualspider.extraction.spi.PreviewResult;
import com.visualspider.extraction.spi.PreviewResult.FieldOutcome;
import com.visualspider.task.domain.FieldDefinition;
import com.visualspider.task.domain.FieldSource;
import com.visualspider.task.domain.TaskDefinition;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * {@link ExtractionPreview} 默认实现（M2-3 #19 / spec §D11）。
 *
 * <p>在给定 {@link DomState} 上对每个字段读取原始值并经 {@link CleaningPipeline} 清洗；
 * 返回原始/最终/是否为空 + 诊断。M3 运行引擎复用同一实现，保证"预览可用则可跑"。
 *
 * <p>不修改任何持久化数据；DOM 查询由调用方在 lane 线程上提供 {@code DomState}。
 */
@Component
public final class ExtractionPreviewImpl implements ExtractionPreview {

    private final CleaningPipeline cleaner;

    public ExtractionPreviewImpl(CleaningPipeline cleaner) {
        this.cleaner = cleaner;
    }

    @Override
    public PreviewResult preview(TaskDefinition definition, DomState domState) {
        if (definition == null || domState == null) {
            return new PreviewResult(List.of(), List.of());
        }
        List<FieldOutcome> outcomes = new ArrayList<>();
        CleaningPipeline.ResultCollector collector = new CleaningPipeline.ResultCollector();
        for (FieldDefinition field : definition.fields()) {
            String raw = readRaw(field, domState, collector);
            if (raw == null && hasAttributeMissing(collector, field)) {
                // ATTRIBUTE_MISSING 已记录；不再触发 FIELD_EMPTY 重复诊断
                outcomes.add(new FieldOutcome(field.name(), null, null, true));
                continue;
            }
            CleaningPipeline.Result r = cleaner.clean(raw, field, collector);
            outcomes.add(new FieldOutcome(field.name(), r.rawValue, r.cleanedValue, r.isEmpty));
        }
        return new PreviewResult(List.copyOf(outcomes), collector.immutable());
    }

    private String readRaw(FieldDefinition field, DomState domState,
                           CleaningPipeline.ResultCollector collector) {
        if (field.source() == FieldSource.PAGE_URL) {
            return domState.url();
        }
        List<Node> nodes;
        try {
            nodes = domState.querySelectorAll(field.selector());
        } catch (RuntimeException ex) {
            collector.add(field, DiagnosticCode.SELECTOR_SYNTAX_INVALID, "选择器语法错误: " + ex.getMessage());
            return null;
        }
        if (nodes == null || nodes.isEmpty()) {
            collector.add(field, DiagnosticCode.ZERO_MATCH, "0 匹配");
            return null;
        }
        if (nodes.size() > 1) {
            collector.add(field, DiagnosticCode.MULTIPLE_MATCH, "匹配 " + nodes.size() + " 个，取第一个");
        }
        Node node = nodes.get(0);
        return extractValue(field, node, collector);
    }

    private String extractValue(FieldDefinition field, Node node,
                                CleaningPipeline.ResultCollector collector) {
        return switch (field.source()) {
            case VISIBLE_TEXT -> node.textContent();
            case ATTRIBUTE -> attributeOrMissing(field, node, field.attributeName(), collector);
            case LINK_URL -> attributeOrMissing(field, node, "href", collector);
            case IMAGE_URL -> attributeOrMissing(field, node, "src", collector);
            case PAGE_URL -> null;
        };
    }

    private String attributeOrMissing(FieldDefinition field, Node node, String name,
                                      CleaningPipeline.ResultCollector collector) {
        if (name == null || name.isBlank()) {
            collector.add(field, DiagnosticCode.ATTRIBUTE_MISSING, "属性名未指定");
            return null;
        }
        String value = node.attributes() == null ? null : node.attributes().get(name);
        if (value == null) {
            collector.add(field, DiagnosticCode.ATTRIBUTE_MISSING, "属性缺失: " + name);
        }
        return value;
    }

    private boolean hasAttributeMissing(CleaningPipeline.ResultCollector collector, FieldDefinition field) {
        return collector.immutable().stream().anyMatch(d ->
                d.code() == DiagnosticCode.ATTRIBUTE_MISSING
                        && field.name().equals(d.fieldName()));
    }
}
