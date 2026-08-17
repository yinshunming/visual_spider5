package com.visualspider.visualbrowser;

import java.util.List;

/**
 * 手写选择器校验结果：valid (语法与执行是否成功)、count (匹配数)、error (非法语法时填消息)、
 * elements (匹配元素摘要列表，用于高亮全部匹配)。
 */
public record ValidationResult(
        boolean valid,
        int count,
        String error,
        List<ElementSummary> elements
) {}
