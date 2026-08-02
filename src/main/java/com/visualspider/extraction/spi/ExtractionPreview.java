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
     * 字段读取所需的最小 DOM 信息。
     *
     * <p>M2 仅 {@link #url()} / {@link #querySelectorAll(String)}；
     * M3 扩展 {@link #query(String, SelectorType)} 按选择器类型分发
     * （CSS 走 {@code document.querySelectorAll}，XPath 走 {@code document.evaluate}），
     * 修掉 M2 preview 对 XPath 字段误报 {@code SELECTOR_SYNTAX_INVALID} 的隐患。
     *
     * <p>{@link #querySelectorAll(String)} 保留作为默认实现，委托给
     * {@code query(selector, SelectorType.CSS)}，确保旧 DomState 实现兼容。
     */
    interface DomState {
        String url();

        /** M3 新增：按类型分发查询；M3 调用方应使用本方法以正确处理 XPath 字段。 */
        List<Node> query(String selector, SelectorType type);

        /** 旧 API（M2 兼容）；默认委托 {@link #query(String, SelectorType)} 走 CSS。 */
        default List<Node> querySelectorAll(String selector) {
            return query(selector, SelectorType.CSS);
        }
    }

    /** 节点摘要：仅含字段提取必需的静态字段，不含 ElementHandle。 */
    record Node(String tagName, String id, String className, String textContent,
                java.util.Map<String, String> attributes) {
    }
}