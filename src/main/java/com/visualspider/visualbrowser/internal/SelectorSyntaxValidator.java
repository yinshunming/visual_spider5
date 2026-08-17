package com.visualspider.visualbrowser.internal;

import javax.xml.xpath.XPathFactory;
import org.jsoup.select.QueryParser;
import org.springframework.stereotype.Component;

/**
 * 选择器语法校验（M2-2 #18）。
 *
 * <p>CSS：通过 Jsoup {@code QueryParser}（仅语法解析，不接触 DOM）。
 * XPath：通过 JDK {@link XPathFactory#newInstance()} 编译（不执行）。
 * 仅做语法检查；运行时匹配数与高亮由 {@code PlaywrightControl.validateSelector} 提供。
 */
@Component
public class SelectorSyntaxValidator {

    public void validateCss(String selector) {
        if (selector == null || selector.isBlank()) {
            throw new InvalidSelectorException("CSS 选择器不能为空", "css");
        }
        try {
            QueryParser.parse(selector);
        } catch (IllegalArgumentException ex) {
            throw new InvalidSelectorException("CSS 语法错误: " + ex.getMessage(), "css");
        } catch (org.jsoup.select.Selector.SelectorParseException ex) {
            throw new InvalidSelectorException("CSS 语法错误: " + ex.getMessage(), "css");
        }
    }

    public void validateXPath(String selector) {
        if (selector == null || selector.isBlank()) {
            throw new InvalidSelectorException("XPath 不能为空", "xpath");
        }
        try {
            XPathFactory.newInstance().newXPath().compile(selector);
        } catch (RuntimeException ex) {
            throw new InvalidSelectorException("XPath 语法错误: " + ex.getMessage(), "xpath");
        } catch (javax.xml.xpath.XPathException ex) {
            throw new InvalidSelectorException("XPath 语法错误: " + ex.getMessage(), "xpath");
        }
    }
}
