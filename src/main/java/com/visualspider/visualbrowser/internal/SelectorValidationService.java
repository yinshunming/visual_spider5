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
 * <p>先做语法检查（CSS via Jsoup、XPath via JDK {@code XPathFactory}），然后通过
 * {@code PlaywrightControl.validateSelector} 实际查询当前页：
 * <ul>
 *   <li>语法失败 -> {@link InvalidSelectorException}</li>
 *   <li>runtime OK -> matchCount + matchedElements（不存 ElementHandle）</li>
 *   <li>0 匹配 -> matchCount=0，不抛错</li>
 *   <li>多匹配 -> matchCount>=1，返回 matchedElements（前端用于高亮）</li>
 * </ul>
 *
 * <p>{@link PlaywrightControl} 由调用方按 session 传入（per-session，绑定当前 lane/Page），
 * 与 preview 同路径；本服务无状态，可作为单例。
 */
@Service
public class SelectorValidationService {

    private static final Logger LOG = LoggerFactory.getLogger(SelectorValidationService.class);

    private final SelectorSyntaxValidator syntaxValidator;

    public SelectorValidationService(SelectorSyntaxValidator syntaxValidator) {
        this.syntaxValidator = syntaxValidator;
    }

    public ValidationResult validateOne(String selector, String type, PlaywrightControl control) {
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
