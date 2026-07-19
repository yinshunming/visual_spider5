package com.visualspider.task.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 任务采集模式。
 *
 * <p>M1 仅 {@link SinglePage} 落地；{@link List} 占位可创建但 {@code validateForRun} 返回"M4 启用"。
 *
 * <p>JSON 序列化用字符串 {@code "SINGLE_PAGE"} / {@code "LIST"}（与 DB 一致）；
 * 反序列化时通过 {@link JsonCreator} 还原为 sealed record 实例。
 *
 * <p>为何不用 sealed interface + @JsonTypeInfo：注解里直接引用同文件后续声明的
 * 嵌套 record 会触发 Java 编译器的前向引用限制；改用字符串 + 工厂方法更稳定。
 */
public sealed interface TaskMode permits TaskMode.SinglePage, TaskMode.List {

    String code();

    record SinglePage() implements TaskMode {
        @Override
        public String code() {
            return "SINGLE_PAGE";
        }
    }

    record List() implements TaskMode {
        @Override
        public String code() {
            return "LIST";
        }
    }

    @JsonCreator
    static TaskMode fromCode(String code) {
        if (code == null) {
            return null;
        }
        return switch (code) {
            case "SINGLE_PAGE" -> new SinglePage();
            case "LIST" -> new List();
            default -> throw new IllegalArgumentException("Unknown TaskMode code: " + code);
        };
    }

    @JsonValue
    default String jsonValue() {
        return code();
    }
}
