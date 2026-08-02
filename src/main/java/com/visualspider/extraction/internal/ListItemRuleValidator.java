package com.visualspider.extraction.internal;

import com.visualspider.task.domain.ListItemRule;
import com.visualspider.task.domain.SelectorType;
import java.util.regex.Pattern;
import javax.xml.xpath.XPathFactory;
import org.springframework.stereotype.Service;

/**
 * {@link ListItemRule} 校验：CSS / XPath 语法（M4 spec §T1）。
 *
 * <p>与 M2 {@code DefinitionValidator.selectType} 共用相同的语法后端；
 * 本校验器只关注 {@link ListItemRule}（container rule），不处理
 * {@link com.visualspider.task.domain.FieldDefinition}。
 */
@Service
public class ListItemRuleValidator {

    public ListItemRuleValidation validate(ListItemRule rule) {
        if (rule == null) {
            return ListItemRuleValidation.error("selector 不能为空", "listItemRule");
        }
        String sel = rule.selector();
        if (sel == null || sel.isBlank()) {
            return ListItemRuleValidation.error("listItemRule.selector 不能为空", "listItemRule.selector");
        }
        SelectorType type = rule.selectorType() == null ? SelectorType.CSS : rule.selectorType();
        if (type == SelectorType.XPATH) {
            try {
                XPathFactory.newInstance().newXPath().compile(sel);
                return ListItemRuleValidation.ok();
            } catch (RuntimeException | javax.xml.xpath.XPathException ex) {
                return ListItemRuleValidation.error("XPath 语法错误: " + ex.getMessage(),
                        "listItemRule.selector");
            }
        }
        try {
            // CSS via jsoup QueryParser（M2 已引入）
            org.jsoup.select.QueryParser.parse(sel);
            return ListItemRuleValidation.ok();
        } catch (IllegalArgumentException
                 | org.jsoup.select.Selector.SelectorParseException ex) {
            return ListItemRuleValidation.error("CSS 语法错误: " + ex.getMessage(),
                    "listItemRule.selector");
        }
    }

    public record ListItemRuleValidation(boolean valid, String message, String fieldPath) {
        public static ListItemRuleValidation ok() {
            return new ListItemRuleValidation(true, null, null);
        }
        public static ListItemRuleValidation error(String msg, String path) {
            return new ListItemRuleValidation(false, msg, path);
        }
    }

    /** XPath 语法 compile 缓存（轻量，模块冷启动期一次性建）。可被多线程并发 compile。 */
    private static final Pattern TRAILING_SPACE = Pattern.compile("\\s+$");
}
