package com.visualspider.shared.api;

/**
 * 业务错误码常量（spec §D7）。
 *
 * <p>统一在 {@code shared} 模块定义；其它模块引用。
 * 中文用户消息固定；i18n 延后 M7。
 *
 * <p>每个错误码含：
 * <ul>
 *   <li>{@link #code()} 字符串（API JSON 中稳定）</li>
 *   <li>{@link #userMessage()} 中文用户消息</li>
 *   <li>{@link #httpStatus()} 建议 HTTP 状态码</li>
 * </ul>
 */
public enum BusinessErrorCode {
    // 401 - 认证
    AUTH_INVALID_CREDENTIALS(401, "AUTH_INVALID_CREDENTIALS", "用户名或密码错误"),
    AUTH_REQUIRED(401, "AUTH_REQUIRED", "请先登录"),
    // 403 - 授权
    ACCESS_DENIED(403, "ACCESS_DENIED", "无权访问该资源"),
    // 404 - 资源
    RESOURCE_NOT_FOUND(404, "RESOURCE_NOT_FOUND", "资源不存在"),
    // 409 - 冲突
    TASK_STALE_VERSION(409, "TASK_STALE_VERSION", "任务已被其他人更新，请刷新后重试"),
    DUPLICATE_USERNAME(409, "DUPLICATE_USERNAME", "用户名已存在"),
    // 400 - 请求格式
    VALIDATION_FAILED(400, "VALIDATION_FAILED", "请求参数校验失败"),
    WEAK_PASSWORD(400, "WEAK_PASSWORD", "密码强度不足：trim 后长度必须 ≥ 12 字符"),
    TASK_INVALID_DEFINITION(400, "TASK_INVALID_DEFINITION", "任务定义不能为空"),
    TASK_UNSUPPORTED_SCHEMA(400, "TASK_UNSUPPORTED_SCHEMA", "暂不支持的 schema 版本（M1 固定为 1）"),
    TASK_INVALID_MODE(400, "TASK_INVALID_MODE", "采集模式不能为空"),
    TASK_INVALID_URL(400, "TASK_INVALID_URL", "起始 URL 必须为 http(s) 且 host 非空"),
    TASK_INVALID_VIEWPORT(400, "TASK_INVALID_VIEWPORT", "视口必须为 1280×720（M1 固定）"),
    TASK_DUPLICATE_FIELD(400, "TASK_DUPLICATE_FIELD", "字段名重复"),
    TASK_INVALID_FIELD_NAME(400, "TASK_INVALID_FIELD_NAME", "字段名不能为空"),
    TASK_NO_FIELDS(400, "TASK_NO_FIELDS", "至少 1 个字段"),
    TASK_MISSING_ATTRIBUTE_NAME(400, "TASK_MISSING_ATTRIBUTE_NAME", "ATTRIBUTE 字段必须指定属性名"),
    TASK_INVALID_SELECTOR(400, "TASK_INVALID_SELECTOR", "选择器语法不合法"),
    TASK_INVALID_WAIT_POLICY(400, "TASK_INVALID_WAIT_POLICY", "额外等待时间必须 0-5 秒"),
    // M4 任务定义（spec §D13）
    TASK_SCHEMA_OUTDATED(409, "TASK_SCHEMA_OUTDATED", "schemaVersion 过时，请重新保存任务"),
    LIST_ITEM_RULE_MISSING(400, "LIST_ITEM_RULE_MISSING", "列表模式任务必须定义列表项规则"),
    LIST_ITEM_RULE_NO_MATCH(400, "LIST_ITEM_RULE_NO_MATCH", "列表项规则匹配数少于 2"),
    MULTIPLE_MATCH(400, "MULTIPLE_MATCH", "字段选择器匹配多个元素"),
    UNIQUE_KEY_UNKNOWN_FIELD(400, "UNIQUE_KEY_UNKNOWN_FIELD", "唯一键字段名未在字段列表中"),
    LIMITS_OUT_OF_RANGE(400, "LIMITS_OUT_OF_RANGE", "limits 必须在 1..硬上限 之间"),
    EDIT_BUFFER_CONFLICT(409, "EDIT_BUFFER_CONFLICT", "本地编辑与最新版本冲突，请刷新或覆盖"),
    SESSION_NOT_FOUND(404, "SESSION_NOT_FOUND", "配置会话不存在"),
    SESSION_NOT_OWNER(403, "SESSION_NOT_OWNER", "无权访问该配置会话"),
    CONFIG_LANE_FULL(409, "CONFIG_LANE_FULL", "配置会话 lane 已占满，请稍候重试"),
    TASK_NOT_DRAFT(409, "TASK_NOT_DRAFT", "仅草稿或可运行状态的任务可开启配置会话"),
    // M3 运行（spec §D19）
    RUN_NOT_FOUND(404, "RUN_NOT_FOUND", "运行不存在"),
    RUN_NOT_OWNER(403, "RUN_NOT_OWNER", "无权访问该运行"),
    USER_RUN_LIMIT(409, "USER_RUN_LIMIT", "每用户最多 1 个进行中的运行"),
    TASK_NOT_READY(409, "TASK_NOT_READY", "任务未通过校验，不能启动运行"),
    RUN_NOT_CANCELLABLE(409, "RUN_NOT_CANCELLABLE", "运行已结束，不能取消"),
    // 500 - 内部
    INTERNAL_ERROR(500, "INTERNAL_ERROR", "服务器内部错误");

    private final int httpStatus;
    private final String code;
    private final String userMessage;

    BusinessErrorCode(int httpStatus, String code, String userMessage) {
        this.httpStatus = httpStatus;
        this.code = code;
        this.userMessage = userMessage;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String code() {
        return code;
    }

    public String userMessage() {
        return userMessage;
    }
}
