package com.visualspider.identity.spi;

import com.visualspider.identity.domain.ActorId;

/**
 * 身份认证 SPI。
 *
 * <p>其它模块通过此 interface 登录/退出/查询认证状态；
 * 底层 BCrypt + HttpSession 细节隐藏在 {@code identity} 模块实现类内。
 */
public interface Authentication {

    /**
     * 登录。
     *
     * @param username 登录名（不可为空）
     * @param rawPassword 明文密码（不可为空）；实现内部 BCrypt 哈希后立即 {@code Arrays.fill('\0')}
     * @throws com.visualspider.identity.domain.exceptions.AuthenticationFailedException 用户名或密码错误 / 账号停用
     */
    void login(String username, char[] rawPassword);

    /**
     * 登出当前 session。不存在 session 时为 no-op。
     *
     * @param actor 调用者；用于校验一致性（可选）
     */
    void logout(ActorId actor);

    /**
     * 当前是否已认证。
     */
    boolean isAuthenticated();
}
