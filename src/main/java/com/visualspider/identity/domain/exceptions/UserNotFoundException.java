package com.visualspider.identity.domain.exceptions;

/**
 * 用户不存在。HTTP 404。
 */
public class UserNotFoundException extends RuntimeException {
    public UserNotFoundException(long userId) {
        super("账号不存在: id=" + userId);
    }
}
