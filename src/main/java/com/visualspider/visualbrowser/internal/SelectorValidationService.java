package com.visualspider.visualbrowser.internal;

import com.visualspider.visualbrowser.PlaywrightControl;
import com.visualspider.visualbrowser.ValidationResult;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 选择器校验服务（M2-2 #18）。
 *
 * <p>先在 lane 内做语法检查（CSS via Jsoup、XPath via JDK {@code XPathFactory}），
 * 然后通过 {@code PlaywrightControl.validateSelector} 实际查询当前页：
 * <ul>
 *   <li>语法失败 → {@link InvalidSelectorException}</li>
 *   <li>runtime OK → matchCount + matchedElements（不存 ElementHandle）</li>
 *   <li>0 匹配 → matchCount=0，不抛错</li>
 *   <li>多匹配 → matchCount>=1，返回 matchedElements（前端用于高亮）</li>
 * </ul>
 */
@Service
public class SelectorValidationService {

    private static final Logger LOG = LoggerFactory.getLogger(SelectorValidationService.class);

    private final SelectorSyntaxValidator syntaxValidator;
    private final PlaywrightControl control;

    public SelectorValidationService(SelectorSyntaxValidator syntaxValidator, PlaywrightControl control) {
        this.syntaxValidator = syntaxValidator;
        this.control = control;
    }

    public ValidationResult validateOne(String selector, String type) {
        if ("css".equalsIgnoreCase(type)) {
            syntaxValidator.validateCss(selector);
        } else {
            syntaxValidator.validateXPath(selector);
        }
        try {
            return control.validateSelector(selector, type.toLowerCase()).join();
        } catch (RuntimeException ex) {
            LOG.warn("selector validate runtime failed: selector={} type={}", selector, type, ex);
            return new ValidationResult(false, 0, "runtime: " + ex.getMessage(), List.of());
        }
    }
}
