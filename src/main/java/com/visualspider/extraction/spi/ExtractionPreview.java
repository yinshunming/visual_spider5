package com.visualspider.extraction.spi;

import com.visualspider.task.domain.SelectorType;
import com.visualspider.task.domain.TaskDefinition;
import java.util.List;

/**
 * 配置预览通道 SPI（M2-3 #19 / M3 公用契约）。
 *
 * <p>在当前 lane/Page 上对 task definition 的字段做读取与清洗，返回原始值/最终值/诊断；
 * 不修改任何持久化数据。M3 {@code run} 模块也将基于同一接口实现运行与预览共用。
 *
 * <p>实现位于 {@code extraction.internal.ExtractionPreviewImpl}；模块外不应感知 Playwright。
 */
public interface ExtractionPreview {

    PreviewResult preview(TaskDefinition definition, DomState domState);

    /**
     * List 模式受限预览（M4 spec §D9）。
     *
     * <p>命中 {@code definition.listItemRule().selector()} 的前 {@code maxItems} 项
     * （M4 上限 20），逐项调用 {@link #preview(TaskDefinition, DomState)}，
     * 聚合 {@code ListPreviewResult}。
     *
     * <p>{@code maxItems} 由调用方传入；Impl 内部再次 cap 到 20（spec §T3）。
     *
     * <p>实匹配校验的 {@code LIST_ITEM_RULE_NO_MATCH}：方法返回空 list 时由调用方
     * （{@code TaskReadiness.validateForRun}）检测并报对应业务错误码。
     */
    ListPreviewResult previewList(TaskDefinition definition, DomState domState, int maxItems);

    /**
     * 字段读取所需的最小 DOM 信息。
     *
     * <p>M2 仅 {@link #url()} / {@link #querySelectorAll(String)}；
     * M3 扩展 {@link #query(String, SelectorType)} 按选择器类型分发
     * （CSS 走 {@code document.querySelectorAll}，XPath 走 {@code document.evaluate}），
     * 修掉 M2 preview 对 XPath 字段误报 {@code SELECTOR_SYNTAX_INVALID} 的隐患。
     *
     * <p>M4 增加 {@link #scopeToNode(Node)} 提供 item 子树作用域查询（list 模式）。
     * 默认 throw，Playwright lane 上实现为 {@code ElementHandle.querySelector(...)} 形式。
     */
    interface DomState {
        String url();

        /** M3 新增：按类型分发查询；M3 调用方应使用本方法以正确处理 XPath 字段。 */
        List<Node> query(String selector, SelectorType type);

        /** 旧 API（M2 兼容）；默认委托 {@link #query(String, SelectorType)} 走 CSS。 */
        default List<Node> querySelectorAll(String selector) {
            return query(selector, SelectorType.CSS);
        }

        /**
         * M4 新增：把查询域缩到 {@code item} 的子树下。生产 Playwright 实现用
         * {@code ElementHandle} 子查询；纯 Java 测试 stub 可返回预定义节点。
         */
        default DomState scopeToNode(Node item) {
            throw new UnsupportedOperationException(
                    "DomState.scopeToNode 未实现（M4 list-item scoping）");
        }
    }

    /** 节点摘要：仅含字段提取必需的静态字段，不含 ElementHandle。 */
    record Node(String tagName, String id, String className, String textContent,
                java.util.Map<String, String> attributes) {
    }

    /**
     * List 模式预览聚合（M4 spec §D9）。
     *
     * <p>{@code totalMatchCount} = {@code listItemRule} 命中总数；
     * {@code previews.size()} = 实际预览条数（≤ maxItems 且 ≤ totalMatchCount）。
     */
    record ListPreviewResult(List<PreviewResult> previews,
                             int totalMatchCount,
                             List<ExtractionDiagnostic> diagnostics) {
        public ListPreviewResult {
            if (previews == null) previews = List.of();
            if (diagnostics == null) diagnostics = List.of();
        }
    }
}