package com.visualspider.spike.m0;

/**
 * 选择模式下按坐标检查到的 DOM 元素摘要。boundingBox 为远程视口坐标（1280×720），
 * 前端按客户端显示尺寸换算后叠加高亮。每次 select 重新查询 DOM，不保存 ElementHandle。
 */
public record SelectionRecord(
        String tagName,
        String id,
        String className,
        String text,
        double x,
        double y,
        double width,
        double height
) {}
