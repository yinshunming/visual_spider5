package com.visualspider.visualbrowser;

import java.util.List;

/**
 * 选择模式下按坐标检查到的 DOM 元素摘要。boundingBox 为远程视口坐标（1280×720），
 * 前端按客户端显示尺寸换算后叠加高亮。每次 select 重新查询 DOM，不保存 ElementHandle。
 * 候选 CSS/XPath 由 {@link CandidateGenerator} 基于 tagName/id/class 生成。
 */
public record SelectionRecord(
        String tagName,
        String id,
        String className,
        String text,
        double x,
        double y,
        double width,
        double height,
        List<String> cssCandidates,
        List<String> xpathCandidates
) {}
