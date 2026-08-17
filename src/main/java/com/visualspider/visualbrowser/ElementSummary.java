package com.visualspider.visualbrowser;

/**
 * 校验匹配元素摘要：tagName/id/className/text + 远程视口 boundingBox。
 * 每次校验重新查询 DOM，不保存 ElementHandle。
 */
public record ElementSummary(
        String tagName,
        String id,
        String className,
        String text,
        double x,
        double y,
        double width,
        double height
) {}
