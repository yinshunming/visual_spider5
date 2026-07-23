package com.visualspider.extraction.spi;

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

    /** 字段读取所需的最小 DOM 信息；M2 仅 page.url() / page.content()，M3 扩展。 */
    interface DomState {
        String url();

        /** 给定选择器（CSS 限定）查询所有节点摘要；M2 实现的 IPC 通过 lane Page 抓取。 */
        List<Node> querySelectorAll(String selector);
    }

    /** 节点摘要：仅含字段提取必需的静态字段，不含 ElementHandle。 */
    record Node(String tagName, String id, String className, String textContent,
                java.util.Map<String, String> attributes) {
    }
}
