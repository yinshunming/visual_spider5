package com.visualspider.identity.domain.exceptions;

/**
 * 未认证（无 session / session 过期）。HTTP 401。
 */
public class NotAuthenticatedException extends RuntimeException {
    public NotAuthenticatedException(String message) {
        super(message);
    }

    public static NotAuthenticatedException becauseSessionMissing() {
        return new NotAuthenticatedException("当前未登录");
    }
}
