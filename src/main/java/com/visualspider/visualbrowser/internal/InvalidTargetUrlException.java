package com.visualspider.visualbrowser.internal;

/** 目标 URL 不符合当前里程碑策略。 */
public final class InvalidTargetUrlException extends RuntimeException {

    public InvalidTargetUrlException() {
        super("目标 URL 必须是包含合法主机名的 http(s) URL");
    }
}
