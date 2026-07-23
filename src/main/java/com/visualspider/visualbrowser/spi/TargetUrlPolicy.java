package com.visualspider.visualbrowser.spi;

/** 校验可视浏览器允许访问的目标 URL。 */
public interface TargetUrlPolicy {
    void validate(String url);
}
