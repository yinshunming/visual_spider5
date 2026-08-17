package com.visualspider.identity.domain;

/**
 * 账号状态。
 *
 * <p>{@code DISABLED} 时 {@code Authentication.login} 拒绝，
 * 错误信息不回显"账号已停用"以避免用户名枚举。
 */
public enum UserStatus {
    ACTIVE,
    DISABLED
}
