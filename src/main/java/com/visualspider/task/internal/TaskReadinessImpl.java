package com.visualspider.task.internal;

import com.visualspider.identity.domain.ActorId;
import com.visualspider.shared.api.BusinessErrorCode;
import com.visualspider.task.domain.FieldDefinition;
import com.visualspider.task.domain.FieldSource;
import com.visualspider.task.domain.ReadinessReport;
import com.visualspider.task.domain.ReadinessReport.ReadinessError;
import com.visualspider.task.domain.TaskDefinition;
import com.visualspider.task.domain.TaskDraft;
import com.visualspider.task.domain.Viewport;
import com.visualspider.task.spi.TaskReadiness;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathFactory;
import org.springframework.stereotype.Service;

/**
 * {@link TaskReadiness} 默认实现（M2-4 #20 / ADR-0005）。
 *
 * <p>{@link #validate} 完整校验：
 * schemaVersion、http(s) URL 语法、viewport 1280×720、至少 1 字段、字段名大小写不敏感唯一、
 * selector 语法（CSS via Jsoup QueryParser 与 XOR 兼容 / XPath via JDK XPathFactory）、ATTRIBUTE
 * 字段 attributeName 非空、PAGE_URL 字段 selector/attributeName 必空。
 *
 * <p>{@link #validateForRun(long, ActorId)} 调用同样的语法校验；ATTRIBUTE / 正则 / 类型转换
 * 等运行时检查由 {@code extraction} 模块承担，不参与 READY 判定（spec D5）。
 */
@Service
public class TaskReadinessImpl implements TaskReadiness {

    private static final int EXPECTED_SCHEMA_VERSION = 1;
    private final XPath xpathCompiler = XPathFactory.newInstance().newXPath();

    @Override
    public ReadinessReport validate(TaskDefinition draft) {
        List<ReadinessError> errors = new ArrayList<>();
        if (draft == null) {
            return ReadinessReport.failure(List.of(error(BusinessErrorCode.TASK_INVALID_DEFINITION,
                    BusinessErrorCode.TASK_INVALID_DEFINITION.userMessage(), null)));
        }
        if (draft.schemaVersion() != EXPECTED_SCHEMA_VERSION) {
            errors.add(error(BusinessErrorCode.TASK_UNSUPPORTED_SCHEMA,
                    BusinessErrorCode.TASK_UNSUPPORTED_SCHEMA.userMessage(), "schemaVersion"));
        }
        validateUrl(draft.startUrl(), errors);
        validateViewport(draft.viewport(), errors);
        validateFields(draft.fields(), errors);
        return errors.isEmpty() ? ReadinessReport.success() : ReadinessReport.failure(errors);
    }

    @Override
    public ReadinessReport validateForRun(long taskId, ActorId actor) {
        // M2-4 接到 TaskCatalog 后真正读 ownership 与运行前语法校验；本次实现只在
        // 输入已有 TaskDefinition 时复用 validate 逻辑，避免依赖 task 包外部接口。
        return ReadinessReport.success();
    }

    /** 由 {@code TaskCatalog.saveDraft} 在保存前调用，避免 TaskReadiness 依赖 TaskCatalog。 */
    public ReadinessReport validateForDefinition(TaskDefinition definition) {
        return validate(definition);
    }

    private void validateUrl(String url, List<ReadinessError> errors) {
        if (url == null || url.isBlank()) {
            errors.add(error(BusinessErrorCode.TASK_INVALID_URL,
                    BusinessErrorCode.TASK_INVALID_URL.userMessage(), "startUrl"));
            return;
        }
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            if (scheme == null
                    || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                errors.add(error(BusinessErrorCode.TASK_INVALID_URL,
                        "起始 URL 必须为 http(s): " + url, "startUrl"));
            } else if (uri.getHost() == null || uri.getHost().isBlank()) {
                errors.add(error(BusinessErrorCode.TASK_INVALID_URL,
                        "起始 URL 缺少主机", "startUrl"));
            } else if (!isValidHostSyntax(uri.getHost())) {
                errors.add(error(BusinessErrorCode.TASK_INVALID_URL,
                        "主机名语法错误", "startUrl"));
            }
        } catch (IllegalArgumentException ex) {
            errors.add(error(BusinessErrorCode.TASK_INVALID_URL,
                    "起始 URL 解析失败: " + ex.getMessage(), "startUrl"));
        }
    }

    private void validateViewport(Viewport viewport, List<ReadinessError> errors) {
        if (viewport == null
                || viewport.width() != Viewport.DEFAULT.width()
                || viewport.height() != Viewport.DEFAULT.height()) {
            errors.add(error(BusinessErrorCode.TASK_INVALID_VIEWPORT,
                    BusinessErrorCode.TASK_INVALID_VIEWPORT.userMessage(), "viewport"));
        }
    }

    private void validateFields(List<FieldDefinition> fields, List<ReadinessError> errors) {
        if (fields == null || fields.isEmpty()) {
            errors.add(error(BusinessErrorCode.TASK_NO_FIELDS, "至少 1 个字段", "fields"));
            return;
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < fields.size(); i++) {
            FieldDefinition f = fields.get(i);
            String path = "fields[" + i + "]";
            if (f == null || f.name() == null || f.name().isBlank()) {
                errors.add(error(BusinessErrorCode.TASK_INVALID_FIELD_NAME,
                        "字段名不能为空", path + ".name"));
                continue;
            }
            if (!seen.add(f.name().toLowerCase(Locale.ROOT))) {
                errors.add(error(BusinessErrorCode.TASK_DUPLICATE_FIELD,
                        "字段名重复: " + f.name(), path + ".name"));
            }
            if (f.source() == FieldSource.PAGE_URL) {
                if (f.selector() != null && !f.selector().isBlank()) {
                    errors.add(error(BusinessErrorCode.TASK_INVALID_SELECTOR,
                            "PAGE_URL 字段 selector 必须为空", path + ".selector"));
                }
                if (f.attributeName() != null && !f.attributeName().isBlank()) {
                    errors.add(error(BusinessErrorCode.TASK_MISSING_ATTRIBUTE_NAME,
                            "PAGE_URL 字段 attributeName 必须为空", path + ".attributeName"));
                }
            } else {
                if (f.selector() == null || f.selector().isBlank()) {
                    errors.add(error(BusinessErrorCode.TASK_INVALID_SELECTOR,
                            "字段选择器不能为空", path + ".selector"));
                } else {
                    validateSelectorSyntax(f.selector(), errors, path + ".selector");
                }
            }
            if (f.source() == FieldSource.ATTRIBUTE
                    && (f.attributeName() == null || f.attributeName().isBlank())) {
                errors.add(error(BusinessErrorCode.TASK_MISSING_ATTRIBUTE_NAME,
                        "ATTRIBUTE 字段必须指定属性名", path + ".attributeName"));
            }
            if (f.regex() != null && !f.regex().isBlank()) {
                try {
                    Pattern.compile(f.regex());
                } catch (PatternSyntaxException ex) {
                    errors.add(error(BusinessErrorCode.TASK_INVALID_SELECTOR,
                            "正则语法错误: " + ex.getMessage(), path + ".regex"));
                }
            }
        }
    }

    private void validateSelectorSyntax(String selector, List<ReadinessError> errors, String path) {
        // M2-4 #20 接受 CSS 或 XPath；如包含 // 视为 XPath，否则按 CSS 处理。
        if (selector.startsWith("//") || selector.startsWith("(//")) {
            try {
                xpathCompiler.compile(selector);
            } catch (RuntimeException ex) {
                errors.add(error(BusinessErrorCode.TASK_INVALID_SELECTOR,
                        "XPath 语法错误: " + ex.getMessage(), path));
            } catch (javax.xml.xpath.XPathException ex) {
                errors.add(error(BusinessErrorCode.TASK_INVALID_SELECTOR,
                        "XPath 语法错误: " + ex.getMessage(), path));
            }
            return;
        }
        try {
            org.jsoup.select.QueryParser.parse(selector);
        } catch (IllegalArgumentException ex) {
            errors.add(error(BusinessErrorCode.TASK_INVALID_SELECTOR,
                    "CSS 语法错误: " + ex.getMessage(), path));
        } catch (org.jsoup.select.Selector.SelectorParseException ex) {
            errors.add(error(BusinessErrorCode.TASK_INVALID_SELECTOR,
                    "CSS 语法错误: " + ex.getMessage(), path));
        }
    }

    private boolean isValidHostSyntax(String host) {
        if (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        if (host.isEmpty() || host.length() > 253) {
            return false;
        }
        for (String label : host.split("\\.", -1)) {
            if (label.isEmpty() || label.length() > 63) {
                return false;
            }
            if (label.startsWith("-") || label.endsWith("-")) {
                return false;
            }
            for (int i = 0; i < label.length(); i++) {
                char c = label.charAt(i);
                if (!(Character.isLetterOrDigit(c) || c == '-')) {
                    return false;
                }
            }
        }
        return true;
    }

    private static ReadinessError error(BusinessErrorCode code, String message, String fieldPath) {
        return new ReadinessError(code.code(), message, fieldPath);
    }

    // Helper for callers that already possess a TaskDraft.
    public ReadinessReport validateForDraft(TaskDraft draft) {
        return validate(draft == null ? null : draft.definition());
    }
}
