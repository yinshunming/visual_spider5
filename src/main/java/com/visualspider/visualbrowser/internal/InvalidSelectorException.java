package com.visualspider.visualbrowser.internal;

/** 选择器语法错误（语法层面，不包含运行时匹配数）。 */
public class InvalidSelectorException extends RuntimeException {

    private final String kind;

    public InvalidSelectorException(String message, String kind) {
        super(message);
        this.kind = kind;
    }

    public String kind() {
        return kind;
    }
}
