package com.visualspider.task.internal;

import com.visualspider.identity.domain.ActorId;
import com.visualspider.shared.api.BusinessErrorCode;
import com.visualspider.task.domain.FieldDefinition;
import com.visualspider.task.domain.FieldSource;
import com.visualspider.task.domain.Limits;
import com.visualspider.task.domain.ReadinessReport;
import com.visualspider.task.domain.ReadinessReport.ReadinessError;
import com.visualspider.task.domain.SelectorType;
import com.visualspider.task.domain.TaskDefinition;
import com.visualspider.task.domain.TaskDraft;
import com.visualspider.task.domain.TaskMode;
import com.visualspider.task.domain.UniqueKeyField;
import com.visualspider.task.domain.Viewport;
import com.visualspider.task.domain.WaitPolicy;
import com.visualspider.task.spi.TaskCatalog;
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
 * {@link TaskReadiness} 默认实现（M2-4 #20 / M3-1 #23 / M4-1 #31 / ADR-0005）。
 *
 * <p>{@link #validate} 完整校验：
 * schemaVersion（= 3；M4 §D10 / M5 §D11）、http(s) URL 语法、viewport 1280×720、
 * 至少 1 字段、字段名大小写不敏感唯一、selector 语法（CSS via Jsoup QueryParser /
 * XPath via JDK XPathFactory）、ATTRIBUTE 字段 attributeName 非空、PAGE_URL 字段
 * selector/attributeName 必空、{@code mode=LIST} 时 {@code listItemRule} 必填、
 * {@code uniqueKey[i].fieldName} 必须在 fields[].name 中。
 *
 * <p>{@link #validateForRun(long, ActorId)} 调用同样的语法校验；ATTRIBUTE / 正则 / 类型转换
 * 等运行时检查由 {@code extraction} 模块承担，不参与 READY 判定（spec D5）。
 *
 * <p>M4 list-item 实匹配校验（{@code LIST_ITEM_RULE_NO_MATCH} / {@code MULTIPLE_MATCH}
 * 阻止就绪）由 M4-3 (#33) 在 {@code extraction.previewList} 路径里挂入，本类不实现。
 */
@Service
public class TaskReadinessImpl implements TaskReadiness {

    private static final int EXPECTED_SCHEMA_VERSION = 3;
    private final XPath xpathCompiler = XPathFactory.newInstance().newXPath();
    /** @Lazy 打破 TaskCatalog -> TaskReadiness -> TaskCatalog 构造期循环（ADR-0005）。 */
    private final TaskCatalog taskCatalog;
    /** M4 spec §D10 实匹配校验 hook（生产 Playwright lane 装配）。 */
    private final com.visualspider.task.spi.LiveReadinessHook liveHook;

    /** 测试构造：不接入 TaskCatalog / live hook，{@link #validateForRun} 退化为 stub。 */
    public TaskReadinessImpl() {
        this(null, new com.visualspider.task.internal.AlwaysPassLiveReadinessHook());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public TaskReadinessImpl(@org.springframework.context.annotation.Lazy TaskCatalog taskCatalog,
                            com.visualspider.task.spi.LiveReadinessHook liveHook) {
        this.taskCatalog = taskCatalog;
        this.liveHook = liveHook == null
                ? new com.visualspider.task.internal.AlwaysPassLiveReadinessHook()
                : liveHook;
    }

    /** M3 二元构造保留（仅 catalog），live hook 走默认 no-op。 */
    public TaskReadinessImpl(TaskCatalog taskCatalog) {
        this(taskCatalog, new com.visualspider.task.internal.AlwaysPassLiveReadinessHook());
    }

    @Override
    public ReadinessReport validate(TaskDefinition draft) {
        List<ReadinessError> errors = new ArrayList<>();
        if (draft == null) {
            return ReadinessReport.failure(List.of(error(BusinessErrorCode.TASK_INVALID_DEFINITION,
                    BusinessErrorCode.TASK_INVALID_DEFINITION.userMessage(), null)));
        }
        validateSchemaVersion(draft, errors);
        validateUrl(draft.startUrl(), errors);
        validateViewport(draft.viewport(), errors);
        validateWaitPolicy(draft.waitPolicy(), errors);
        validateLimits(draft.limits(), errors);
        validateListItemRule(draft, errors);
        validateUniqueKey(draft, errors);
        validateFields(draft.fields(), errors);
        // Live 实匹配校验（spec §D10）：listItemRule 命中数 + 字段多匹配
        if (draft.mode() instanceof com.visualspider.task.domain.TaskMode.List
                && draft.listItemRule() != null) {
            com.visualspider.task.spi.LiveReadinessHook.LiveReadinessOutcome live =
                    liveHook.check(draft, -1L);
            if (!live.passed()) {
                for (int i = 0; i < live.blockingCodes().size(); i++) {
                    String code = live.blockingCodes().get(i);
                    String msg = i < live.messages().size() ? live.messages().get(i) : code;
                    BusinessErrorCode mapped = switch (code) {
                        case "LIST_ITEM_RULE_NO_MATCH" -> BusinessErrorCode.LIST_ITEM_RULE_NO_MATCH;
                        case "MULTIPLE_MATCH" -> BusinessErrorCode.MULTIPLE_MATCH;
                        default -> BusinessErrorCode.TASK_INVALID_DEFINITION;
                    };
                    errors.add(error(mapped, msg, "live"));
                }
            }
        }
        return errors.isEmpty() ? ReadinessReport.success() : ReadinessReport.failure(errors);
    }

    /**
     * schemaVersion 校验（M4 §D10 / M5 §D11）：
     * <ul>
     *   <li>{@code schemaVersion == 3} → OK</li>
     *   <li>{@code schemaVersion ∈ {1, 2}} → {@code TASK_SCHEMA_OUTDATED}（启动 hook / catalog 兜底应已升 V3；未经升级路径直接校验时拒绝）</li>
     *   <li>其它 → {@code TASK_UNSUPPORTED_SCHEMA}</li>
     * </ul>
     */
    private void validateSchemaVersion(TaskDefinition draft, List<ReadinessError> errors) {
        int v = draft.schemaVersion();
        if (v == EXPECTED_SCHEMA_VERSION) {
            return;
        }
        if (v == 1 || v == 2) {
            errors.add(error(BusinessErrorCode.TASK_SCHEMA_OUTDATED,
                    "schemaVersion=" + v + " 已过时，请重新保存任务以升级到 V3", "schemaVersion"));
            return;
        }
        errors.add(error(BusinessErrorCode.TASK_UNSUPPORTED_SCHEMA,
                "不支持的 schemaVersion=" + v + "（期望 " + EXPECTED_SCHEMA_VERSION + "）",
                "schemaVersion"));
    }

    /**
     * Limits 校验（spec §D1）：{@link Limits} 紧凑构造器已硬上限拒绝越界，
     * 此处防御性兜底，覆盖反序列化路径绕过构造器的极端场景。
     */
    private void validateLimits(Limits limits, List<ReadinessError> errors) {
        if (limits == null) {
            errors.add(error(BusinessErrorCode.LIMITS_OUT_OF_RANGE,
                    "limits 不能为空", "limits"));
            return;
        }
        try {
            // 重新走构造器；越界抛 IllegalArgumentException
            new Limits(limits.pageLimit(), limits.recordLimit(), limits.durationLimit());
        } catch (IllegalArgumentException ex) {
            errors.add(error(BusinessErrorCode.LIMITS_OUT_OF_RANGE,
                    ex.getMessage(), "limits"));
        }
    }

    /**
     * list 模式必填 listItemRule；其它模式可不填（spec §D10）。
     */
    private void validateListItemRule(TaskDefinition draft, List<ReadinessError> errors) {
        if (!(draft.mode() instanceof TaskMode.List)) {
            return;
        }
        if (draft.listItemRule() == null || draft.listItemRule().selector() == null
                || draft.listItemRule().selector().isBlank()) {
            errors.add(error(BusinessErrorCode.LIST_ITEM_RULE_MISSING,
                    "列表模式任务必须定义列表项规则", "listItemRule"));
        }
    }

    /**
     * uniqueKey[i].fieldName 必须在 fields[].name 中（spec §D10）。
     */
    private void validateUniqueKey(TaskDefinition draft, List<ReadinessError> errors) {
        List<UniqueKeyField> keys = draft.uniqueKey();
        if (keys == null || keys.isEmpty()) {
            return;
        }
        Set<String> fieldNames = new HashSet<>();
        for (FieldDefinition f : draft.fields()) {
            if (f != null && f.name() != null) {
                fieldNames.add(f.name());
            }
        }
        for (int i = 0; i < keys.size(); i++) {
            UniqueKeyField k = keys.get(i);
            String path = "uniqueKey[" + i + "].fieldName";
            if (k == null || k.fieldName() == null || k.fieldName().isBlank()) {
                errors.add(error(BusinessErrorCode.UNIQUE_KEY_UNKNOWN_FIELD,
                        "唯一键字段名不能为空", path));
                continue;
            }
            if (!fieldNames.contains(k.fieldName())) {
                errors.add(error(BusinessErrorCode.UNIQUE_KEY_UNKNOWN_FIELD,
                        "唯一键字段名未在字段列表中: " + k.fieldName(), path));
            }
        }
    }

    @Override
    public ReadinessReport validateForRun(long taskId, ActorId actor) {
        // ADR-0005：读 draft 后复用语法校验；运行时检查由 extraction 承担，不参与 READY。
        if (taskCatalog == null) {
            // 测试 stub 路径：无 catalog 可读，直接返回 success。
            return ReadinessReport.success();
        }
        TaskDraft draft = taskCatalog.read(taskId, actor);
        return validate(draft.definition());
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

    /**
     * 校验 {@link WaitPolicy}：{@code extraWaitSeconds} 必须 0-5（M3 spec §D6）。
     *
     * <p>WaitPolicy 构造器已强制 0-5；此处作为防御性兜底，覆盖反序列化路径绕过构造器的极端场景
     * （例如反射或未来 JSON 自定义反序列化）。{@code null} 由 {@link TaskDefinition} 紧凑构造器
     * 默认填充 {@code WaitPolicy(0)}，故此处非空。
     */
    private void validateWaitPolicy(WaitPolicy waitPolicy, List<ReadinessError> errors) {
        if (waitPolicy == null) {
            errors.add(error(BusinessErrorCode.TASK_INVALID_WAIT_POLICY,
                    BusinessErrorCode.TASK_INVALID_WAIT_POLICY.userMessage(),
                    "waitPolicy.extraWaitSeconds"));
            return;
        }
        int s = waitPolicy.extraWaitSeconds();
        if (s < 0 || s > 5) {
            errors.add(error(BusinessErrorCode.TASK_INVALID_WAIT_POLICY,
                    "额外等待时间必须 0-5 秒；got " + s, "waitPolicy.extraWaitSeconds"));
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
