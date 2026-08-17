package com.visualspider.identity.domain.exceptions;

/**
 * 弱密码（trim 后长度 < 12）。HTTP 400。
 */
public class WeakPasswordException extends RuntimeException {
    private static final int MIN_LENGTH = 12;

    public WeakPasswordException() {
        super("密码强度不足：trim 后长度必须 ≥ " + MIN_LENGTH + " 字符");
    }
}
