package com.visualspider.identity.spi;

import com.visualspider.identity.domain.ActorRole;
import com.visualspider.identity.domain.UserAccount;
import com.visualspider.identity.domain.UserStatus;

import java.util.Optional;

/**
 * app_user 表数据访问。
 *
 * <p>M1-2 仅暴露最小查询接口；不在仓库层实现 BCrypt / 校验，
 * 留给 service 层组合 PasswordEncoder。
 */
public interface AppUserRepository {

    /** 按 id 查；包含 status 信息（即使停用也返回）。 */
    Optional<UserAccount> findById(long id);

    /** 按 username 查（用于登录）。 */
    Optional<UserAccount> findByUsername(String username);

    /**
     * 读取 password_hash（仅供 {@code AuthenticationService} 比对）。
     *
     * @return BCrypt 哈希字符串；用户不存在返回 {@code Optional.empty()}
     */
    Optional<String> findPasswordHashByUsername(String username);

    /**
     * 插入新账号。返回生成的 id。
     *
     * @throws org.springframework.dao.DuplicateKeyException username 唯一约束冲突（service 层捕获并转为 {@code DuplicateUsernameException}）
     */
    long insert(String username, String passwordHash, ActorRole role, UserStatus status);

    /** 更新密码哈希。 */
    void updatePasswordHash(long userId, String newPasswordHash);

    /** 更新状态。 */
    void updateStatus(long userId, UserStatus newStatus);
}
