package com.visualspider.identity.internal;

import com.visualspider.identity.domain.ActorId;
import com.visualspider.identity.domain.ActorRole;
import com.visualspider.identity.domain.UserAccount;
import com.visualspider.identity.domain.UserStatus;
import com.visualspider.identity.domain.exceptions.DuplicateUsernameException;
import com.visualspider.identity.domain.exceptions.UserNotFoundException;
import com.visualspider.identity.domain.exceptions.WeakPasswordException;
import com.visualspider.identity.spi.AccountAdministration;
import com.visualspider.identity.spi.AppUserRepository;
import com.visualspider.shared.security.ActorAuthentication;
import com.visualspider.shared.security.ActorPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Arrays;

/**
 * {@link AccountAdministration} 的默认实现。
 *
 * <p>每个方法先校验调用者为 admin；密码统一通过 {@link PasswordEncoder} 编码，
 * {@code char[]} 参数在哈希后立即清零（spec §D5）。
 */
@Service
public class AccountAdministrationImpl implements AccountAdministration {

    private static final Logger LOG = LoggerFactory.getLogger(AccountAdministrationImpl.class);

    private static final int MIN_PASSWORD_LENGTH = 12;

    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public AccountAdministrationImpl(AppUserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    private ActorPrincipal requireAdmin() {
        SecurityContext context = SecurityContextHolder.getContextHolderStrategy().getContext();
        if (context == null || !(context.getAuthentication() instanceof ActorAuthentication auth)) {
            throw new AccessDeniedException("未登录");
        }
        ActorPrincipal principal = auth.actorPrincipal();
        if (!principal.isAdmin()) {
            throw new AccessDeniedException("需要 admin 权限");
        }
        return principal;
    }

    private void validatePassword(char[] rawPassword) {
        if (rawPassword == null || rawPassword.length == 0) {
            throw new WeakPasswordException();
        }
        String trimmed = new String(rawPassword).trim();
        if (trimmed.length() < MIN_PASSWORD_LENGTH) {
            throw new WeakPasswordException();
        }
    }

    @Override
    public long createAccount(String username, char[] rawPassword, ActorRole role, ActorId actor) {
        ActorPrincipal admin = requireAdmin();
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username 不能为空");
        }
        validatePassword(rawPassword);

        String hash = passwordEncoder.encode(new String(rawPassword));
        Arrays.fill(rawPassword, '\0');

        try {
            long id = userRepository.insert(username.trim(), hash, role, UserStatus.ACTIVE);
            LOG.info("createAccount: id={} username={} role={} by adminId={}",
                    id, username, role instanceof ActorRole.Admin ? "ADMIN" : "COLLECTOR", admin.actorId().value());
            return id;
        } catch (DuplicateKeyException e) {
            throw new DuplicateUsernameException(username);
        }
    }

    @Override
    public void disableAccount(long userId, ActorId actor) {
        ActorPrincipal admin = requireAdmin();
        if (admin.actorId().value() == userId) {
            throw new AccessDeniedException("管理员不能停用自己");
        }
        userRepository.findById(userId).orElseThrow(() -> new UserNotFoundException(userId));
        userRepository.updateStatus(userId, UserStatus.DISABLED);
        LOG.info("disableAccount: id={} by adminId={}", userId, admin.actorId().value());
    }

    @Override
    public void resetPassword(long userId, char[] newRawPassword, ActorId actor) {
        ActorPrincipal admin = requireAdmin();
        validatePassword(newRawPassword);
        userRepository.findById(userId).orElseThrow(() -> new UserNotFoundException(userId));
        String hash = passwordEncoder.encode(new String(newRawPassword));
        Arrays.fill(newRawPassword, '\0');
        userRepository.updatePasswordHash(userId, hash);
        LOG.info("resetPassword: id={} by adminId={}", userId, admin.actorId().value());
    }

    @Override
    public void enableAccount(long userId, ActorId actor) {
        ActorPrincipal admin = requireAdmin();
        userRepository.findById(userId).orElseThrow(() -> new UserNotFoundException(userId));
        userRepository.updateStatus(userId, UserStatus.ACTIVE);
        LOG.info("enableAccount: id={} by adminId={}", userId, admin.actorId().value());
    }

    @Override
    public UserAccount findById(long userId, ActorId actor) {
        requireAdmin();
        return userRepository.findById(userId).orElseThrow(() -> new UserNotFoundException(userId));
    }
}
