package com.visualspider.identity.domain.exceptions;

/**
 * 创建账号时 username 已存在。HTTP 409。
 */
public class DuplicateUsernameException extends RuntimeException {
    public DuplicateUsernameException(String username) {
        super("用户名已存在: " + username);
    }
}
