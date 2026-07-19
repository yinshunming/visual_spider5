package com.visualspider.identity.spi;

import com.visualspider.identity.domain.ActorId;
import com.visualspider.identity.domain.ActorRole;
import com.visualspider.identity.domain.UserAccount;

/**
 * 账号管理 SPI（仅 admin 可调用）。
 *
 * <p>M1-2 落地：创建 / 停用 / 启用 / 重置密码。
 * 不做：公开注册 / 找回密码 / MFA / 自定义角色 / 任务共享 / 协同编辑（产品 §4）。
 */
public interface AccountAdministration {

    /**
     * 创建账号。
     *
     * @return 新账号 id
     * @throws com.visualspider.identity.domain.exceptions.AccessDeniedException 调用者非 admin
     * @throws com.visualspider.identity.domain.exceptions.DuplicateUsernameException username 已存在
     * @throws com.visualspider.identity.domain.exceptions.WeakPasswordException 密码 < 12 字符
     */
    long createAccount(String username, char[] rawPassword, ActorRole role, ActorId actor);

    /** 停用账号；admin 不能停自己。 */
    void disableAccount(long userId, ActorId actor);

    /** 重置密码。 */
    void resetPassword(long userId, char[] newRawPassword, ActorId actor);

    /** 启用账号（恢复 ACTIVE）。 */
    void enableAccount(long userId, ActorId actor);

    /** 查询账号详情。 */
    UserAccount findById(long userId, ActorId actor);
}
