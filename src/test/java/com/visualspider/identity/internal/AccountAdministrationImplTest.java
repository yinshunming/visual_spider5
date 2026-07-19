package com.visualspider.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.visualspider.identity.domain.ActorId;
import com.visualspider.identity.domain.ActorRole;
import com.visualspider.identity.domain.UserAccount;
import com.visualspider.identity.domain.UserStatus;
import com.visualspider.identity.domain.exceptions.DuplicateUsernameException;
import com.visualspider.identity.domain.exceptions.UserNotFoundException;
import com.visualspider.identity.domain.exceptions.WeakPasswordException;
import com.visualspider.identity.spi.AppUserRepository;
import com.visualspider.shared.security.ActorAuthentication;
import com.visualspider.shared.security.ActorPrincipal;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * AccountAdministrationImpl 单元测试。
 *
 * <p>覆盖：非 admin 抛 AccessDeniedException；admin 停用自己抛 AccessDeniedException；
 * 重复 username 抛 DuplicateUsernameException；弱密码抛 WeakPasswordException；
 * 正常路径调用 repository。
 */
@ExtendWith(MockitoExtension.class)
class AccountAdministrationImplTest {

    @Mock
    private AppUserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    private AccountAdministrationImpl admin;

    @BeforeEach
    void setUp() {
        admin = new AccountAdministrationImpl(userRepository, passwordEncoder);
        // 默认登录为 admin（id=1）
        setPrincipal(1L, "admin", new ActorRole.Admin());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("非 admin 调用 createAccount 抛 AccessDeniedException")
    void createAccount_nonAdmin_denied() {
        setPrincipal(2L, "alice", new ActorRole.Collector());
        char[] pwd = "valid-password-12+".toCharArray();
        assertThatThrownBy(() ->
                admin.createAccount("bob", pwd, new ActorRole.Collector(), new ActorId(2L)))
                .isInstanceOf(AccessDeniedException.class);
        verify(userRepository, never()).insert(anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("admin 创建账号：写入 repository 返回 id")
    void createAccount_admin_succeeds() {
        when(passwordEncoder.encode(anyString())).thenReturn("hashed-pwd");
        when(userRepository.insert(eq("bob"), eq("hashed-pwd"), any(ActorRole.class), eq(UserStatus.ACTIVE)))
                .thenReturn(42L);

        char[] pwd = "valid-password-12+".toCharArray();
        long id = admin.createAccount("bob", pwd, new ActorRole.Collector(), new ActorId(1L));
        assertThat(id).isEqualTo(42L);
        // pwd 已被清零（不可验证内容，但确保不抛异常即说明流程跑通）
    }

    @Test
    @DisplayName("重复 username 抛 DuplicateUsernameException")
    void createAccount_duplicateUsername_throws() {
        when(passwordEncoder.encode(anyString())).thenReturn("hashed");
        when(userRepository.insert(anyString(), anyString(), any(), any()))
                .thenThrow(new DuplicateKeyException("dup"));

        char[] pwd = "valid-password-12+".toCharArray();
        assertThatThrownBy(() ->
                admin.createAccount("bob", pwd, new ActorRole.Collector(), new ActorId(1L)))
                .isInstanceOf(DuplicateUsernameException.class);
    }

    @Test
    @DisplayName("弱密码抛 WeakPasswordException")
    void createAccount_weakPassword_throws() {
        char[] pwd = "short".toCharArray();
        assertThatThrownBy(() ->
                admin.createAccount("bob", pwd, new ActorRole.Collector(), new ActorId(1L)))
                .isInstanceOf(WeakPasswordException.class);
        verify(userRepository, never()).insert(anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("admin 停用自己抛 AccessDeniedException")
    void disableAccount_self_denied() {
        assertThatThrownBy(() -> admin.disableAccount(1L, new ActorId(1L)))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("不能停用自己");
        verify(userRepository, never()).updateStatus(anyLong(), any());
    }

    @Test
    @DisplayName("admin 停用别人成功")
    void disableAccount_otherSucceeds() {
        when(userRepository.findById(2L))
                .thenReturn(Optional.of(new UserAccount(new ActorId(2L), "alice",
                        new ActorRole.Collector(), UserStatus.ACTIVE)));

        admin.disableAccount(2L, new ActorId(1L));
        verify(userRepository, times(1)).updateStatus(eq(2L), eq(UserStatus.DISABLED));
    }

    @Test
    @DisplayName("停用不存在的用户抛 UserNotFoundException")
    void disableAccount_notFound_throws() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> admin.disableAccount(99L, new ActorId(1L)))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    @DisplayName("重置密码成功")
    void resetPassword_succeeds() {
        when(userRepository.findById(2L))
                .thenReturn(Optional.of(new UserAccount(new ActorId(2L), "alice",
                        new ActorRole.Collector(), UserStatus.ACTIVE)));
        when(passwordEncoder.encode(anyString())).thenReturn("new-hash");

        char[] pwd = "new-valid-password-12+".toCharArray();
        admin.resetPassword(2L, pwd, new ActorId(1L));
        verify(userRepository, times(1)).updatePasswordHash(eq(2L), eq("new-hash"));
    }

    @Test
    @DisplayName("重置弱密码抛 WeakPasswordException")
    void resetPassword_weak_throws() {
        char[] pwd = "short".toCharArray();
        assertThatThrownBy(() -> admin.resetPassword(2L, pwd, new ActorId(1L)))
                .isInstanceOf(WeakPasswordException.class);
        verify(userRepository, never()).updatePasswordHash(anyLong(), anyString());
    }

    private static void setPrincipal(long actorId, String username, ActorRole role) {
        ActorPrincipal principal = new ActorPrincipal(new ActorId(actorId), username, role);
        SecurityContextHolder.getContext().setAuthentication(new ActorAuthentication(principal));
    }
}
